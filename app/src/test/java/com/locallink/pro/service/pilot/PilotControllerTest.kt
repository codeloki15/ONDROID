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

    /** Configurable fake actuator; records taps, screens supplied by [perceiveFn]. */
    private class FakeActuator(
        val perceiveFn: () -> List<PilotElement>,
        val isCancelled: () -> Boolean = { false },
        val onTap: (PilotElement) -> Boolean = { true },
    ) : PilotActuator {
        val tapped = ArrayList<Int>()
        override fun perceive() = perceiveFn()
        override suspend fun tap(e: PilotElement): Boolean { tapped.add(e.id); return onTap(e) }
        override suspend fun longPress(e: PilotElement) = true
        override suspend fun doubleTap(e: PilotElement) = true
        override suspend fun drag(from: PilotElement, to: PilotElement) = true
        override suspend fun type(e: PilotElement, text: String) = true
        override fun clear(e: PilotElement) = true
        override suspend fun swipe(direction: String) = true
        override fun launchApp(app: String) = true
        override fun back() = true
        override fun home() = true
        override fun recents() = true
        override fun notifications() = true
        override fun quickSettings() = true
        override fun cancelled() = isCancelled()
    }

    @Test fun tapsThenCompletes() = runTest {
        val script = ArrayDeque(listOf("tap" to """{"id":0}""", "done" to """{"result":"sent"}"""))
        val actuator = FakeActuator(perceiveFn = { oneElement })
        val ctrl = PilotController(
            reasoner = { _, _, _, _ -> script.removeFirst() },
            actuator = actuator,
        )
        val events = ctrl.run("send it").toList()
        assertEquals(listOf(0), actuator.tapped)
        assertTrue(events.last() is AgentEvent.Final)
        assertEquals("sent", (events.last() as AgentEvent.Final).text)
    }

    @Test fun stopsImmediatelyWhenCancelled() = runTest {
        var calls = 0
        val actuator = FakeActuator(perceiveFn = { oneElement }, isCancelled = { true })
        val ctrl = PilotController(
            reasoner = { _, _, _, _ -> calls++; "tap" to """{"id":0}""" },
            actuator = actuator,
        )
        val events = ctrl.run("x").toList()
        assertTrue(events.last() is AgentEvent.Final)
        assertTrue("no tap should run once cancelled", calls <= 1)
    }

    @Test fun stopsAtMaxSteps() = runTest {
        // Reasoner always taps; the screen changes BETWEEN steps (so the stuck guard never
        // fires) but is stable within a step (snapshot + pre-act freshen see the same tree,
        // so the tap executes). Two perceives per step: snapshot + freshen.
        var calls = 0
        val actuator = FakeActuator(perceiveFn = {
            val step = calls++ / 2
            listOf(PilotElement(0, "n$step", null, null, null, intArrayOf(0, 0, 1, 1), true, false))
        })
        val ctrl = PilotController(
            reasoner = PilotReasoner { _, _, _, _ -> "tap" to """{"id":0}""" },
            actuator = actuator,
            maxSteps = 3,
        )
        val events = ctrl.run("loop").toList()
        assertTrue(events.last() is AgentEvent.Final)
        assertEquals(3, actuator.tapped.size)
    }

    @Test fun stuckGuardStopsRepeatedSameActionOnSameScreen() = runTest {
        // Same screen + same action every step → stuck guard stops it well before maxSteps.
        val actuator = FakeActuator(perceiveFn = { oneElement })
        val ctrl = PilotController(
            reasoner = PilotReasoner { _, _, _, _ -> "tap" to """{"id":0}""" },
            actuator = actuator,
            maxSteps = 25,
        )
        val events = ctrl.run("loop").toList()
        assertTrue(events.last() is AgentEvent.Final)
        assertTrue("should stop well before maxSteps", actuator.tapped.size < 5)
    }

    @Test fun launchAppIsDispatched() = runTest {
        val script = ArrayDeque(listOf(
            "launch_app" to """{"app":"Settings"}""", "done" to """{"result":"opened"}""",
        ))
        var launched: String? = null
        val actuator = object : PilotActuator {
            override fun perceive() = emptyList<PilotElement>()
            override suspend fun tap(e: PilotElement) = true
            override suspend fun longPress(e: PilotElement) = true
            override suspend fun doubleTap(e: PilotElement) = true
            override suspend fun drag(from: PilotElement, to: PilotElement) = true
            override suspend fun type(e: PilotElement, text: String) = true
            override fun clear(e: PilotElement) = true
            override suspend fun swipe(direction: String) = true
            override fun launchApp(app: String): Boolean { launched = app; return true }
            override fun back() = true
            override fun home() = true
            override fun recents() = true
            override fun notifications() = true
            override fun quickSettings() = true
            override fun cancelled() = false
        }
        val ctrl = PilotController(
            reasoner = { _, _, _, _ -> script.removeFirst() },
            actuator = actuator,
        )
        ctrl.run("open settings").toList()
        assertEquals("Settings", launched)
    }
}
