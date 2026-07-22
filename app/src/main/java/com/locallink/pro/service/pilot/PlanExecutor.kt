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
) {
    fun run(task: String): Flow<AgentEvent> = flow {
        var plan = planner.plan(task, "")
        emit(AgentEvent.Plan(plan.todos))
        val done = ArrayList<String>()
        var steps = 0
        var replans = 0
        var i = 0
        while (i < plan.todos.size) {
            if (steps++ >= maxSteps) { emit(AgentEvent.Final("Paused after $maxSteps steps.")); return@flow }
            val todo = plan.todos[i]
            emit(AgentEvent.TodoStatus(i, todo.text, done = false))

            var answer: String? = null
            if (todo.needsInput) {
                emit(AgentEvent.InputRequested(todo.text, todo.inputReason))
                answer = runner.requestInput(todo.text, todo.inputReason)
                if (answer == null) { emit(AgentEvent.Final("Stopped — no input provided.")); return@flow }
            }

            val ok: Boolean = when (todo.channel) {
                Channel.CHAT -> { emit(AgentEvent.Token(runner.chat(todo.text))); true }
                Channel.COMPOSIO -> { runner.composio(todo.text); true }
                Channel.PILOT -> runner.pilot(if (answer != null) "${todo.text} [user said: $answer]" else todo.text)
            }

            if (ok) {
                emit(AgentEvent.TodoStatus(i, todo.text, done = true))
                done.add(todo.text)
                i++
            } else {
                if (replans++ >= maxReplans) { emit(AgentEvent.Final("Stopped — couldn't complete after re-planning.")); return@flow }
                val ctx = "Completed: ${done.joinToString("; ")}. Stuck on: ${todo.text}."
                plan = planner.plan(task, ctx)
                emit(AgentEvent.Plan(plan.todos))
                i = 0
            }
        }
        emit(AgentEvent.Final("Done."))
    }
}
