package com.locallink.pro.data.repository

import android.util.Log
import com.locallink.pro.data.model.ChatHistoryMessage
import com.locallink.pro.data.model.MessageTypes
import com.locallink.pro.data.model.ProtocolMessage
import com.locallink.pro.domain.model.Message
import com.locallink.pro.domain.model.MessageSender
import com.locallink.pro.domain.model.MessageStatus
import com.locallink.pro.domain.model.MessageType
import com.locallink.pro.domain.model.SubToolInfo
import com.locallink.pro.domain.model.ToolCallInfo
import com.locallink.pro.domain.model.TransportType
import com.locallink.pro.service.notification.NotificationHelper
import com.locallink.pro.service.rest.OmniPinApiClient
import com.locallink.pro.service.transport.TransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val transportManager: TransportManager,
    private val apiClient: OmniPinApiClient,
    private val notificationHelper: NotificationHelper,
) {
    companion object {
        private const val TAG = "ChatRepository"
        private const val DEFAULT_PAGE_SIZE = 50
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    // Streaming buffer for AI responses that arrive in chunks
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _isAiResponding = MutableStateFlow(false)
    val isAiResponding: StateFlow<Boolean> = _isAiResponding.asStateFlow()

    // Active tool calls during an AI response
    private val _activeToolCalls = MutableStateFlow<List<ToolCallInfo>>(emptyList())
    val activeToolCalls: StateFlow<List<ToolCallInfo>> = _activeToolCalls.asStateFlow()

    // Pagination state
    private val _isLoadingHistory = MutableStateFlow(false)
    val isLoadingHistory: StateFlow<Boolean> = _isLoadingHistory.asStateFlow()

    private val _hasMoreHistory = MutableStateFlow(true)
    val hasMoreHistory: StateFlow<Boolean> = _hasMoreHistory.asStateFlow()

    private var currentPage = 1
    private var currentSessionId: String? = null

    init {
        // Listen for incoming messages from legacy WebSocket server (fallback)
        scope.launch {
            transportManager.incomingMessages.collect { protoMsg ->
                handleIncomingMessage(protoMsg)
            }
        }
    }

    /**
     * Load chat history from the server (Tauri DB).
     * @param refresh If true, clears existing messages and loads from page 1
     * @param sessionId Optional session ID to filter by
     */
    suspend fun loadChatHistory(refresh: Boolean = false, sessionId: String? = null): Result<Unit> {
        if (_isLoadingHistory.value) return Result.success(Unit)

        if (refresh) {
            currentPage = 1
            currentSessionId = sessionId
            _hasMoreHistory.value = true
        }

        if (!_hasMoreHistory.value) return Result.success(Unit)

        _isLoadingHistory.value = true

        return try {
            val result = apiClient.fetchChatHistory(
                page = currentPage,
                pageSize = DEFAULT_PAGE_SIZE,
                sessionId = currentSessionId
            )

            result.fold(
                onSuccess = { response ->
                    if (response.success && response.messages != null) {
                        val newMessages = response.messages.map { it.toDomainMessage() }

                        if (refresh) {
                            _messages.value = newMessages
                        } else {
                            // Prepend older messages (history loads older first)
                            _messages.value = newMessages + _messages.value
                        }

                        _hasMoreHistory.value = response.hasMore
                        if (response.hasMore) {
                            currentPage++
                        }

                        Log.d(TAG, "Loaded ${newMessages.size} messages, hasMore=${response.hasMore}")
                        Result.success(Unit)
                    } else {
                        Log.e(TAG, "Failed to load history: ${response.error}")
                        Result.failure(Exception(response.error ?: "Failed to load chat history"))
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load history", error)
                    Result.failure(error)
                }
            )
        } finally {
            _isLoadingHistory.value = false
        }
    }

    /**
     * Reload all chat history (refresh from page 1).
     */
    suspend fun reloadChatHistory(sessionId: String? = null): Result<Unit> {
        return loadChatHistory(refresh = true, sessionId = sessionId)
    }

    /**
     * Load more (older) messages for pagination.
     */
    suspend fun loadMoreHistory(): Result<Unit> {
        return loadChatHistory(refresh = false, sessionId = currentSessionId)
    }

    private fun ChatHistoryMessage.toDomainMessage(): Message {
        val sender = when (role.lowercase()) {
            "user" -> MessageSender.USER
            "assistant" -> MessageSender.AI
            "system" -> MessageSender.SYSTEM
            else -> MessageSender.SYSTEM
        }

        val type = when (role.lowercase()) {
            "assistant" -> MessageType.RESPONSE_TEXT
            "system" -> MessageType.ERROR
            else -> MessageType.TEXT
        }

        return Message(
            id = id,
            text = content,
            sender = sender,
            type = type,
            timestamp = timestamp,
            isVoice = isVoice,
            status = MessageStatus.SENT
        )
    }

    suspend fun sendUserMessage(text: String, isVoice: Boolean = false): Message {
        val userMessage = Message(
            text = text,
            sender = MessageSender.USER,
            type = MessageType.TEXT,
            isVoice = isVoice,
            status = MessageStatus.SENDING
        )

        // Add to local messages
        _messages.value = _messages.value + userMessage

        // Mark as sent immediately
        val sentMessage = userMessage.copy(status = MessageStatus.SENT)
        _messages.value = _messages.value.map {
            if (it.id == userMessage.id) sentMessage else it
        }

        _isAiResponding.value = true

        // When connected via FastAPI WebSocket, send via WS so broadcasting works
        if (transportManager.getActiveTransportType() == TransportType.WEBSOCKET_FASTAPI) {
            transportManager.sendMessage(text, isVoice)
        } else if (apiClient.isConfigured.value) {
            // REST API with streaming (Anthropic Claude) — no broadcasting
            sendViaRestApi(text)
        } else {
            // Fallback: send via legacy WebSocket transport
            transportManager.sendMessage(text, isVoice)
        }

        return sentMessage
    }

    private fun sendViaRestApi(text: String) {
        scope.launch {
            _streamingText.value = ""
            apiClient.chatStream(
                message = text,
                onChunk = { token ->
                    _streamingText.value += token
                },
                onDone = { fullText ->
                    val aiMessage = Message(
                        text = fullText,
                        sender = MessageSender.AI,
                        type = MessageType.RESPONSE_TEXT,
                    )
                    _messages.value = _messages.value + aiMessage
                    _streamingText.value = ""
                    _isAiResponding.value = false
                },
                onError = { errorMsg ->
                    Log.e(TAG, "Chat stream error: $errorMsg")
                    val errorMessage = Message(
                        text = errorMsg,
                        sender = MessageSender.SYSTEM,
                        type = MessageType.ERROR,
                    )
                    _messages.value = _messages.value + errorMessage
                    _streamingText.value = ""
                    _isAiResponding.value = false
                }
            )
        }
    }

    private fun handleIncomingMessage(protoMsg: ProtocolMessage) {
        when (protoMsg.type) {
            MessageTypes.AI_RESPONSE, MessageTypes.RESPONSE_TEXT -> {
                val aiMessage = Message(
                    id = protoMsg.messageId,
                    text = protoMsg.content.text ?: "",
                    sender = MessageSender.AI,
                    type = MessageType.RESPONSE_TEXT,
                    timestamp = protoMsg.timestamp
                )
                _messages.value = _messages.value + aiMessage
                _isAiResponding.value = false
                _streamingText.value = ""
            }

            MessageTypes.AI_STREAM_START -> {
                _isAiResponding.value = true
                _streamingText.value = ""
                _activeToolCalls.value = emptyList()
            }

            MessageTypes.AI_STREAM_CHUNK -> {
                val token = protoMsg.content.streamToken ?: protoMsg.content.text ?: ""
                _streamingText.value += token
            }

            MessageTypes.AI_STREAM_END -> {
                val fullText = _streamingText.value
                if (fullText.isNotBlank()) {
                    val aiMessage = Message(
                        id = protoMsg.messageId,
                        text = fullText,
                        sender = MessageSender.AI,
                        type = MessageType.RESPONSE_TEXT,
                        timestamp = protoMsg.timestamp
                    )
                    _messages.value = _messages.value + aiMessage
                }
                _streamingText.value = ""
                _activeToolCalls.value = emptyList()
                _isAiResponding.value = false
            }

            MessageTypes.ERROR -> {
                val errorMessage = Message(
                    id = protoMsg.messageId,
                    text = protoMsg.content.error ?: "Unknown error",
                    sender = MessageSender.SYSTEM,
                    type = MessageType.ERROR,
                    timestamp = protoMsg.timestamp
                )
                _messages.value = _messages.value + errorMessage
                _isAiResponding.value = false
            }

            MessageTypes.USER_MESSAGE_MIRROR -> {
                // Message from another client in the session (e.g., desktop Tauri app)
                val mirroredMessage = Message(
                    text = protoMsg.content.text ?: "",
                    sender = MessageSender.USER_REMOTE,
                    type = MessageType.TEXT,
                    timestamp = protoMsg.timestamp
                )
                _messages.value = _messages.value + mirroredMessage
            }

            MessageTypes.TOOL_CALL -> {
                val toolName = protoMsg.content.toolName ?: protoMsg.content.text ?: "unknown"
                val toolId = protoMsg.content.toolId ?: protoMsg.content.commandId ?: ""
                val newTool = ToolCallInfo(
                    toolName = toolName,
                    toolId = toolId
                )
                _activeToolCalls.value = _activeToolCalls.value + newTool
            }

            MessageTypes.TOOL_RESULT -> {
                val toolName = protoMsg.content.toolName ?: "unknown"
                val result = protoMsg.content.text ?: ""
                val success = protoMsg.content.success ?: true
                _activeToolCalls.value = _activeToolCalls.value.map { tc ->
                    if (tc.result == null && tc.toolName == toolName) {
                        tc.copy(result = result, success = success)
                    } else tc
                }
            }

            MessageTypes.SUB_TOOL_CALL -> {
                val parentToolId = protoMsg.content.parentToolId ?: ""
                val subToolName = protoMsg.content.toolName ?: "unknown"
                _activeToolCalls.value = _activeToolCalls.value.map { tc ->
                    if (tc.toolId == parentToolId) {
                        tc.copy(subTools = tc.subTools + SubToolInfo(toolName = subToolName))
                    } else tc
                }
            }

            MessageTypes.SUB_TOOL_RESULT -> {
                val parentToolId = protoMsg.content.parentToolId ?: ""
                val success = protoMsg.content.success ?: true
                _activeToolCalls.value = _activeToolCalls.value.map { tc ->
                    if (tc.toolId == parentToolId) {
                        val updatedSubs = tc.subTools.toMutableList()
                        val idx = updatedSubs.indexOfFirst { it.success == null }
                        if (idx >= 0) {
                            updatedSubs[idx] = updatedSubs[idx].copy(success = success)
                        }
                        tc.copy(subTools = updatedSubs)
                    } else tc
                }
            }

            MessageTypes.NOTIFICATION -> {
                val title = protoMsg.content.notificationTitle ?: "OmniPin"
                val message = protoMsg.content.text ?: ""
                val success = protoMsg.content.success ?: true
                notificationHelper.show(title, message, success)
            }

            MessageTypes.PONG -> {
                // Latency measurement — handled silently
            }
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
        _streamingText.value = ""
        _activeToolCalls.value = emptyList()
    }
}
