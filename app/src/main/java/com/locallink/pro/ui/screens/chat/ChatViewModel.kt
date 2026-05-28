package com.locallink.pro.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locallink.pro.data.repository.ChatRepository
import com.locallink.pro.domain.model.ConnectionInfo
import com.locallink.pro.domain.model.Message
import com.locallink.pro.domain.model.ToolCallInfo
import com.locallink.pro.service.transport.TransportManager
import com.locallink.pro.service.voice.VoiceService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val connectionInfo: ConnectionInfo = ConnectionInfo(),
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val isAiResponding: Boolean = false,
    val partialVoiceResult: String = "",
    val streamingText: String = "",
    val autoTts: Boolean = true,
    val activeToolCalls: List<ToolCallInfo> = emptyList(),
    val selectedModel: String = "Claude Sonnet",
    val availableModels: List<String> = listOf("Claude Sonnet", "Claude Haiku", "GPT-4o", "Ollama"),
    val showModelSelector: Boolean = false,
    // Pagination state
    val isLoadingHistory: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasMoreHistory: Boolean = true,
    val historyError: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val transportManager: TransportManager,
    private val voiceService: VoiceService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        voiceService.initialize()

        // Sync autoTts from VoiceService (persisted singleton)
        viewModelScope.launch {
            voiceService.autoTts.collect { enabled ->
                _uiState.update { it.copy(autoTts = enabled) }
            }
        }

        // Collect messages
        viewModelScope.launch {
            chatRepository.messages.collect { messages ->
                _uiState.update { it.copy(messages = messages) }

                // Auto-TTS for latest AI message
                val latest = messages.lastOrNull()
                if (latest != null &&
                    latest.sender == com.locallink.pro.domain.model.MessageSender.AI &&
                    _uiState.value.autoTts
                ) {
                    voiceService.speak(latest.text)
                }
            }
        }

        // Collect connection state
        viewModelScope.launch {
            transportManager.activeConnection.collect { info ->
                _uiState.update { it.copy(connectionInfo = info) }
            }
        }

        // Collect streaming text
        viewModelScope.launch {
            chatRepository.streamingText.collect { text ->
                _uiState.update { it.copy(streamingText = text) }
            }
        }

        // Collect AI responding state
        viewModelScope.launch {
            chatRepository.isAiResponding.collect { responding ->
                _uiState.update { it.copy(isAiResponding = responding) }
            }
        }

        // Collect active tool calls
        viewModelScope.launch {
            chatRepository.activeToolCalls.collect { toolCalls ->
                _uiState.update { it.copy(activeToolCalls = toolCalls) }
            }
        }

        // Collect pagination state
        viewModelScope.launch {
            chatRepository.isLoadingHistory.collect { loading ->
                _uiState.update { it.copy(isLoadingHistory = loading) }
            }
        }
        viewModelScope.launch {
            chatRepository.hasMoreHistory.collect { hasMore ->
                _uiState.update { it.copy(hasMoreHistory = hasMore) }
            }
        }

        // Load chat history on init
        loadChatHistory()

        // Collect voice state
        viewModelScope.launch {
            voiceService.isListening.collect { listening ->
                _uiState.update { it.copy(isListening = listening) }
            }
        }
        viewModelScope.launch {
            voiceService.isSpeaking.collect { speaking ->
                _uiState.update { it.copy(isSpeaking = speaking) }
            }
        }
        viewModelScope.launch {
            voiceService.partialResult.collect { partial ->
                _uiState.update { it.copy(partialVoiceResult = partial) }
            }
        }

        // Auto-send voice final results
        viewModelScope.launch {
            voiceService.finalResult.collect { text ->
                if (text.isNotBlank()) {
                    sendMessage(text, isVoice = true)
                }
            }
        }
    }

    fun updateInput(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage(text: String? = null, isVoice: Boolean = false) {
        val messageText = text ?: _uiState.value.inputText
        if (messageText.isBlank()) return

        _uiState.update { it.copy(inputText = "") }

        viewModelScope.launch {
            chatRepository.sendUserMessage(messageText, isVoice)
        }
    }

    fun toggleVoiceInput() {
        if (_uiState.value.isListening) {
            voiceService.stopListening()
        } else {
            voiceService.startListening()
        }
    }

    fun stopTts() {
        voiceService.stopSpeaking()
    }

    fun toggleAutoTts() {
        voiceService.setAutoTts(!_uiState.value.autoTts)
    }

    fun clearChat() {
        chatRepository.clearMessages()
    }

    fun selectModel(model: String) {
        _uiState.update { it.copy(selectedModel = model, showModelSelector = false) }
    }

    fun toggleModelSelector() {
        _uiState.update { it.copy(showModelSelector = !it.showModelSelector) }
    }

    /**
     * Load chat history from the server (initial load).
     */
    fun loadChatHistory() {
        viewModelScope.launch {
            _uiState.update { it.copy(historyError = null) }
            chatRepository.loadChatHistory(refresh = true).onFailure { error ->
                _uiState.update { it.copy(historyError = error.message) }
            }
        }
    }

    /**
     * Refresh chat history (pull-to-refresh).
     */
    fun refreshChatHistory() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, historyError = null) }
            chatRepository.reloadChatHistory().fold(
                onSuccess = {
                    _uiState.update { it.copy(isRefreshing = false) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isRefreshing = false, historyError = error.message) }
                }
            )
        }
    }

    /**
     * Load more (older) messages for pagination.
     */
    fun loadMoreHistory() {
        if (_uiState.value.isLoadingHistory || !_uiState.value.hasMoreHistory) return

        viewModelScope.launch {
            chatRepository.loadMoreHistory().onFailure { error ->
                _uiState.update { it.copy(historyError = error.message) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceService.shutdown()
    }
}
