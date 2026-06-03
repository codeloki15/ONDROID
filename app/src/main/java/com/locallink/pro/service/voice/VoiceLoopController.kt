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
import javax.inject.Inject
import javax.inject.Singleton

enum class LoopState { IDLE, WAKE_LISTENING, CAPTURING, THINKING, SPEAKING }

/**
 * Hands-free voice loop:
 *   WAKE_LISTENING → (wake word) chime → CAPTURING (STT) → THINKING (LLM+tools)
 *   → SPEAKING (TTS) → back to WAKE_LISTENING.
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
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var collectorsStarted = false
    private var turnId = 0
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
                voice.sttError.collect { if (_state.value == LoopState.CAPTURING) enterWakeListening() }
            }
        }
        runCatching { tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }
        enterWakeListening()
    }

    fun stop() {
        turnId++
        wake.stop()
        voice.stopListening()
        voice.stopSpeaking()
        runCatching { tone?.release() }; tone = null
        _state.value = LoopState.IDLE
    }

    private fun enterWakeListening() {
        voice.stopListening()
        _state.value = LoopState.WAKE_LISTENING
        main.postDelayed({ if (_state.value == LoopState.WAKE_LISTENING) wake.start() }, HANDOFF_MS)
    }

    private fun onWakeWord() {
        if (_state.value != LoopState.WAKE_LISTENING) return
        wake.stop()
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }
        _state.value = LoopState.CAPTURING
        main.postDelayed({ if (_state.value == LoopState.CAPTURING) voice.startListening() }, HANDOFF_MS)
    }

    private fun onUtterance(text: String) {
        if (text.isBlank()) { enterWakeListening(); return }
        _state.value = LoopState.THINKING
        val myTurn = ++turnId
        scope.launch {
            try {
                chat.send(text, isVoice = true)
                if (myTurn != turnId) return@launch
                speakThenResume(chat.lastAssistantReply.value)
            } catch (e: Exception) {
                Log.e(TAG, "turn failed", e)
                if (myTurn == turnId) enterWakeListening()
            }
        }
    }

    private fun speakThenResume(reply: String) {
        if (reply.isBlank()) { enterWakeListening(); return }
        _state.value = LoopState.SPEAKING
        val myTurn = turnId
        voice.stopListening() // ensure mic free before TTS
        voice.speak(reply)
        scope.launch {
            // wait for TTS to start then finish
            voice.isSpeaking.dropWhile { !it }.first { !it }
            if (myTurn == turnId && _state.value == LoopState.SPEAKING) enterWakeListening()
        }
    }
}
