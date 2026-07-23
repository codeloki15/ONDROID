package com.locallink.pro.service.pilot

import com.locallink.pro.service.llm.AgentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.UUID

fun interface PilotReasoner {
    /** @return (toolName, argsJson) for the next action. */
    suspend fun nextAction(
        task: String,
        elements: List<PilotElement>,
        screenshot: ByteArray?,
        history: List<String>,
    ): Pair<String, String>
}

/** Device actions the controller drives. Backed by the AccessibilityService at runtime. */
interface PilotActuator {
    fun perceive(): List<PilotElement>
    suspend fun tap(e: PilotElement): Boolean
    suspend fun longPress(e: PilotElement): Boolean
    suspend fun doubleTap(e: PilotElement): Boolean
    suspend fun drag(from: PilotElement, to: PilotElement): Boolean
    suspend fun type(e: PilotElement, text: String): Boolean
    fun clear(e: PilotElement): Boolean
    suspend fun swipe(direction: String): Boolean
    fun launchApp(app: String): Boolean
    fun back(): Boolean
    fun home(): Boolean
    fun recents(): Boolean
    fun notifications(): Boolean
    fun quickSettings(): Boolean
    fun cancelled(): Boolean
}

class PilotController(
    private val reasoner: PilotReasoner,
    private val actuator: PilotActuator,
    /** Optional per-step screenshot for vision. Returns null → tree-only for that step. */
    private val screenshot: suspend () -> ByteArray? = { null },
    private val maxSteps: Int = 60,
    /** Called with the successful action trace when the run ends in Done — experience learning. */
    private val onTrace: (suspend (List<TraceStep>) -> Unit)? = null,
    /**
     * Live user-input channel: when the model asks a question mid-run, this pauses the loop
     * (input floater) and resumes with the answer. Null → an ask ends the run (legacy).
     */
    private val askUser: (suspend (String) -> String?)? = null,
    /** Notes injected at the start of history — e.g. a partially replayed routine's steps. */
    private val primedHistory: List<String> = emptyList(),
) {
    fun run(task: String): Flow<AgentEvent> = flow {
        val history = ArrayList<String>(primedHistory)
        val trace = ArrayList<TraceStep>()
        var traceBroken = false  // an untraceable step ran → replaying the rest would diverge
        var lastActionSig: String? = null
        var stuckCount = 0
        var step = 0
        // Mechanical repeat-blocker (pi-style guard hook): same action on the same screen
        // twice is never executed again — the model is told to choose differently.
        val attempted = HashMap<String, Int>()
        while (step < maxSteps) {
            step++
            if (actuator.cancelled()) { emit(AgentEvent.Final("Stopped.")); return@flow }
            val elements = actuator.perceive()
            val screenSig = elements.joinToString("|") { "${it.text}:${it.bounds.joinToString(",")}" }
            val shot = screenshot()
            // Long runs: window the history so the prompt stays bounded — the tail carries
            // the actionable context, the prefix collapses to a count.
            val recent = if (history.size <= HISTORY_WINDOW) history
            else buildList {
                add("(${history.size - HISTORY_WINDOW} earlier actions omitted)")
                addAll(history.takeLast(HISTORY_WINDOW))
            }
            val (name, args) = reasoner.nextAction(task, elements, shot, recent)
            val action = PilotActionParser.parse(name, args)

            // Terminal actions first.
            when (action) {
                is PilotAction.Done -> {
                    if (!traceBroken && trace.isNotEmpty() && trace.size <= MAX_TRACE_STEPS) {
                        onTrace?.invoke(trace.toList())
                    }
                    emit(AgentEvent.Final(action.result)); return@flow
                }
                is PilotAction.Ask -> {
                    val ask = askUser
                    if (ask == null) { emit(AgentEvent.Final(action.question)); return@flow }
                    emit(AgentEvent.InputRequested(action.question, null))
                    val answer = ask(action.question)
                    if (answer.isNullOrBlank()) { emit(AgentEvent.Final("Stopped — no input provided.")); return@flow }
                    history.add("asked user: \"${action.question}\" → they said: \"$answer\"")
                    continue
                }
                is PilotAction.Invalid -> { history.add("invalid action: ${action.reason}"); continue }
                else -> {}
            }
            if (actuator.cancelled()) { emit(AgentEvent.Final("Stopped.")); return@flow }

            // Stuck guard: same action on the same screen 3x running → give up (not just any repeat,
            // so legit waits/re-scrolls aren't cut short). Args are whitespace-canonicalized —
            // the model's JSON formatting varies call-to-call and must not defeat the guard.
            val canonArgs = args.filterNot { it.isWhitespace() }
            val actionSig = "$screenSig##$name$canonArgs"
            if (actionSig == lastActionSig) stuckCount++ else stuckCount = 0
            lastActionSig = actionSig
            if (stuckCount >= 2) {
                emit(AgentEvent.Final("Stopped — repeating the same action with no effect. Try rephrasing."))
                return@flow
            }

            // Repeat-blocker: refuse to re-execute an action already tried twice on this screen.
            val attemptKey = "$screenSig##$name$canonArgs"
            val tries = attempted.getOrDefault(attemptKey, 0)
            if (tries >= 2) {
                history.add(
                    "BLOCKED $name$args — already tried ${tries}x on this exact screen with no " +
                        "progress. Do something DIFFERENT (another element, scroll, back, or done/ask).",
                )
                continue
            }
            attempted[attemptKey] = tries + 1

            val id = UUID.randomUUID().toString()
            emit(AgentEvent.ToolCall(id, name, args, true))
            val (ok, note) = execute(action, elements)
            emit(AgentEvent.ToolResult(id, name, if (ok) note else "failed: $note", ok))
            if (ok) {
                val ts = traceStepOf(action, elements)
                if (ts != null) trace.add(ts) else traceBroken = true
            }
            // Let the screen settle after actions that navigate/transition, so the NEXT perceive
            // (and screenshot) reflect the new screen — this is what stops the model re-issuing the
            // same tap because the old screen was still showing. App launches are the slowest
            // transition (cold starts, splash screens) and get extra time.
            if (ok && action.changesScreen()) {
                kotlinx.coroutines.delay(if (action is PilotAction.LaunchApp) LAUNCH_SETTLE_MS else SETTLE_MS)
            }
            // Outcome-annotated note (pi lesson: results carry verdict + post-state, never a
            // bare "tapped X"): tell the model where the action LANDED, or that it did nothing.
            history.add(
                if (!ok) note
                else if (!action.changesScreen()) note
                else {
                    val after = actuator.perceive()
                    val afterSig = after.joinToString("|") { "${it.text}:${it.bounds.joinToString(",")}" }
                    if (afterSig == screenSig) "$note → screen did NOT change (no effect)"
                    else "$note → now on: ${screenTitle(after)}"
                },
            )
        }
        emit(AgentEvent.Final("Stopped after $maxSteps steps."))
    }.flowOn(Dispatchers.IO) // network (reasoner) + a11y calls must run off the main thread

    /**
     * Re-locate a chosen element on the CURRENT screen before acting. The model picked its id
     * from a snapshot taken before a slow LLM round-trip — the screen may have shifted since.
     * Identity match (resId/text/desc) on a fresh perceive gives correct bounds; a vanished
     * target fails cleanly instead of tapping whatever moved into its place.
     */
    private fun freshen(target: PilotElement): PilotElement? {
        val anonymous = target.resId.isNullOrBlank() && target.text.isNullOrBlank() && target.desc.isNullOrBlank()
        if (anonymous) return target // nothing durable to match on — act on the snapshot node
        val fresh = actuator.perceive()
        fresh.firstOrNull {
            it.resId == target.resId && it.text == target.text && it.desc == target.desc && it.cls == target.cls
        }?.let { return it }
        target.resId?.takeIf { it.isNotBlank() }?.let { r -> fresh.firstOrNull { it.resId == r }?.let { return it } }
        target.text?.takeIf { it.isNotBlank() }?.let { t -> fresh.firstOrNull { it.text == t }?.let { return it } }
        target.desc?.takeIf { it.isNotBlank() }?.let { d -> fresh.firstOrNull { it.desc == d }?.let { return it } }
        return null
    }

    /** Perform one non-terminal action; returns (success, human note for history/UI). */
    private suspend fun execute(
        action: PilotAction, elements: List<PilotElement>,
    ): Pair<Boolean, String> {
        fun el(id: Int): PilotElement? {
            val chosen = elements.firstOrNull { it.id == id } ?: return null
            return freshen(chosen)
        }
        return when (action) {
            is PilotAction.Tap -> el(action.id)?.let { actuator.tap(it) to "tapped id ${action.id} (${it.label()})" }
                ?: (false to "tap: no element id ${action.id}")
            is PilotAction.LongPress -> el(action.id)?.let { actuator.longPress(it) to "long-pressed id ${action.id}" }
                ?: (false to "long_press: no element id ${action.id}")
            is PilotAction.DoubleTap -> el(action.id)?.let { actuator.doubleTap(it) to "double-tapped id ${action.id}" }
                ?: (false to "double_tap: no element id ${action.id}")
            is PilotAction.Drag -> {
                val from = el(action.fromId); val to = el(action.toId)
                if (from == null || to == null) false to "drag: missing element id"
                else actuator.drag(from, to) to "dragged ${action.fromId}→${action.toId}"
            }
            is PilotAction.Type -> el(action.id)?.let { actuator.type(it, action.text) to "typed \"${action.text}\" into id ${action.id}" }
                ?: (false to "type: no element id ${action.id}")
            is PilotAction.Clear -> el(action.id)?.let { actuator.clear(it) to "cleared id ${action.id}" }
                ?: (false to "clear: no element id ${action.id}")
            is PilotAction.Swipe -> actuator.swipe(action.direction) to "swiped ${action.direction}"
            // scroll(dir) reveals content in that direction → finger swipes the OPPOSITE way.
            is PilotAction.Scroll -> actuator.swipe(ScrollMap.toSwipe(action.direction)) to "scrolled ${action.direction}"
            is PilotAction.LaunchApp -> actuator.launchApp(action.query) to "launched app: ${action.query}"
            is PilotAction.Back -> actuator.back() to "pressed back"
            is PilotAction.Home -> actuator.home() to "went home"
            is PilotAction.Recents -> actuator.recents() to "opened recents"
            is PilotAction.Notifications -> actuator.notifications() to "opened notifications"
            is PilotAction.QuickSettings -> actuator.quickSettings() to "opened quick settings"
            is PilotAction.Wait -> { kotlinx.coroutines.delay(action.ms); true to "waited ${action.ms}ms" }
            else -> false to "unhandled action"
        }
    }

    private fun PilotElement.label(): String = text ?: desc ?: cls ?: ""

    /** First few visible texts — enough for the model to recognize which screen it landed on. */
    private fun screenTitle(elements: List<PilotElement>): String =
        elements.mapNotNull { e -> (e.text ?: e.desc)?.takeIf { it.isNotBlank() } }
            .distinct().take(4).joinToString(" · ").ifBlank { "(blank screen)" }

    /** Convert an executed action into a replayable [TraceStep] (null = not traceable). */
    private fun traceStepOf(action: PilotAction, elements: List<PilotElement>): TraceStep? {
        fun el(id: Int) = elements.firstOrNull { it.id == id }
        fun target(id: Int, name: String, arg: String? = null): TraceStep? {
            val e = el(id) ?: return null
            // An element with no durable identifier can't be re-located on replay.
            if (e.resId.isNullOrBlank() && e.text.isNullOrBlank() && e.desc.isNullOrBlank()) return null
            return TraceStep(
                action = name, arg = arg,
                targetResId = e.resId, targetText = e.text, targetDesc = e.desc, targetCls = e.cls,
            )
        }
        return when (action) {
            is PilotAction.Tap -> target(action.id, "tap")
            is PilotAction.LongPress -> target(action.id, "long_press")
            is PilotAction.DoubleTap -> target(action.id, "double_tap")
            is PilotAction.Type -> target(action.id, "type", action.text)
            is PilotAction.Clear -> target(action.id, "clear")
            is PilotAction.Swipe -> TraceStep("swipe", action.direction)
            is PilotAction.Scroll -> TraceStep("scroll", action.direction)
            is PilotAction.LaunchApp -> TraceStep("launch_app", action.query)
            is PilotAction.Back -> TraceStep("back")
            is PilotAction.Home -> TraceStep("home")
            is PilotAction.Recents -> TraceStep("recents")
            is PilotAction.Notifications -> TraceStep("notifications")
            is PilotAction.QuickSettings -> TraceStep("quick_settings")
            is PilotAction.Wait -> TraceStep("wait", action.ms.toString())
            // Drag targets two elements; skip tracing (rare + fragile to replay).
            else -> null
        }
    }

    /** Actions that typically change the screen and warrant a settle delay before re-perceiving. */
    private fun PilotAction.changesScreen(): Boolean = when (this) {
        is PilotAction.Tap, is PilotAction.DoubleTap, is PilotAction.LaunchApp,
        is PilotAction.Back, is PilotAction.Home, is PilotAction.Recents,
        is PilotAction.Notifications, is PilotAction.QuickSettings,
        is PilotAction.Swipe, is PilotAction.Scroll -> true
        else -> false
    }

    private companion object {
        const val SETTLE_MS = 700L
        const val LAUNCH_SETTLE_MS = 1600L  // app launches need splash/cold-start time
        const val MAX_TRACE_STEPS = 15      // longer flows are too fragile to replay verbatim
        const val HISTORY_WINDOW = 30       // reasoner sees the last N action notes verbatim
    }
}
