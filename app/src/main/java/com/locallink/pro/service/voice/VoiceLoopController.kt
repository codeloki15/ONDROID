package com.locallink.pro.service.voice

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.locallink.pro.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

enum class LoopState { IDLE, WAKE_LISTENING, CAPTURING, THINKING, SPEAKING }

/**
 * Hands-free voice loop (continuous conversation mode):
 *   WAKE_LISTENING → (wake word) chime → CAPTURING (STT) → THINKING (LLM+tools)
 *   → SPEAKING (TTS) → CAPTURING (re-listen, no wake word) → …
 *
 * The wake word ("Hey Omni") only *starts* the conversation. After each reply the mic
 * re-opens for the next turn so you can talk back-and-forth. It returns to WAKE_LISTENING
 * when you say a stop phrase ("stop", "goodbye", …) or after [MAX_SILENCES] empty captures.
 *
 * Owns the single mic token: the wake engine, STT, and TTS are time-exclusive. A turnId
 * guard prevents a slow/old reply from corrupting a newer turn.
 */
@Singleton
class VoiceLoopController @Inject constructor(
    private val voice: VoiceService,
    private val wake: WakeWordEngine,
    private val chat: ChatRepository,
) {
    companion object {
        private const val TAG = "VoiceLoop"
        private const val HANDOFF_MS = 250L // guard between mic owners (native release is slow)
        private const val MAX_SILENCES = 2  // empty/timeout captures before dropping to wake word
        private const val SPEECH_START_MS = 2_500L  // grace for the first queued utterance to begin
        private const val SPEECH_MAX_MS = 90_000L   // ceiling on waiting out a long spoken reply
        // Phrases that end the active conversation (back to wake-word idle).
        private val STOP_PHRASES = setOf("stop", "stop listening", "goodbye", "bye omni", "that's all", "thats all", "exit", "cancel")
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // Mic lifecycle (wake start/stop) is blocking — keep it OFF the main thread.
    private val micScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collectorsStarted = false
    private var turnId = 0
    private var silences = 0
    private var tone: ToneGenerator? = null

    private val _state = MutableStateFlow(LoopState.IDLE)
    val state: StateFlow<LoopState> = _state.asStateFlow()

    fun start() {
        if (_state.value != LoopState.IDLE) return
        voice.initialize()
        wake.onWake = { main.post { onWakeWord() } }
        if (!collectorsStarted) {
            collectorsStarted = true
            scope.launch {
                voice.finalResult.collect { text -> if (_state.value == LoopState.CAPTURING) onUtterance(text) }
            }
            scope.launch {
                // No speech captured (timeout/no-match): retry listening a couple times, then idle.
                voice.sttError.collect { if (_state.value == LoopState.CAPTURING) onSilence() }
            }
            scope.launch {
                // Stream the live transcript into the system-wide floater while capturing.
                voice.partialResult.collect { p ->
                    if (_state.value == LoopState.CAPTURING) {
                        com.locallink.pro.service.pilot.OmniAccessibilityService.instance?.updateTranscript(p)
                    }
                }
            }
        }
        runCatching { tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }
        enterWakeListening()
    }

    fun stop() {
        turnId++
        _state.value = LoopState.IDLE  // set first so any in-flight callbacks no-op
        voice.stopListening()
        voice.stopSpeaking()
        runCatching { tone?.release() }; tone = null
        micScope.launch { wake.stop() }  // blocking join — off main
    }

    private fun enterWakeListening() {
        voice.stopListening()
        _state.value = LoopState.WAKE_LISTENING
        // start() does model-load + AudioRecord init (blocking) — run off the main thread.
        main.postDelayed({
            if (_state.value == LoopState.WAKE_LISTENING) micScope.launch { wake.start() }
        }, HANDOFF_MS)
    }

    private fun onWakeWord() {
        if (_state.value != LoopState.WAKE_LISTENING) return
        silences = 0
        // Claim CAPTURING now (closes the window for a second wake event), chime, show the
        // live-transcription floater over whatever app is open, then free the wake mic
        // off-main and only start STT once it's actually released.
        _state.value = LoopState.CAPTURING
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }
        com.locallink.pro.service.pilot.OmniAccessibilityService.instance?.showTranscript()
        micScope.launch {
            wake.stop() // blocks until the wake worker exits + AudioRecord released
            main.post { if (_state.value == LoopState.CAPTURING) voice.startListening() }
        }
    }

    /** Re-open the mic for the next conversation turn (no wake word). Mic is already free here. */
    private fun beginCapture(chime: Boolean) {
        if (chime) runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }
        _state.value = LoopState.CAPTURING
        main.postDelayed({ if (_state.value == LoopState.CAPTURING) voice.startListening() }, HANDOFF_MS)
    }

    /** Empty capture: re-listen a few times, then fall back to wake-word idle. */
    private fun onSilence() {
        voice.stopListening()
        if (++silences >= MAX_SILENCES) { silences = 0; enterWakeListening() }
        else main.postDelayed({ if (_state.value == LoopState.CAPTURING) voice.startListening() }, HANDOFF_MS)
    }

    private fun onUtterance(text: String) {
        val svc = com.locallink.pro.service.pilot.OmniAccessibilityService.instance
        if (text.isBlank()) { onSilence(); return }
        // Stop phrase ends the conversation.
        if (text.trim().lowercase().trimEnd('.', '!', '?') in STOP_PHRASES) {
            silences = 0
            svc?.hideTranscript()
            enterWakeListening()
            return
        }
        silences = 0
        _state.value = LoopState.THINKING
        svc?.updateTranscript(text)
        val myTurn = ++turnId
        scope.launch {
            try {
                // Spoken input carries no mode, so decide what this turn actually is. A question
                // gets answered and spoken; only a real instruction is worth foregrounding the
                // app and running the agent for.
                if (chat.isPhoneAction(text)) {
                    // Hand the task to the AUTOMATE agent (plans, replays learned routines, drives
                    // the phone) and bring the app to the foreground so the user watches it work.
                    openApp()
                    chat.runAgent(text)
                    if (myTurn != turnId) return@launch
                    svc?.hideTranscript()
                    voice.speak("On it.")
                } else {
                    // Answer in place: no app switch, no pilot. Composio tools still work here.
                    chat.runChatWithTools(text, isVoice = true)
                    if (myTurn != turnId) return@launch
                    svc?.hideTranscript()
                    chat.lastAssistantReply.value
                        .takeIf { it.isNotBlank() }
                        ?.let { voice.speak(it) }
                }
                // Return to wake-word listening so "Hey Omni" keeps working — but not before TTS
                // finishes: the mic is single-owner, and reopening it under a live utterance is
                // what produces ERROR_NO_MATCH. A spoken answer can run far past the old fixed wait.
                awaitSpeechEnd()
                if (myTurn == turnId) enterWakeListening()
            } catch (e: Exception) {
                Log.e(TAG, "turn failed", e)
                svc?.hideTranscript()
                if (myTurn == turnId) enterWakeListening()
            }
        }
    }

    /**
     * Block until TTS has finished (or [SPEECH_MAX_MS] elapses), so the mic is genuinely free
     * before the wake engine reclaims it. Speech is queued sentence-by-sentence, so allow a
     * moment for the first utterance to start before concluding nothing is being said.
     */
    private suspend fun awaitSpeechEnd() {
        withTimeoutOrNull(SPEECH_START_MS) { voice.isSpeaking.first { it } }
        withTimeoutOrNull(SPEECH_MAX_MS) { voice.isSpeaking.first { !it } }
        kotlinx.coroutines.delay(HANDOFF_MS)
    }

    /** Bring OmniPro to the foreground (best-effort) so the hands-free task is visible. */
    private fun openApp() {
        runCatching {
            val ctx = com.locallink.pro.service.pilot.OmniAccessibilityService.instance ?: return
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            } ?: return
            ctx.startActivity(intent)
        }
    }

}
