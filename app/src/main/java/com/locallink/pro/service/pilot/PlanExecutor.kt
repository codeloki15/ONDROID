package com.locallink.pro.service.pilot

import com.locallink.pro.service.llm.AgentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface ChannelRunner {
    suspend fun chat(todo: String): String
    suspend fun composio(todo: String): String
    suspend fun pilot(todo: String): Boolean   // false = stuck/failed
    suspend fun requestInput(question: String, reason: String?): String?
}

class PlanExecutor(
    private val planner: PlanSource,
    private val runner: ChannelRunner,
    private val maxSteps: Int = 25,
    private val maxReplans: Int = 3,
    /** True once the user hit STOP — aborts the whole plan instead of replanning around it. */
    private val cancelled: () -> Boolean = { false },
    /** Short description of what's on screen right now — grounds replans in reality. */
    private val screenSummary: suspend () -> String = { "" },
) {
    fun run(task: String): Flow<AgentEvent> = flow {
        var plan = planner.plan(task, "")
        emit(AgentEvent.Plan(plan.todos))
        val done = ArrayList<String>()
        var steps = 0
        var replans = 0
        var i = 0
        while (i < plan.todos.size) {
            if (cancelled()) { emit(AgentEvent.Final("Stopped.")); return@flow }
            if (steps++ >= maxSteps) { emit(AgentEvent.Final("Paused after $maxSteps steps.")); return@flow }
            val todo = plan.todos[i]
            emit(AgentEvent.TodoStatus(i, todo.text, done = false))

            var answer: String? = null
            if (todo.needsInput) {
                // Ask with the planner's question phrasing when it gave one; the todo text is context.
                val question = todo.inputReason?.takeIf { it.isNotBlank() } ?: todo.text
                val context = todo.text.takeIf { it != question }
                emit(AgentEvent.InputRequested(question, context))
                answer = runner.requestInput(question, context)
                if (answer == null) { emit(AgentEvent.Final("Stopped — no input provided.")); return@flow }
            }

            val withAnswer = if (answer != null) "${todo.text} [user said: $answer]" else todo.text
            val ok: Boolean = when (todo.channel) {
                // A chat todo's reply IS a user-facing answer — emit it so it persists as a message.
                Channel.CHAT -> { emit(AgentEvent.AssistantSay(runner.chat(withAnswer))); true }
                Channel.COMPOSIO -> { emit(AgentEvent.AssistantSay(runner.composio(withAnswer))); true }
                Channel.PILOT -> runner.pilot(withAnswer)
            }

            if (ok) {
                emit(AgentEvent.TodoStatus(i, todo.text, done = true))
                done.add(todo.text)
                i++
            } else {
                // The user pressing STOP surfaces as a failed pilot leg — abort, don't replan.
                if (cancelled()) { emit(AgentEvent.Final("Stopped.")); return@flow }
                if (replans++ >= maxReplans) { emit(AgentEvent.Final("Stopped — couldn't complete after re-planning.")); return@flow }
                val screen = runCatching { screenSummary() }.getOrDefault("")
                val ctx = buildString {
                    append("Completed: ${done.joinToString("; ")}. Stuck on: ${todo.text}.")
                    if (screen.isNotBlank()) append("\nOn screen right now: $screen")
                }
                plan = planner.plan(task, ctx)
                emit(AgentEvent.Plan(plan.todos))
                i = 0
            }
        }
        // Only emit a terminal "Done." when the plan actually did on-device work; a pure chat
        // answer is already the user-facing reply, so a trailing "Done." is just noise.
        if (plan.todos.any { it.channel == Channel.PILOT }) emit(AgentEvent.Final("Done."))
        else emit(AgentEvent.Final(""))
    }
}
