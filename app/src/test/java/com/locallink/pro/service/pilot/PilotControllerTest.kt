package com.locallink.pro.service.pilot

import com.locallink.pro.service.llm.AgentEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PilotControllerTest {
    private val oneElement = listOf(
        PilotElement(0, "Send", null, null, "Button", intArrayOf(0, 0, 10, 10), true, false),
    )

    @Test fun tapsThenCompletes() = runTest {
        val script = ArrayDeque(listOf("tap" to """{"id":0}""", "done" to """{"result":"sent"}"""))
        val tapped = ArrayList<Int>()
        val ctrl = PilotController(
            reasoner = { _, _, _, _ -> script.removeFirst() },
            perceive = { oneElement },
            tap = { e -> tapped.add(e.id); true },
            cancelled = { false },
        )
        val events = ctrl.run("send it").toList()
        assertEquals(listOf(0), tapped)
        assertTrue(events.last() is AgentEvent.Final)
        assertEquals("sent", (events.last() as AgentEvent.Final).text)
    }

    @Test fun stopsImmediatelyWhenCancelled() = runTest {
        var calls = 0
        val ctrl = PilotController(
            reasoner = { _, _, _, _ -> calls++; "tap" to """{"id":0}""" },
            perceive = { oneElement },
            tap = { true },
            cancelled = { true }, // STOP already pressed
        )
        val events = ctrl.run("x").toList()
        assertTrue(events.last() is AgentEvent.Final)
        assertTrue("no tap should run once cancelled", calls <= 1)
    }

    @Test fun stopsAtMaxSteps() = runTest {
        // Reasoner always taps (never done); each perceive returns a DIFFERENT screen
        // signature so the no-progress guard never fires and the loop runs to maxSteps.
        var step = 0
        val ctrl = PilotController(
            reasoner = PilotReasoner { _, _, _, _ -> "tap" to """{"id":0}""" },
            perceive = { listOf(PilotElement(0, "n${step++}", null, null, null, intArrayOf(0, 0, 1, 1), true, false)) },
            tap = { true },
            cancelled = { false },
            maxSteps = 3,
        )
        val events = ctrl.run("loop").toList()
        assertTrue(events.last() is AgentEvent.Final)
    }
}
