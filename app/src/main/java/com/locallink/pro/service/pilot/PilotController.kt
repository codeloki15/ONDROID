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
    private val maxSteps: Int = 25,
) {
    fun run(task: String): Flow<AgentEvent> = flow {
        val history = ArrayList<String>()
        var lastActionSig: String? = null
        var stuckCount = 0
        var step = 0
        while (step < maxSteps) {
            step++
            if (actuator.cancelled()) { emit(AgentEvent.Final("Stopped.")); return@flow }
            val elements = actuator.perceive()
            val screenSig = elements.joinToString("|") { "${it.text}:${it.bounds.joinToString(",")}" }
            val (name, args) = reasoner.nextAction(task, elements, /*screenshot*/ null, history)
            val action = PilotActionParser.parse(name, args)

            // Terminal actions first.
            when (action) {
                is PilotAction.Done -> { emit(AgentEvent.Final(action.result)); return@flow }
                is PilotAction.Ask -> { emit(AgentEvent.Final(action.question)); return@flow }
                is PilotAction.Invalid -> { history.add("invalid action: ${action.reason}"); continue }
                else -> {}
            }
            if (actuator.cancelled()) { emit(AgentEvent.Final("Stopped.")); return@flow }

            // Stuck guard: same action on the same screen 3x running → give up (not just any repeat,
            // so legit waits/re-scrolls aren't cut short).
            val actionSig = "$screenSig##$name$args"
            if (actionSig == lastActionSig) stuckCount++ else stuckCount = 0
            lastActionSig = actionSig
            if (stuckCount >= 2) {
                emit(AgentEvent.Final("Stopped — repeating the same action with no effect. Try rephrasing."))
                return@flow
            }

            val id = UUID.randomUUID().toString()
            emit(AgentEvent.ToolCall(id, name, args, true))
            val (ok, note) = execute(action, elements)
            emit(AgentEvent.ToolResult(id, name, if (ok) note else "failed: $note", ok))
            history.add(note)
        }
        emit(AgentEvent.Final("Stopped after $maxSteps steps."))
    }.flowOn(Dispatchers.IO) // network (reasoner) + a11y calls must run off the main thread

    /** Perform one non-terminal action; returns (success, human note for history/UI). */
    private suspend fun execute(
        action: PilotAction, elements: List<PilotElement>,
    ): Pair<Boolean, String> {
        fun el(id: Int) = elements.firstOrNull { it.id == id }
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
            is PilotAction.Scroll -> actuator.swipe(action.direction) to "scrolled ${action.direction}"
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
}
