package com.locallink.pro.service.pilot

import com.locallink.pro.service.llm.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunProgressTest {
    private class Clock(var t: Long = 0) : () -> Long {
        override fun invoke() = t
        fun advance(ms: Long) { t += ms }
    }

    private fun todo(text: String) = Todo(text, Channel.PILOT, false, null)

    @Test fun aPlannedRunCountsItsLegs() {
        val p = RunProgress("book a table")
        p.onEvent(AgentEvent.Plan(listOf(todo("open app"), todo("search"), todo("book"))))
        assertEquals(0, p.percent())
        assertTrue(p.detail().startsWith("Step 1 of 3"))

        p.onEvent(AgentEvent.TodoStatus(0, "open app", done = true))
        assertEquals(33, p.percent())
        assertTrue(p.detail().startsWith("Step 2 of 3"))
    }

    @Test fun aPilotDirectRunHasNoDenominatorAndSaysSo() {
        // Voice and replayed routines emit no plan. An indeterminate bar is the honest answer —
        // but the tool calls still say what is happening, which is what was actually asked for.
        val p = RunProgress("what's my battery")
        assertNull("no plan means no percentage", p.percent())
        p.onEvent(AgentEvent.ToolCall("1", "launch_app", "{}", true))
        assertTrue("should name the current action, got: ${p.detail()}", p.detail().contains("opening an app"))
    }

    @Test fun noEstimateUntilTwoLegsHaveFinished() {
        // Legs vary enormously — one is a single intent, the next is a dozen screens. One sample
        // would swing the estimate wildly, so it stays hidden rather than lying confidently.
        val clock = Clock()
        val p = RunProgress("three legs", clock)
        p.onEvent(AgentEvent.Plan(listOf(todo("a"), todo("b"), todo("c"))))
        assertNull(p.etaSeconds())

        clock.advance(10_000)
        p.onEvent(AgentEvent.TodoStatus(0, "a", done = true))
        assertNull("one sample is not an estimate", p.etaSeconds())

        clock.advance(10_000)
        p.onEvent(AgentEvent.TodoStatus(1, "b", done = true))
        assertEquals("one 10s leg left", 10L, p.etaSeconds())
    }

    @Test fun theEstimateDisappearsOnceTheLastLegIsDone() {
        val clock = Clock()
        val p = RunProgress("two legs", clock)
        p.onEvent(AgentEvent.Plan(listOf(todo("a"), todo("b"))))
        clock.advance(5_000); p.onEvent(AgentEvent.TodoStatus(0, "a", done = true))
        clock.advance(5_000); p.onEvent(AgentEvent.TodoStatus(1, "b", done = true))
        assertNull("nothing remains to estimate", p.etaSeconds())
        assertEquals(100, p.percent())
    }

    @Test fun statusShowsElapsedAndAnyQueue() {
        val clock = Clock()
        val p = RunProgress("slow one", clock)
        p.queued = 2
        clock.advance(95_000)
        val s = p.status()
        assertTrue("elapsed should be human, got: $s", s.contains("1m 35s"))
        assertTrue("a queue must not be invisible, got: $s", s.contains("2 waiting"))
    }

    @Test fun aLongLegTextIsShortenedForTheShade() {
        val p = RunProgress("x")
        p.onEvent(AgentEvent.Plan(listOf(todo("a".repeat(120)))))
        assertTrue("detail should be trimmed, got ${p.detail().length}", p.detail().length < 80)
    }
}
