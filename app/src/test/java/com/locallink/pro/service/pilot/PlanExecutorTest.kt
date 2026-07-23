package com.locallink.pro.service.pilot

import com.locallink.pro.service.llm.AgentEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanExecutorTest {
    private class FakeRunner(
        val onInput: (String) -> String? = { "answer" },
    ) : ChannelRunner {
        val calls = ArrayList<String>()
        override suspend fun chat(todo: String): String { calls.add("chat:$todo"); return "chat-reply" }
        override suspend fun composio(todo: String): String { calls.add("composio:$todo"); return "composio-ok" }
        override suspend fun pilot(todo: String): Boolean { calls.add("pilot:$todo"); return true }
        override suspend fun requestInput(question: String, reason: String?): String? {
            calls.add("input:$question"); return onInput(question)
        }
    }

    @Test fun runsEachTodoByChannel() = runTest {
        val planner = PlanSource { _, _ -> Plan(listOf(
            Todo("say hi", Channel.CHAT, false, null),
            Todo("email bob", Channel.COMPOSIO, false, null),
            Todo("open settings", Channel.PILOT, false, null),
        )) }
        val runner = FakeRunner()
        val events = PlanExecutor(planner, runner).run("do stuff").toList()
        assertEquals(listOf("chat:say hi", "composio:email bob", "pilot:open settings"), runner.calls)
        assertTrue(events.first() is AgentEvent.Plan)
        assertTrue(events.last() is AgentEvent.Final)
    }

    @Test fun pausesForInputOnNeedsInputTodo() = runTest {
        val planner = PlanSource { _, _ -> Plan(listOf(
            Todo("sign in", Channel.PILOT, true, "your password"),
        )) }
        val runner = FakeRunner(onInput = { "hunter2" })
        val events = PlanExecutor(planner, runner).run("login").toList()
        assertTrue(runner.calls.any { it.startsWith("input:") })
        assertTrue(events.any { it is AgentEvent.InputRequested })
    }

    @Test fun asksWithReasonPhrasingAndThreadsAnswerIntoTheTodo() = runTest {
        val planner = PlanSource { _, _ -> Plan(listOf(
            Todo("Delete the chosen apps", Channel.PILOT, true, "Which apps should I delete?"),
        )) }
        val calls = ArrayList<String>()
        val runner = object : ChannelRunner {
            override suspend fun chat(todo: String): String { calls.add("chat:$todo"); return "ok" }
            override suspend fun composio(todo: String) = "ok"
            override suspend fun pilot(todo: String): Boolean { calls.add("pilot:$todo"); return true }
            override suspend fun requestInput(question: String, reason: String?): String? {
                calls.add("input:$question|ctx:$reason"); return "Candy Crush"
            }
        }
        PlanExecutor(planner, runner).run("delete unwanted apps").toList()
        // The floater asks the planner's question, with the todo as context…
        assertEquals("input:Which apps should I delete?|ctx:Delete the chosen apps", calls[0])
        // …and the answer travels INTO the acting todo.
        assertEquals("pilot:Delete the chosen apps [user said: Candy Crush]", calls[1])
    }

    @Test fun stopAbortsThePlanInsteadOfReplanning() = runTest {
        var planCount = 0
        val planner = PlanSource { _, _ ->
            planCount++
            Plan(listOf(Todo("do a thing", Channel.PILOT, false, null)))
        }
        val runner = object : ChannelRunner {
            override suspend fun chat(todo: String) = "ok"
            override suspend fun composio(todo: String) = "ok"
            override suspend fun pilot(todo: String): Boolean = false // user hit STOP mid-leg
            override suspend fun requestInput(question: String, reason: String?): String? = null
        }
        val events = PlanExecutor(planner, runner, cancelled = { true }).run("x").toList()
        assertEquals("no replan after STOP", 1, planCount)
        assertEquals("Stopped.", (events.last() as AgentEvent.Final).text)
    }

    @Test fun replansWhenPilotTodoGetsStuck() = runTest {
        var planCount = 0
        val planner = PlanSource { _, _ ->
            planCount++
            if (planCount == 1) Plan(listOf(Todo("stuck step", Channel.PILOT, false, null)))
            else Plan(listOf(Todo("recovered", Channel.CHAT, false, null)))
        }
        val runner = object : ChannelRunner {
            val calls = ArrayList<String>()
            override suspend fun chat(todo: String): String { calls.add("chat:$todo"); return "ok" }
            override suspend fun composio(todo: String) = "ok"
            override suspend fun pilot(todo: String): Boolean { calls.add("pilot:$todo"); return false } // stuck
            override suspend fun requestInput(question: String, reason: String?): String? = null
        }
        val events = PlanExecutor(planner, runner).run("x").toList()
        assertTrue("should have replanned", planCount >= 2)
        assertTrue(events.last() is AgentEvent.Final)
    }
}
