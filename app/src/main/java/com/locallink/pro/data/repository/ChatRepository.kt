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
import com.locallink.pro.service.llm.OpenRouterClient
import com.locallink.pro.service.llm.OpenRouterUnavailable
import com.locallink.pro.service.pilot.MemoryPilot
import com.locallink.pro.service.pilot.OmniAccessibilityService
import com.locallink.pro.service.pilot.OpenRouterPilotReasoner
import com.locallink.pro.service.pilot.PilotActuator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val openRouter: OpenRouterClient,
    private val settings: SettingsPreferences,
    private val experiences: ExperienceStore,
    private val memory: MemoryStore,
    private val deviceTools: com.locallink.pro.service.llm.tools.DeviceToolFastPath,
    private val teaching: com.locallink.pro.service.pilot.GuidedTeachingSession,
    private val notifier: com.locallink.pro.service.pilot.AutomationNotifier,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) {
    private companion object { const val TAG = "ChatRepository" }

    // ── One agent at a time ──────────────────────────────────────────────
    // Agent runs drive the ONE physical screen; two concurrent runs yank apps out from
    // under each other and both spiral into replans. New runs queue behind the active one.
    private val agentMutex = Mutex()
    @Volatile private var activeTask: String? = null
    /** Runs parked behind the active one — surfaced in the shade so a queue isn't invisible. */
    private val waiting = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Wrap an agent flow so executions are strictly serialized on the shared screen. The STOP
     * pill (and its cancel-flag reset) belongs to the run that is EXECUTING — showing it at
     * submit time would clear a STOP aimed at the active run.
     */
    private fun serialized(task: String, inner: Flow<AgentEvent>): Flow<AgentEvent> = flow {
        waiting.incrementAndGet()
        var acquired = false
        try {
            agentMutex.withLock {
                acquired = true
                waiting.decrementAndGet()
                activeTask = task
                _isAiResponding.value = true
                val svc = com.locallink.pro.service.pilot.OmniAccessibilityService.instance
                svc?.showStop()
                // Every entry point — chat, voice, the routine library, WorkManager schedules and
                // notification triggers — funnels through here, so this is the one place that can
                // say what the phone is doing without each of them growing its own copy.
                notifier.start(task, waiting.get())
                try {
                    emitAll(inner.onEach { notifier.onEvent(it) })
                } finally {
                    notifier.stop()
                    svc?.hideStop()
                    activeTask = null
                }
            }
        } finally {
            // A queued run can be abandoned before it ever gets the lock (collector cancelled
            // while waiting). Without this the count only ever climbs and the shade starts
            // inventing a queue that isn't there.
            if (!acquired) waiting.decrementAndGet()
        }
    }

    /** If a run is active, tell the user this one is queued (persisted as a system note). */
    private suspend fun noteIfQueued(sessionId: String, task: String) {
        val current = activeTask
        if (agentMutex.isLocked && current != null) {
            messageDao.insert(MessageEntity(
                sessionId = sessionId, role = "system",
                text = "⏳ Queued — I'll start “$task” after finishing “$current”.",
                timestamp = System.currentTimeMillis(),
            ))
        }
    }

    /** Pilot with experience memory: replay learned routines first, reason only when needed. */
    private fun memoryPilot(
        actuator: PilotActuator,
        askUser: (suspend (String) -> String?)? = null,
        /** Overrides where a successful run's actions go; defaults to the routine library. */
        onTrace: (suspend (String, List<com.locallink.pro.service.pilot.TraceStep>) -> Unit)? = null,
    ): MemoryPilot = MemoryPilot(
        reasoner = OpenRouterPilotReasoner(settings),
        actuator = actuator,
        screenshot = { com.locallink.pro.service.pilot.PilotProjectionHolder.capture() },
        find = { task -> experiences.find(task) },
        save = { task, steps -> (onTrace ?: { t, s -> experiences.save(t, s) })(task, steps) },
        bump = { id -> experiences.bump(id) },
        askUser = askUser,
        summarise = { task -> reportOutcome(task, actuator) },
        reflector = com.locallink.pro.service.pilot.OpenRouterPilotReflector(settings),
    )

    /**
     * Say what an automation actually achieved, reading it off the screen it finished on.
     *
     * "Done." tells the user nothing — if they asked for a price, the price is the answer, and
     * it's sitting right there on the final screen. Returns null on any failure so the caller
     * falls back to its own wording rather than the run appearing to fail.
     */
    private suspend fun reportOutcome(task: String, actuator: PilotActuator): String? {
        val screen = runCatching { screenSummaryOf(actuator)() }.getOrDefault("")
        if (screen.isBlank()) return null
        val reply = runCatching {
            openRouter.plainChat(
                "A phone automation just finished. Report to the user what was achieved, in one " +
                    "or two sentences, in plain past tense.\n" +
                    "Quote the CONCRETE values that answer the request — prices, names, times, " +
                    "counts — exactly as they appear. Never reply with just \"Done\".\n" +
                    "If the screen doesn't show what was asked for, say plainly what it does show.\n\n" +
                    "Task: $task\n" +
                    "Final screen: $screen",
            )
        }.getOrDefault("")
        return reply.takeIf { it.isNotBlank() }
    }

    /** A short "what's on screen" line for grounding replans. */
    private fun screenSummaryOf(actuator: PilotActuator): suspend () -> String = {
        runCatching {
            actuator.perceive()
                .mapNotNull { e -> (e.text ?: e.desc)?.takeIf { it.isNotBlank() } }
                .distinct().take(18).joinToString(", ")
        }.getOrDefault("")
    }

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
        val sessionId = ensureSession(text.ifBlank { "Photo" }, now)

        messageDao.insert(
            MessageEntity(sessionId = sessionId, role = "user", text = text, imageUri = imageUri, isVoice = isVoice, timestamp = now)
        )
        touchSession(sessionId)

        // Vision turn: send the photo to the multimodal model ("what am I looking at").
        if (image != null) {
            _isAiResponding.value = true
            try {
                val prompt = text.ifBlank { "What am I looking at? Describe what's in this photo and anything notable about it." }
                val jpeg = java.io.ByteArrayOutputStream().use { bos ->
                    image.compress(Bitmap.CompressFormat.JPEG, 85, bos); bos.toByteArray()
                }
                val history = messageDao.getMessages(sessionId)
                    .filter { it.role == "user" || it.role == "assistant" }
                    .dropLast(1)
                    .map { it.role to it.text }
                val reply = openRouter.visionChat(prompt, jpeg, history)
                if (reply.isNotBlank()) {
                    messageDao.insert(MessageEntity(
                        sessionId = sessionId, role = "assistant", text = reply,
                        timestamp = System.currentTimeMillis(),
                    ))
                    _lastAssistantReply.value = reply
                } else {
                    messageDao.insert(MessageEntity(
                        sessionId = sessionId, role = "system",
                        text = "Error: couldn't analyze the photo — the selected model may not support images. " +
                            "Pick a vision-capable model in Settings → AI model.",
                        timestamp = System.currentTimeMillis(),
                    ))
                }
            } finally {
                _isAiResponding.value = false
                touchSession(sessionId)
            }
            return
        }
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
        // Run the loop in the SERVICE's scope, not here (viewModelScope), so it survives the app
        // going to the background when Pilot navigates into another app. Each event is persisted to
        // the DB from that scope; the UI "responding" flag is cleared when the terminal Final event
        // is persisted (see persistPilotEvent). runPilotFlow returns immediately.
        // Mid-run questions pause the loop with the floater; answers persist as user messages.
        val liveAsk: suspend (String) -> String? = { q ->
            val a = service.requestInput(q, null)
            if (!a.isNullOrBlank()) {
                messageDao.insert(MessageEntity(
                    sessionId = sessionId, role = "user", text = a,
                    timestamp = System.currentTimeMillis(),
                ))
                touchSession(sessionId)
            }
            a
        }
        noteIfQueued(sessionId, task)
        service.runPilotFlow(
            flow = serialized(task, memoryPilot(service.asActuator(), askUser = liveAsk).run(task)),
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

    /**
     * Chat-only turn (the home "New chat"/"Voice chat" modes): NO planner, NO device control /
     * accessibility service — a conversational reply that can ALSO use Composio cloud tools
     * (Gmail, Slack, Calendar, …) via the OpenRouter tool-calling loop. Fast and a11y-independent.
     *
     * This is the ONLY difference from the "Automate my phone" mode ([runAgent]): here tools are
     * cloud SaaS actions the model drives itself; there they are on-screen taps the pilot performs.
     */
    suspend fun runChatOnly(task: String) = runChatWithTools(task, isVoice = false)

    /**
     * Conversational reply + Composio cloud tools, shared by text ("New chat") and voice
     * ("Voice chat") modes. Streams tokens, persists tool_call/tool_result rows, and handles the
     * OAuth-connect flow. Never touches the accessibility service.
     *
     * @param isVoice when true (hands-free voice loop), an app-connect (OAuth) link is NOT opened
     *   in a browser — the reply guides the user to connect it in Settings instead, so the spoken
     *   loop isn't interrupted. In text mode the link opens in a Custom Tab as usual.
     */
    suspend fun runChatWithTools(task: String, isVoice: Boolean) {
        val now = System.currentTimeMillis()
        val sessionId = ensureSession(task, now)
        messageDao.insert(MessageEntity(sessionId = sessionId, role = "user", text = task, isVoice = isVoice, timestamp = now))
        touchSession(sessionId)
        // Mine durable personal facts in the background (never blocks the turn).
        memory.maybeExtract(task) { p -> openRouter.plainChat(p) }

        if (!openRouter.hasKey()) {
            messageDao.insert(MessageEntity(
                sessionId = sessionId, role = "system",
                text = "Error: No OpenRouter API key set. Add one in Settings → AI Model to chat.",
                timestamp = System.currentTimeMillis(),
            ))
            touchSession(sessionId)
            return
        }

        _isAiResponding.value = true
        _streamingText.value = ""
        try {
            val history = messageDao.getMessages(sessionId)
                .filter { it.role == "user" || it.role == "assistant" }
                .dropLast(1) // the task itself goes as the final user message
                .map { it.role to it.text }

            // Tracks whether the run hit an app that needs connecting but couldn't open the link
            // (voice mode) — so we can append a spoken-friendly nudge to the final reply.
            var pendingConnect = false
            try {
                openRouter.run(history, task) { _, _ -> true }.collect { event ->
                    when (event) {
                        is AgentEvent.Token -> _streamingText.value += event.text
                        is AgentEvent.ToolCall -> messageDao.insert(MessageEntity(
                            sessionId = sessionId, role = "tool_call",
                            text = "${event.name}(${event.argsJson})", timestamp = System.currentTimeMillis(),
                        ))
                        is AgentEvent.ToolResult -> messageDao.insert(MessageEntity(
                            sessionId = sessionId, role = "tool_result",
                            text = "${event.name} → ${event.result}", timestamp = System.currentTimeMillis(),
                        ))
                        is AgentEvent.OpenAuthUrl -> {
                            if (isVoice) {
                                // Hands-free: don't yank the user into a browser mid-conversation.
                                pendingConnect = true
                                messageDao.insert(MessageEntity(
                                    sessionId = sessionId, role = "system",
                                    text = "↳ That app isn't connected yet — connect it in Settings → Connect apps.",
                                    timestamp = System.currentTimeMillis(),
                                ))
                            } else {
                                _authUrlToOpen.tryEmit(event.url)
                                messageDao.insert(MessageEntity(
                                    sessionId = sessionId, role = "system",
                                    text = "↳ Opening sign-in to connect your app…",
                                    timestamp = System.currentTimeMillis(),
                                ))
                            }
                        }
                        is AgentEvent.Final -> {
                            val reply = if (isVoice && pendingConnect && event.text.isNotBlank())
                                event.text + " You'll need to connect that app in the Omni app's Settings first."
                            else event.text
                            if (reply.isNotBlank()) messageDao.insert(MessageEntity(
                                sessionId = sessionId, role = "assistant",
                                text = reply, timestamp = System.currentTimeMillis(),
                            ))
                            _lastAssistantReply.value = reply
                        }
                        // Planning-agent-only events; the tool-calling chat loop never emits these.
                        is AgentEvent.Plan, is AgentEvent.TodoStatus,
                        is AgentEvent.InputRequested, is AgentEvent.AssistantSay -> {}
                    }
                }
            } catch (e: OpenRouterUnavailable) {
                Log.w(TAG, "OpenRouter unavailable (${e.reason})")
                messageDao.insert(MessageEntity(
                    sessionId = sessionId, role = "system",
                    text = "Error: Cloud model ${e.reason}. Try again, or pick a different model in Settings.",
                    timestamp = System.currentTimeMillis(),
                ))
            }
        } finally {
            _streamingText.value = ""
            _isAiResponding.value = false
            touchSession(sessionId)
        }
    }

    /**
     * Does this utterance need the phone driven, or is it just something to answer?
     *
     * Hands-free speech arrives with no mode attached, so the voice loop used to send every
     * utterance to the agent — "what's 20% of 340" would foreground the app and start a
     * screenshot→reason→tap loop to answer a question that needed one sentence back.
     *
     * Defaults to true (action) whenever the model can't be reached, so a classification
     * failure degrades to the previous behaviour rather than silently dropping a real command.
     */
    suspend fun isPhoneAction(text: String): Boolean {
        val verdict = runCatching {
            openRouter.plainChat(
                "Classify this request. Answer with exactly one word.\n" +
                    "ACTION — it asks you to operate the phone or its apps (open, send, play, " +
                    "set, call, post, reply, search in an app, change a setting).\n" +
                    "CHAT — it is a question, a fact lookup, or conversation you can answer in words.\n\n" +
                    "Request: $text",
            )
        }.getOrDefault("")
        // Substring, not equality: models like to wrap one-word answers in punctuation or quotes.
        if (verdict.contains("CHAT", ignoreCase = true)) return false
        if (verdict.contains("ACTION", ignoreCase = true)) return true
        Log.w(TAG, "unclear intent verdict '${verdict.take(40)}' — treating as an action")
        return true
    }

    /**
     * Run ONE instruction of a routine being taught, and keep the actions it produced.
     *
     * Teaching runs the real pilot rather than watching the user, so every saved action is one
     * Omni can perform itself — the reason a demonstration could capture a launcher tap that
     * could never replay. The user sees each step land before adding the next.
     *
     * @return a human report of what that step did, for display beside the instruction.
     */
    suspend fun teachStep(instruction: String): TeachStepResult {
        val service = com.locallink.pro.service.pilot.OmniAccessibilityService.instance
            ?: return TeachStepResult.Blocked(
                "Automate is switched off, so Omni can't carry out steps. Turn on the OmniPro " +
                    "accessibility service and try again.",
            )

        if (!com.locallink.pro.service.pilot.PilotProjectionHolder.isReady) {
            com.locallink.pro.service.pilot.PilotProjectionRequest.request()
        }

        val actions = ArrayList<com.locallink.pro.service.pilot.TraceStep>()
        var report: String? = null
        var stopped = false
        teaching.markRunning(true)
        try {
            memoryPilot(
                service.asActuator(),
                askUser = { q -> service.requestInput(q, null) },
                onTrace = { _, steps -> actions.addAll(steps) },
            ).run(instruction).collect { e ->
                when (e) {
                    is AgentEvent.Final ->
                        if (e.text.startsWith("Stopped")) stopped = true else report = e.text
                    else -> {}
                }
            }
        } finally {
            teaching.markRunning(false)
        }

        val text = report
            ?: reportOutcome(instruction, service.asActuator())
            ?: if (stopped) "Couldn't finish that step." else "Finished: $instruction"
        // Recorded whether or not it succeeded: a step that didn't work is information the user
        // needs in the list, not something to hide behind a toast.
        teaching.addStep(instruction, text, !stopped, actions)
        return TeachStepResult.Ran(text, !stopped)
    }

    /** Store everything taught so far under the session's name, then end the session. */
    suspend fun saveTaughtRoutine(): Boolean {
        val name = teaching.name.value
        val trace = teaching.trace.value
        if (name.isBlank() || trace.isEmpty()) return false
        // Saved through the same store the pilot writes to, so a taught routine is replayed,
        // listed and scheduled by the existing machinery.
        experiences.save(name, trace)
        teaching.clear()
        return true
    }

    /** Planning-agent entry: plan → route todos to chat/composio/pilot → execute, with input pauses. */
    suspend fun runAgent(task: String, onOutcome: ((Boolean, String) -> Unit)? = null) {
        val now = System.currentTimeMillis()
        val sessionId = ensureSession(task, now)
        messageDao.insert(MessageEntity(sessionId = sessionId, role = "user", text = task, timestamp = now))
        touchSession(sessionId)
        memory.maybeExtract(task) { p -> openRouter.plainChat(p) }

        // FAST PATH — "call/dial <number|remembered contact>": open the dialer prefilled
        // (ACTION_DIAL — the user presses the actual call button; we never place calls
        // unattended). Memory facts resolve "call my wife" without asking.
        val dialTarget = Regex("^\\s*(?:call|dial|phone)\\s+(.{1,60})$", RegexOption.IGNORE_CASE)
            .find(task)?.groupValues?.get(1)?.trim()?.trimEnd('.', '!', '?')
        if (dialTarget != null) {
            val number = dialTarget.takeIf { it.matches(Regex("^[+0-9][0-9 ()\\-]{4,}$")) }
                ?: memory.findPhone(dialTarget)
            if (number != null) {
                val opened = runCatching {
                    appContext.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_DIAL,
                            android.net.Uri.parse("tel:${android.net.Uri.encode(number)}"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.isSuccess
                val reply = if (opened)
                    "Dialer is ready for $dialTarget ($number) — press the call button to connect."
                else "I couldn't open the dialer."
                messageDao.insert(MessageEntity(
                    sessionId = sessionId, role = "assistant", text = reply,
                    timestamp = System.currentTimeMillis(),
                ))
                _lastAssistantReply.value = reply
                touchSession(sessionId)
                onOutcome?.invoke(opened, reply)
                return
            }
        }
        // FAST PATH — one on-device function does the whole job (set an alarm, start a timer,
        // launch an app, toggle the torch …). One round trip and an intent, instead of a planner
        // pass plus a screenshot→reason→tap loop. Deliberately BEFORE the accessibility check:
        // these are plain intents, so they work even when the a11y service is off.
        deviceTools.tryHandle(task)?.let { outcome ->
            Log.i(TAG, "device tool '${outcome.toolName}' handled the task")
            messageDao.insert(MessageEntity(
                sessionId = sessionId, role = "assistant", text = outcome.reply,
                timestamp = System.currentTimeMillis(),
            ))
            _lastAssistantReply.value = outcome.reply
            touchSession(sessionId)
            onOutcome?.invoke(true, outcome.reply)
            return
        }

        val service = com.locallink.pro.service.pilot.OmniAccessibilityService.instance
        if (service == null) {
            // No device control available — still answer in plain chat instead of hard-failing.
            // Only phone-control tasks genuinely need the accessibility service. Tell the model
            // it can't act so it never pretends to ("Opening Settings app…").
            _isAiResponding.value = true
            try {
                val reply = openRouter.plainChat(
                    "$task\n\n(System note: you can NOT control the phone right now — the " +
                        "accessibility service is off. Answer in text; if the request needs " +
                        "device control, say so briefly and point to Settings → Accessibility → OmniPro.)",
                )
                if (reply.isNotBlank()) {
                    messageDao.insert(MessageEntity(sessionId = sessionId, role = "assistant",
                        text = reply, timestamp = System.currentTimeMillis()))
                    _lastAssistantReply.value = reply
                } else {
                    messageDao.insert(MessageEntity(sessionId = sessionId, role = "system",
                        text = "Error: no reply. For phone-control tasks, enable the Omni accessibility " +
                            "service in Settings → Accessibility → OmniPro.",
                        timestamp = System.currentTimeMillis()))
                }
            } finally {
                _isAiResponding.value = false
                touchSession(sessionId)
                onOutcome?.invoke(false, "accessibility service off — answered in chat only")
            }
            return
        }
        _isAiResponding.value = true
        // Ask for screen-capture consent for pilot todos if not already granted (best-effort).
        if (!com.locallink.pro.service.pilot.PilotProjectionHolder.isReady) {
            com.locallink.pro.service.pilot.PilotProjectionRequest.request()
        }

        // FAST PATH: a learned routine (exact or template) matches the task → skip the
        // planner entirely and replay it. This is what makes repeat tasks near-instant.
        val learned = runCatching { experiences.find(task) }.getOrNull()
        if (learned != null && learned.steps.isNotEmpty()) {
            val fastAsk: suspend (String) -> String? = { q ->
                val a = service.requestInput(q, null)
                if (!a.isNullOrBlank()) {
                    messageDao.insert(MessageEntity(
                        sessionId = sessionId, role = "user", text = a,
                        timestamp = System.currentTimeMillis(),
                    ))
                    touchSession(sessionId)
                }
                a
            }
            noteIfQueued(sessionId, task)
            var replayStopped = false
            service.runPilotFlow(
                flow = serialized(task, memoryPilot(service.asActuator(), askUser = fastAsk).run(task)),
                onEvent = { e ->
                    if (e is AgentEvent.Final && e.text.startsWith("Stopped")) replayStopped = true
                    persistPilotEvent(sessionId, e)
                },
                onComplete = { cause ->
                    _isAiResponding.value = false; touchSession(sessionId)
                    onOutcome?.invoke(cause == null && !replayStopped,
                        cause?.message ?: if (replayStopped) "agent stopped mid-task" else "completed")
                },
            )
            return
        }

        val runner = object : com.locallink.pro.service.pilot.ChannelRunner {
            override suspend fun chat(todo: String): String =
                openRouter.plainChat(todo)  // tool-free plain reply (no Composio machinery)

            // Composio channel disabled — never routed here (planner emits only chat/pilot).
            override suspend fun composio(todo: String): String = openRouter.plainChat(todo)
            override suspend fun pilot(todo: String): String? {
                var report: String? = null
                var stuck = false
                memoryPilot(
                    service.asActuator(),
                    askUser = { q -> requestInput(q, null) },
                ).run(todo).collect { e ->
                    when (e) {
                        is AgentEvent.Final ->
                            if (e.text.startsWith("Stopped")) stuck = true else report = e.text
                        is AgentEvent.ToolCall, is AgentEvent.ToolResult -> persistPilotEvent(sessionId, e)
                        else -> {}
                    }
                }
                if (stuck) return null
                // A bare "Done." is the one answer that's never useful — read the screen the
                // step finished on and say what actually happened instead.
                return report
                    ?: reportOutcome(todo, service.asActuator())
                    ?: "Finished: $todo"
            }
            override suspend fun requestInput(question: String, reason: String?): String? {
                val answer = service.requestInput(question, reason)
                // Persist the user's floater answer so the conversation (and any replan)
                // actually contains what they said.
                if (!answer.isNullOrBlank()) {
                    messageDao.insert(MessageEntity(
                        sessionId = sessionId, role = "user",
                        text = answer, timestamp = System.currentTimeMillis(),
                    ))
                    touchSession(sessionId)
                }
                return answer
            }
        }
        val executor = com.locallink.pro.service.pilot.PlanExecutor(
            com.locallink.pro.service.pilot.OpenRouterPlanner(settings, { memory.promptBlock() }), runner,
            cancelled = { service.cancelFlag.get() },
            screenSummary = screenSummaryOf(service.asActuator()),
            // A near-miss on the learned-routine lookup still has something to teach the planner.
            priorRoutines = { t -> experiences.priorRoutinesBlock(t) },
        )
        noteIfQueued(sessionId, task)
        var planStopped = false
        service.runPilotFlow(
            flow = serialized(task, executor.run(task)),
            onEvent = { e ->
                if (e is AgentEvent.Final && e.text.startsWith("Stopped")) planStopped = true
                persistPilotEvent(sessionId, e)
            },
            onComplete = { cause ->
                _isAiResponding.value = false; touchSession(sessionId)
                onOutcome?.invoke(cause == null && !planStopped,
                    cause?.message ?: if (planStopped) "agent stopped mid-task" else "completed")
            },
        )
    }

    /** Persist one Pilot [AgentEvent] to the chat DB (runs in the service scope). */
    private suspend fun persistPilotEvent(sessionId: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.Token -> _streamingText.value += event.text
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
                if (event.text.isNotBlank()) messageDao.insert(MessageEntity(
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
        // Token events are deltas that accumulate here; a multi-todo run calls this once per
        // engine, so clear the buffer or the previous todo's reply prefixes this one.
        _streamingText.value = ""
        events.collect { event ->
            when (event) {
                is AgentEvent.Token -> _streamingText.value += event.text
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

/**
 * Outcome of one taught step.
 *
 * [Blocked] is deliberately distinct from a step that ran and failed: the first is a
 * precondition the user must fix (Automate switched off), the second belongs in the step list
 * as a red entry. Collapsing them is how "tap Send, nothing happens at all" came about.
 */
sealed interface TeachStepResult {
    data class Ran(val report: String, val succeeded: Boolean) : TeachStepResult
    data class Blocked(val reason: String) : TeachStepResult
}
