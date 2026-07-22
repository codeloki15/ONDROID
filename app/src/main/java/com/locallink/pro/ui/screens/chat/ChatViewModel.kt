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
                    voiceService.speak(latest.text)
                }
            }
        }
        viewModelScope.launch { chatRepository.streamingText.collect { t -> _uiState.update { it.copy(streamingText = t) } } }
        viewModelScope.launch { chatRepository.isAiResponding.collect { r -> _uiState.update { it.copy(isAiResponding = r) } } }
        viewModelScope.launch { voiceService.isListening.collect { l -> _uiState.update { it.copy(isListening = l) } } }
        viewModelScope.launch { voiceService.isSpeaking.collect { s -> _uiState.update { it.copy(isSpeaking = s) } } }
        viewModelScope.launch { voiceService.partialResult.collect { p -> _uiState.update { it.copy(partialVoiceResult = p) } } }
        viewModelScope.launch {
            voiceService.finalResult.collect { text -> if (text.isNotBlank()) sendMessage(text, isVoice = true) }
        }
    }

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

        // Escape hatch: "/pilot <task>" forces the raw device-control loop (debug/bypass planner).
        if (trimmed.startsWith("/pilot ", ignoreCase = true)) {
            val task = trimmed.substring("/pilot ".length).trim()
            if (task.isNotBlank()) viewModelScope.launch { chatRepository.runPilot(task) }
            return
        }

        // Default: route every message through the planning agent (no prefix). It decides
        // chat / composio / pilot per todo. Image messages still use the plain chat send.
        viewModelScope.launch {
            if (imageUri == null) {
                chatRepository.runAgent(messageText)
            } else {
                val bitmap: Bitmap? = imageService.loadForInference(imageUri)
                chatRepository.send(text = messageText, image = bitmap, imageUri = imageUri.toString(), isVoice = isVoice)
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
