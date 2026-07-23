package com.locallink.pro.service.pilot

import com.locallink.pro.service.llm.AgentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.UUID

/** A learned routine loaded from the store. */
data class SavedExperience(val id: Long, val steps: List<TraceStep>, val successCount: Int)

/**
 * The learning wrapper around the pilot: when the task matches a previously successful
 * run, its recorded action trace is replayed deterministically — no model calls, no
 * guessing. If no experience exists (or replay aborts because the UI diverged), it
 * falls back to the reasoning loop, and that run's successful trace is saved so the
 * NEXT time is deterministic. Storage is abstracted to lambdas so this stays testable.
 */
class MemoryPilot(
    private val reasoner: PilotReasoner,
    private val actuator: PilotActuator,
    private val screenshot: suspend () -> ByteArray? = { null },
    private val find: suspend (String) -> SavedExperience?,
    private val save: suspend (String, List<TraceStep>) -> Unit,
    private val bump: suspend (Long) -> Unit,
    /** Live mid-run question channel (input floater); null → asks end the run. */
    private val askUser: (suspend (String) -> String?)? = null,
) {
    fun run(task: String): Flow<AgentEvent> = flow {
        val exp = runCatching { find(task) }.getOrNull()
        if (exp != null && exp.steps.isNotEmpty()) {
            val id = UUID.randomUUID().toString()
            emit(AgentEvent.ToolCall(
                id, "replay_routine",
                "{\"steps\":${exp.steps.size},\"learned_from\":${exp.successCount}}", true,
            ))
            val abort = ExperienceReplayer(actuator).replay(exp.steps)
            if (abort == null) {
                runCatching { bump(exp.id) }
                emit(AgentEvent.ToolResult(id, "replay_routine", "replayed ${exp.steps.size} learned steps", true))
                emit(AgentEvent.Final("Done — replayed a routine I learned earlier (no guessing)."))
                return@flow
            }
            emit(AgentEvent.ToolResult(id, "replay_routine", "screen diverged ($abort) — reasoning instead", false))
        }
        emitAll(
            PilotController(
                reasoner = reasoner,
                actuator = actuator,
                screenshot = screenshot,
                onTrace = { steps -> runCatching { save(task, steps) } },
                askUser = askUser,
            ).run(task),
        )
    }.flowOn(Dispatchers.IO)
}
