package com.locallink.pro.ui.screens.chat

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locallink.pro.data.repository.ChatRepository
import com.locallink.pro.domain.model.Message
import com.locallink.pro.domain.model.MessageSender
import com.locallink.pro.service.image.ImageService
import com.locallink.pro.service.voice.VoiceService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val pendingImageUri: Uri? = null,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val isAiResponding: Boolean = false,
    val partialVoiceResult: String = "",
    val streamingText: String = "",
    val autoTts: Boolean = true,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val voiceService: VoiceService,
    private val imageService: ImageService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** Composio OAuth links to open in a Custom Tab (agent is connecting an app in-flow). */
    val authUrlToOpen = chatRepository.authUrlToOpen

    /** Fires once per completed voice capture — the UI uses it to dismiss voice mode. */
    val voiceFinal = voiceService.finalResult

    init {
        voiceService.initialize()
        viewModelScope.launch { voiceService.autoTts.collect { e -> _uiState.update { it.copy(autoTts = e) } } }
        viewModelScope.launch {
            chatRepository.observeMessages().collect { msgs ->
                val prevLast = _uiState.value.messages.lastOrNull()?.id
                _uiState.update { it.copy(messages = msgs) }
                val latest = msgs.lastOrNull()
                if (latest != null && latest.id != prevLast &&
                    latest.sender == MessageSender.AI && _uiState.value.autoTts
                ) {
                    speakFinalReply(latest.text)
                }
            }
        }
        viewModelScope.launch {
            chatRepository.streamingText.collect { t ->
                _uiState.update { it.copy(streamingText = t) }
                if (_uiState.value.autoTts) speakCompletedSentences(t)
            }
        }
        viewModelScope.launch { chatRepository.isAiResponding.collect { r -> _uiState.update { it.copy(isAiResponding = r) } } }
        viewModelScope.launch { voiceService.isListening.collect { l -> _uiState.update { it.copy(isListening = l) } } }
        viewModelScope.launch { voiceService.isSpeaking.collect { s -> _uiState.update { it.copy(isSpeaking = s) } } }
        viewModelScope.launch { voiceService.partialResult.collect { p -> _uiState.update { it.copy(partialVoiceResult = p) } } }
        viewModelScope.launch {
            voiceService.finalResult.collect { text -> if (text.isNotBlank()) sendMessage(text, isVoice = true) }
        }
    }

    // ── Streamed speech ─────────────────────────────────────────────────────────
    // Exactly the text already handed to TTS this turn. The reply is spoken sentence by
    // sentence as it streams, so speech starts after the model's FIRST sentence instead of
    // after the whole response; this prefix is what tells us the remainder still to say.
    private var spokenPrefix = ""

    /**
     * Speak any sentences that have become complete since the last update. A sentence counts
     * as complete only once its terminator is followed by whitespace — so a streamed "3." that
     * is about to become "3.5" is never split mid-number. The trailing sentence therefore has
     * no whitespace after it and is left to [speakFinalReply].
     */
    private fun speakCompletedSentences(full: String) {
        if (full.isEmpty()) return                       // turn boundary — leave the prefix alone
        if (!full.startsWith(spokenPrefix)) spokenPrefix = ""   // stream restarted under us
        var cursor = spokenPrefix.length
        while (true) {
            val end = firstCompleteSentenceEnd(full, cursor)
            if (end < 0) break
            full.substring(cursor, end).trim()
                .takeIf { it.isNotEmpty() }
                ?.let { voiceService.enqueueSpeech(it) }
            cursor = end
        }
        if (cursor > spokenPrefix.length) spokenPrefix = full.substring(0, cursor)
    }

    /**
     * Queue [text] ONE SENTENCE AT A TIME. Handing Kokoro a whole paragraph makes it generate the
     * entire block before emitting any audio (measured on-device: 6.4s for a multi-sentence block
     * vs ~1.1s per single sentence), which defeats the point of streaming.
     *
     * @param bargeInFirst speak the first sentence via [VoiceService.speak] so it cuts off any
     *   stale utterance; the rest are appended. Used when nothing streamed for this turn.
     */
    private fun enqueueBySentence(text: String, bargeInFirst: Boolean) {
        var barge = bargeInFirst
        fun say(s: String) {
            if (barge) { barge = false; voiceService.speak(s) } else voiceService.enqueueSpeech(s)
        }
        var cursor = 0
        while (cursor < text.length) {
            val end = firstCompleteSentenceEnd(text, cursor)
            if (end < 0) break
            text.substring(cursor, end).trim().takeIf { it.isNotEmpty() }?.let { say(it) }
            cursor = end
        }
        // Trailing fragment with no terminator — the last sentence of most replies.
        text.substring(cursor).trim().takeIf { it.isNotEmpty() }?.let { say(it) }
    }

    /**
     * Close out the turn: say whatever streaming didn't already cover. Usually just the final
     * sentence; the whole reply when nothing streamed (tool-only turns, cached replies), since
     * the persisted text can also differ from the stream (e.g. an appended connect-app nudge).
     */
    private fun speakFinalReply(text: String) {
        val already = spokenPrefix
        spokenPrefix = ""
        val streamed = already.isNotEmpty() && text.startsWith(already)
        val remainder = if (streamed) text.substring(already.length) else text
        if (remainder.isBlank()) return
        // Nothing streamed for this turn → barge in, so a stale utterance doesn't run over this one.
        enqueueBySentence(remainder, bargeInFirst = already.isEmpty())
    }

    /** End index (exclusive) of the FIRST finished sentence at/after [from], or -1 if none yet. */
    private fun firstCompleteSentenceEnd(text: String, from: Int): Int {
        for (i in from until text.length) {
            val c = text[i]
            if (c == '\n') return i + 1
            if (c != '.' && c != '!' && c != '?') continue
            if (i + 1 < text.length && text[i + 1].isWhitespace()) return i + 1
        }
        return -1
    }

    // Entry mode from the home cards: "chat" = plain conversation, "voice" = chat + voice UX,
    // "auto" = full agent (planner + device control). History opens default to "auto".
    private var mode: String = "auto"
    fun setMode(m: String) { mode = m }

    fun openSession(id: String?) {
        if (id == null) chatRepository.newSession() else chatRepository.loadSession(id)
    }

    fun updateInput(text: String) = _uiState.update { it.copy(inputText = text) }
    fun attachImage(uri: Uri?) = _uiState.update { it.copy(pendingImageUri = uri) }

    fun sendMessage(text: String? = null, isVoice: Boolean = false) {
        val messageText = text ?: _uiState.value.inputText
        val imageUri = _uiState.value.pendingImageUri
        if (messageText.isBlank() && imageUri == null) return

        val trimmed = messageText.trim()
        _uiState.update { it.copy(inputText = "", pendingImageUri = null) }
        spokenPrefix = "" // new turn: nothing spoken yet (covers turns that ended on an error)

        // Escape hatch: "/pilot <task>" forces the raw device-control loop (debug/bypass planner).
        if (trimmed.startsWith("/pilot ", ignoreCase = true)) {
            val task = trimmed.substring("/pilot ".length).trim()
            if (task.isNotBlank()) viewModelScope.launch { chatRepository.runPilot(task) }
            return
        }

        // Route by entry mode: chat/voice = plain conversation (fast, no device control);
        // auto = the planning agent. Image messages still use the legacy multimodal send.
        viewModelScope.launch {
            when {
                imageUri != null -> {
                    val bitmap: Bitmap? = imageService.loadForInference(imageUri)
                    chatRepository.send(text = messageText, image = bitmap, imageUri = imageUri.toString(), isVoice = isVoice)
                }
                mode == "chat" || mode == "voice" -> chatRepository.runChatOnly(messageText)
                else -> chatRepository.runAgent(messageText)
            }
        }
    }

    fun toggleVoiceInput() {
        if (_uiState.value.isListening) voiceService.stopListening() else voiceService.startListening()
    }

    fun stopTts() = voiceService.stopSpeaking()
    fun toggleAutoTts() = voiceService.setAutoTts(!_uiState.value.autoTts)

    /** Speak an arbitrary message aloud (used by the message "Speak" action). */
    fun speak(text: String) { if (text.isNotBlank()) voiceService.speak(text) }

    /** Drop the last reply and ask the model again on the same user turn. */
    fun regenerate() {
        if (_uiState.value.isAiResponding) return
        viewModelScope.launch { chatRepository.regenerateLast() }
    }

    /** Put a message back into the input box so the user can edit and resend it. */
    fun editAndResend(text: String) = _uiState.update { it.copy(inputText = text) }

    override fun onCleared() {
        super.onCleared()
        voiceService.shutdown()
    }
}
