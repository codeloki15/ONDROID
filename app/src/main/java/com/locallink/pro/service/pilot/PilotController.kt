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

class PilotController(
    private val reasoner: PilotReasoner,
    private val perceive: () -> List<PilotElement>,
    private val tap: suspend (PilotElement) -> Boolean,
    private val cancelled: () -> Boolean,
    private val maxSteps: Int = 25,
) {
    fun run(task: String): Flow<AgentEvent> = flow {
        val history = ArrayList<String>()
        var lastSig: String? = null
        var step = 0
        while (step < maxSteps) {
            step++
            if (cancelled()) { emit(AgentEvent.Final("Stopped.")); return@flow }
            val elements = perceive()
            val sig = elements.joinToString("|") { "${it.text}:${it.bounds.joinToString(",")}" }
            val (name, args) = reasoner.nextAction(task, elements, /*screenshot*/ null, history)
            when (val action = PilotActionParser.parse(name, args)) {
                is PilotAction.Tap -> {
                    if (cancelled()) { emit(AgentEvent.Final("Stopped.")); return@flow }
                    val target = elements.firstOrNull { it.id == action.id }
                    if (target == null) { history.add("tap failed: no element id ${action.id}"); continue }
                    val id = UUID.randomUUID().toString()
                    emit(AgentEvent.ToolCall(id, "tap", args, true))
                    val ok = tap(target)
                    emit(AgentEvent.ToolResult(id, "tap", if (ok) "tapped" else "tap failed", ok))
                    history.add("tapped id ${action.id} (${target.text})")
                }
                is PilotAction.Done -> { emit(AgentEvent.Final(action.result)); return@flow }
                is PilotAction.Ask -> { emit(AgentEvent.Final(action.question)); return@flow }
                is PilotAction.Invalid -> history.add("invalid action: ${action.reason}")
            }
            // No-progress guard: identical screen signature two steps running → stop.
            if (sig == lastSig) { emit(AgentEvent.Final("Stopped — no progress on screen.")); return@flow }
            lastSig = sig
        }
        emit(AgentEvent.Final("Stopped after $maxSteps steps."))
    }.flowOn(Dispatchers.IO) // network (reasoner) + a11y calls must run off the main thread
}
