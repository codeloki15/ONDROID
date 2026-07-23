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
        var primed: List<String> = emptyList()
        val exp = runCatching { find(task) }.getOrNull()
        if (exp != null && exp.steps.isNotEmpty()) {
            val id = UUID.randomUUID().toString()
            emit(AgentEvent.ToolCall(
                id, "replay_routine",
                "{\"steps\":${exp.steps.size},\"learned_from\":${exp.successCount}}", true,
            ))
            val outcome = ExperienceReplayer(actuator).replayAll(exp.steps)
            if (outcome.fullSuccess) {
                runCatching { bump(exp.id) }
                emit(AgentEvent.ToolResult(id, "replay_routine", "replayed ${exp.steps.size} learned steps", true))
                emit(AgentEvent.Final("Done — replayed a routine I learned earlier (no guessing)."))
                return@flow
            }
            if (outcome.executedNotes.isNotEmpty()) {
                // Partial replay: keep the deterministic prefix, let the model finish the tail.
                emit(AgentEvent.ToolResult(
                    id, "replay_routine",
                    "replayed ${outcome.executedNotes.size}/${exp.steps.size} learned steps, then " +
                        "${outcome.reason} — continuing with reasoning", true,
                ))
                primed = outcome.executedNotes.map { "replayed learned step: $it" } +
                    "the learned routine diverged here (${outcome.reason}) — continue the task from the CURRENT screen" +
                    "usually ONE more action finishes it (e.g. tap the matching result); the moment the goal state is visible, call done()"
            } else {
                emit(AgentEvent.ToolResult(id, "replay_routine", "screen diverged (${outcome.reason}) — reasoning instead", false))
            }
        }
        emitAll(
            PilotController(
                reasoner = reasoner,
                actuator = actuator,
                screenshot = screenshot,
                onTrace = { steps -> runCatching { save(task, steps) } },
                askUser = askUser,
                primedHistory = primed,
            ).run(task),
        )
    }.flowOn(Dispatchers.IO)
}
