package com.locallink.pro.data.repository

import android.graphics.Bitmap
import com.locallink.pro.data.db.MessageDao
import com.locallink.pro.data.db.MessageEntity
import com.locallink.pro.data.db.SessionDao
import com.locallink.pro.data.db.SessionEntity
import com.locallink.pro.domain.model.Message
import com.locallink.pro.domain.model.MessageSender
import android.util.Log
import com.locallink.pro.data.local.EngineMode
import com.locallink.pro.data.local.SettingsPreferences
import com.locallink.pro.service.llm.AgentEvent
import com.locallink.pro.service.llm.AgentOrchestrator
import com.locallink.pro.service.llm.OpenRouterClient
import com.locallink.pro.service.llm.OpenRouterUnavailable
import com.locallink.pro.service.pilot.OmniAccessibilityService
import com.locallink.pro.service.pilot.OpenRouterPilotReasoner
import com.locallink.pro.service.pilot.PilotController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val agent: AgentOrchestrator,
    private val openRouter: OpenRouterClient,
    private val settings: SettingsPreferences,
) {
    private companion object { const val TAG = "ChatRepository" }

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _isAiResponding = MutableStateFlow(false)
    val isAiResponding: StateFlow<Boolean> = _isAiResponding.asStateFlow()

    // Latest finished assistant reply text — used by the hands-free voice loop to speak it.
    private val _lastAssistantReply = MutableStateFlow("")
    val lastAssistantReply: StateFlow<String> = _lastAssistantReply.asStateFlow()

    // Composio OAuth links to open in a Custom Tab (emitted when the agent connects an app).
    private val _authUrlToOpen = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val authUrlToOpen = _authUrlToOpen.asSharedFlow()

    fun observeSessions() = sessionDao.observeSessions()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMessages(): Flow<List<Message>> =
        _currentSessionId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else messageDao.observeMessages(id).map { list -> list.map { it.toDomain() } }
        }

    fun newSession() { _currentSessionId.value = null } // session row created lazily on first send

    fun loadSession(id: String) { _currentSessionId.value = id }

    suspend fun deleteSession(id: String) {
        sessionDao.getById(id)?.let { sessionDao.delete(it) }
        if (_currentSessionId.value == id) _currentSessionId.value = null
    }

    /** Persist user msg, stream AI reply, persist assistant msg on completion. */
    suspend fun send(text: String, image: Bitmap? = null, imageUri: String? = null, isVoice: Boolean = false) {
        val now = System.currentTimeMillis()
        val sessionId = ensureSession(text, now)

        messageDao.insert(
            MessageEntity(sessionId = sessionId, role = "user", text = text, imageUri = imageUri, isVoice = isVoice, timestamp = now)
        )
        touchSession(sessionId)
        generateReply(sessionId, text)
    }

    /**
     * DEBUG thin-slice trigger for Omni Pilot: perceive the screen via the AccessibilityService,
     * reason one action per step with the cloud vision model, and tap. Persists the user turn and
     * streams the pilot's [AgentEvent]s into the chat exactly like [send]. Requires the
     * OmniAccessibilityService to be enabled; MediaProjection (screenshot) is NOT wired here —
     * [PilotController] passes a null screenshot, so this runs element-only. (Device-side follow-up.)
     */
    suspend fun runPilot(task: String) {
        val now = System.currentTimeMillis()
        val sessionId = ensureSession(task, now)
        messageDao.insert(
            MessageEntity(sessionId = sessionId, role = "user", text = "/pilot $task", timestamp = now)
        )
        touchSession(sessionId)

        val service = OmniAccessibilityService.instance
        if (service == null) {
            messageDao.insert(MessageEntity(
                sessionId = sessionId, role = "system",
                text = "Error: Omni Pilot accessibility service is not enabled. " +
                    "Enable it in Settings → Accessibility → Omni, then retry.",
                timestamp = System.currentTimeMillis(),
            ))
            touchSession(sessionId)
            return
        }

        _isAiResponding.value = true
        _streamingText.value = ""

        // Ask the Activity to grant screen-capture consent for vision if we don't have it yet.
        // Best-effort: if declined or the Activity isn't foreground, the loop runs tree-only.
        if (!com.locallink.pro.service.pilot.PilotProjectionHolder.isReady) {
            com.locallink.pro.service.pilot.PilotProjectionRequest.request()
        }
        val reasoner = OpenRouterPilotReasoner(settings)
        val controller = PilotController(
            reasoner = reasoner,
            actuator = service.asActuator(),
            screenshot = { com.locallink.pro.service.pilot.PilotProjectionHolder.capture() },
        )
        // Run the loop in the SERVICE's scope, not here (viewModelScope), so it survives the app
        // going to the background when Pilot navigates into another app. Each event is persisted to
        // the DB from that scope; the UI "responding" flag is cleared when the terminal Final event
        // is persisted (see persistPilotEvent). runPilotFlow returns immediately.
        service.runPilotFlow(
            flow = controller.run(task),
            onEvent = { event -> persistPilotEvent(sessionId, event) },
            onComplete = { cause ->
                if (cause != null && cause !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "runPilot failed", cause)
                    messageDao.insert(MessageEntity(
                        sessionId = sessionId, role = "system",
                        text = "Error: ${cause.message ?: cause.javaClass.simpleName}",
                        timestamp = System.currentTimeMillis(),
                    ))
                }
                _streamingText.value = ""
                _isAiResponding.value = false
                touchSession(sessionId)
            },
        )
    }

    /** Planning-agent entry: plan → route todos to chat/composio/pilot → execute, with input pauses. */
    suspend fun runAgent(task: String) {
        val now = System.currentTimeMillis()
        val sessionId = ensureSession(task, now)
        messageDao.insert(MessageEntity(sessionId = sessionId, role = "user", text = task, timestamp = now))
        touchSession(sessionId)
        val service = com.locallink.pro.service.pilot.OmniAccessibilityService.instance
        if (service == null) {
            messageDao.insert(MessageEntity(sessionId = sessionId, role = "system",
                text = "Error: enable Omni accessibility service in Settings → Accessibility → Omni.",
                timestamp = System.currentTimeMillis()))
            touchSession(sessionId); return
        }
        _isAiResponding.value = true
        // Ask for screen-capture consent for pilot todos if not already granted (best-effort).
        if (!com.locallink.pro.service.pilot.PilotProjectionHolder.isReady) {
            com.locallink.pro.service.pilot.PilotProjectionRequest.request()
        }
        val runner = object : com.locallink.pro.service.pilot.ChannelRunner {
            override suspend fun chat(todo: String): String {
                val sb = StringBuilder()
                openRouter.run(emptyList(), todo) { _, _ -> true }.collect { e ->
                    if (e is AgentEvent.Final) sb.append(e.text)
                }
                return sb.toString()
            }
            override suspend fun composio(todo: String): String {
                // Persist the tool_call/tool_result rows so the user sees the work; the final text
                // is returned and surfaced by the executor as an AssistantSay.
                var out = ""
                openRouter.run(emptyList(), todo) { _, _ -> true }.collect { e ->
                    when (e) {
                        is AgentEvent.Final -> out = e.text
                        is AgentEvent.ToolCall, is AgentEvent.ToolResult -> persistPilotEvent(sessionId, e)
                        else -> {}
                    }
                }
                return out
            }
            override suspend fun pilot(todo: String): Boolean {
                var stuck = false
                val controller = com.locallink.pro.service.pilot.PilotController(
                    reasoner = com.locallink.pro.service.pilot.OpenRouterPilotReasoner(settings),
                    actuator = service.asActuator(),
                    screenshot = { com.locallink.pro.service.pilot.PilotProjectionHolder.capture() },
                )
                controller.run(todo).collect { e ->
                    when (e) {
                        is AgentEvent.Final -> if (e.text.startsWith("Stopped")) stuck = true
                        is AgentEvent.ToolCall, is AgentEvent.ToolResult -> persistPilotEvent(sessionId, e)
                        else -> {}
                    }
                }
                return !stuck
            }
            override suspend fun requestInput(question: String, reason: String?): String? =
                service.requestInput(question, reason)
        }
        val executor = com.locallink.pro.service.pilot.PlanExecutor(
            com.locallink.pro.service.pilot.OpenRouterPlanner(settings), runner,
        )
        service.runPilotFlow(
            flow = executor.run(task),
            onEvent = { persistPilotEvent(sessionId, it) },
            onComplete = { _ -> _isAiResponding.value = false; touchSession(sessionId) },
        )
    }

    /** Persist one Pilot [AgentEvent] to the chat DB (runs in the service scope). */
    private suspend fun persistPilotEvent(sessionId: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.Token -> _streamingText.value = event.text
            is AgentEvent.ToolCall -> messageDao.insert(MessageEntity(
                sessionId = sessionId, role = "tool_call",
                text = "${event.name}(${event.argsJson})", timestamp = System.currentTimeMillis(),
            ))
            is AgentEvent.ToolResult -> messageDao.insert(MessageEntity(
                sessionId = sessionId, role = "tool_result",
                text = "${event.name} → ${event.result}", timestamp = System.currentTimeMillis(),
            ))
            is AgentEvent.OpenAuthUrl -> { /* pilot has no auth flow */ }
            is AgentEvent.Final -> {
                messageDao.insert(MessageEntity(
                    sessionId = sessionId, role = "assistant",
                    text = event.text, timestamp = System.currentTimeMillis(),
                ))
                _streamingText.value = ""
                _isAiResponding.value = false
            }
            is AgentEvent.Plan -> messageDao.insert(MessageEntity(
                sessionId = sessionId, role = "system",
                text = "🗒 Plan:\n" + event.todos.mapIndexed { i, t ->
                    "${i + 1}. ${t.text} [${t.channel.name.lowercase()}]" +
                        if (t.needsInput) " (needs input)" else ""
                }.joinToString("\n"),
                timestamp = System.currentTimeMillis(),
            ))
            is AgentEvent.TodoStatus -> if (event.done) messageDao.insert(MessageEntity(
                sessionId = sessionId, role = "system",
                text = "✓ ${event.text}", timestamp = System.currentTimeMillis(),
            ))
            is AgentEvent.InputRequested -> messageDao.insert(MessageEntity(
                sessionId = sessionId, role = "system",
                text = "⌨ Input requested: ${event.question}", timestamp = System.currentTimeMillis(),
            ))
            is AgentEvent.AssistantSay -> if (event.text.isNotBlank()) messageDao.insert(MessageEntity(
                sessionId = sessionId, role = "assistant",
                text = event.text, timestamp = System.currentTimeMillis(),
            ))
        }
        touchSession(sessionId)
    }

    /**
     * Re-run the assistant on the most recent user turn: drop the trailing
     * assistant/tool/system rows, then generate again. No-op if there's no user turn.
     */
    suspend fun regenerateLast() {
        val sessionId = _currentSessionId.value ?: return
        val lastUser = messageDao.getMessages(sessionId).lastOrNull { it.role == "user" } ?: return
        messageDao.deleteAfterLastUser(sessionId)
        generateReply(sessionId, lastUser.text)
    }

    /** Shared generation core: builds history, streams the reply, persists tool/assistant rows. */
    private suspend fun generateReply(sessionId: String, userText: String) {
        // History excluding the latest user turn (passed separately as prompt).
        val history = messageDao.getMessages(sessionId)
            .filter { it.role == "user" || it.role == "assistant" }
            .map { it.role to it.text }
            .dropLast(1)

        _isAiResponding.value = true
        _streamingText.value = ""
        try {
            // Cloud-only: this app uses OpenRouter for chat + Composio cloud tools. The on-device
            // models (Qwen / FunctionGemma) and the 23 local device tools are intentionally
            // disabled — their code remains but is not routed to. (User decision 2026-06-05.)
            if (!openRouter.hasKey()) {
                messageDao.insert(MessageEntity(
                    sessionId = sessionId, role = "system",
                    text = "Error: No OpenRouter API key set. Add one in Settings → AI Model to chat.",
                    timestamp = System.currentTimeMillis(),
                ))
            } else {
                try {
                    runEngine(sessionId, openRouter.run(history, userText) { _, _ -> true })
                } catch (e: OpenRouterUnavailable) {
                    Log.w(TAG, "OpenRouter unavailable (${e.reason})")
                    messageDao.insert(MessageEntity(
                        sessionId = sessionId, role = "system",
                        text = "Error: Cloud model ${e.reason}. Try again, or pick a different model in Settings.",
                        timestamp = System.currentTimeMillis(),
                    ))
                }
            }
        } catch (e: Exception) {
            messageDao.insert(
                MessageEntity(sessionId = sessionId, role = "system", text = "Error: ${e.message}", timestamp = System.currentTimeMillis())
            )
        } finally {
            _streamingText.value = ""
            _isAiResponding.value = false
            touchSession(sessionId)
        }
    }

    /** Collect one engine's [AgentEvent] stream into the DB. Persists tool + assistant rows. */
    private suspend fun runEngine(
        sessionId: String,
        events: kotlinx.coroutines.flow.Flow<AgentEvent>,
    ) {
        events.collect { event ->
            when (event) {
                is AgentEvent.Token -> _streamingText.value = event.text
                is AgentEvent.ToolCall -> messageDao.insert(
                    MessageEntity(
                        sessionId = sessionId, role = "tool_call",
                        text = "${event.name}(${event.argsJson})",
                        timestamp = System.currentTimeMillis(),
                    )
                )
                is AgentEvent.ToolResult -> messageDao.insert(
                    MessageEntity(
                        sessionId = sessionId, role = "tool_result",
                        text = "${event.name} → ${event.result}",
                        timestamp = System.currentTimeMillis(),
                    )
                )
                is AgentEvent.OpenAuthUrl -> {
                    _authUrlToOpen.tryEmit(event.url)
                    messageDao.insert(
                        MessageEntity(
                            sessionId = sessionId, role = "system",
                            text = "↳ Opening sign-in to connect your app…",
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                }
                is AgentEvent.Final -> {
                    messageDao.insert(
                        MessageEntity(
                            sessionId = sessionId, role = "assistant",
                            text = event.text, timestamp = System.currentTimeMillis(),
                        )
                    )
                    _lastAssistantReply.value = event.text
                }
                // Planning-agent-only events; the plain chat/composio engine never emits these.
                is AgentEvent.Plan, is AgentEvent.TodoStatus,
                is AgentEvent.InputRequested, is AgentEvent.AssistantSay -> {}
            }
        }
    }

    private suspend fun ensureSession(firstText: String, now: Long): String {
        _currentSessionId.value?.let { return it }
        val id = UUID.randomUUID().toString()
        sessionDao.upsert(
            SessionEntity(id = id, title = firstText.take(40).ifBlank { "New chat" }, createdAt = now, updatedAt = now)
        )
        _currentSessionId.value = id
        return id
    }

    private suspend fun touchSession(id: String) {
        sessionDao.getById(id)?.let { sessionDao.upsert(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    suspend fun clearAll() {
        sessionDao.deleteAll()
        _currentSessionId.value = null
    }

    private fun MessageEntity.toDomain() = Message(
        id = id,
        text = when (role) {
            "tool_call" -> "🔧 $text"
            "tool_result" -> "↳ $text"
            else -> text
        },
        sender = when (role) {
            "user" -> MessageSender.USER
            "assistant" -> MessageSender.AI
            else -> MessageSender.SYSTEM
        },
        timestamp = timestamp,
        isVoice = isVoice,
        imageUri = imageUri,
    )
}
