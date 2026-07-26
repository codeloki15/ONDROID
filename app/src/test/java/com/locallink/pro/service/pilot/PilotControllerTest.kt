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

    private fun el(id: Int, text: String) =
        PilotElement(id, text, null, null, "Button", intArrayOf(0, 0, 10, 10), true, false)

    /** Configurable fake actuator; records taps, screens supplied by [perceiveFn]. */
    private class FakeActuator(
        val perceiveFn: () -> List<PilotElement>,
        val isCancelled: () -> Boolean = { false },
        val onTap: (PilotElement) -> Boolean = { true },
        val onBack: () -> Unit = {},
    ) : PilotActuator {
        val tapped = ArrayList<Int>()
        var backs = 0
        override fun perceive() = perceiveFn()
        override suspend fun tap(e: PilotElement): Boolean { tapped.add(e.id); return onTap(e) }
        override suspend fun longPress(e: PilotElement) = true
        override suspend fun doubleTap(e: PilotElement) = true
        override suspend fun drag(from: PilotElement, to: PilotElement) = true
        override suspend fun type(e: PilotElement, text: String) = true
        override fun clear(e: PilotElement) = true
        override suspend fun pressEnter(e: PilotElement) = true
        override suspend fun swipe(direction: String) = true
        override fun launchApp(app: String) = true
        override fun back(): Boolean { backs++; onBack(); return true }
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
        // fires) but is stable within a step. Three perceives per step: snapshot + pre-act
        // freshen + post-act outcome check.
        var calls = 0
        val actuator = FakeActuator(perceiveFn = {
            val step = calls++ / 3
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

    @Test fun goesBackToEscapeAStuckScreenInsteadOfGivingUp() = runTest {
        // A run repeating itself is usually on a screen it didn't expect — a dialog, the wrong
        // tab. Back escapes most of those, so it must try that before declaring failure.
        var wentBack = false
        val stuck = listOf(PilotElement(0, "Stuck", null, "id/stuck", null, intArrayOf(0, 0, 10, 10), true, false))
        val escaped = listOf(PilotElement(0, "Escaped", null, "id/escaped", null, intArrayOf(0, 0, 10, 10), true, false))

        val actuator = object : PilotActuator {
            override fun perceive() = if (wentBack) escaped else stuck
            override suspend fun tap(e: PilotElement) = true
            override suspend fun longPress(e: PilotElement) = true
            override suspend fun doubleTap(e: PilotElement) = true
            override suspend fun drag(from: PilotElement, to: PilotElement) = true
            override suspend fun type(e: PilotElement, text: String) = true
            override fun clear(e: PilotElement) = true
            override suspend fun pressEnter(e: PilotElement) = true
            override suspend fun swipe(direction: String) = true
            override fun launchApp(app: String) = true
            override fun back(): Boolean { wentBack = true; return true }
            override fun home() = true
            override fun recents() = true
            override fun notifications() = true
            override fun quickSettings() = true
            override fun cancelled() = false
        }

        // Taps forever on the stuck screen; once Back escapes, reports done.
        val ctrl = PilotController(
            reasoner = PilotReasoner { _, _, _, _ ->
                if (wentBack) "done" to """{"result":"recovered"}""" else "tap" to """{"id":0}"""
            },
            actuator = actuator,
            maxSteps = 25,
        )
        val events = ctrl.run("escape").toList()

        assertTrue("should have tried Back to get unstuck", wentBack)
        val last = events.last()
        assertTrue(last is AgentEvent.Final)
        assertEquals("recovered", (last as AgentEvent.Final).text)
    }

    // ─── Reflection: A/B/C verdict after a navigation ───────────────────────────────────────

    @Test fun aWrongPageIsUndoneOnTheStepItHappens() = runTest {
        // Landing on an ad used to cost three identical taps before the stuck guard noticed.
        // A reflector catches it on the step it happens, and the model is told to re-route.
        var onAd = false
        var reflections = 0
        val seenHistory = ArrayList<List<String>>()

        val actuator = FakeActuator(
            perceiveFn = { if (onAd) listOf(el(0, "Congratulations, you won")) else listOf(el(0, "Open")) },
            onTap = { onAd = true; true },
            onBack = { onAd = false },
        )
        val ctrl = PilotController(
            reasoner = PilotReasoner { _, _, _, history ->
                seenHistory.add(history)
                if (actuator.backs > 0) "done" to """{"result":"recovered"}""" else "tap" to """{"id":0}"""
            },
            actuator = actuator,
            reflector = { _, _, _, _ -> reflections++; Reflection.WRONG_PAGE },
            maxSteps = 10,
        )
        val events = ctrl.run("open my orders").toList()

        assertEquals("one tap, undone once — not three taps then the stuck guard", 1, actuator.tapped.size)
        assertEquals(1, actuator.backs)
        assertEquals(1, reflections)
        assertEquals("recovered", (events.last() as AgentEvent.Final).text)
        // Phase 2: the history the model reads carries the verdict, not just what happened.
        val note = seenHistory.last().last()
        assertTrue("history should say it went back and why, got: $note", note.contains("went BACK"))
    }

    @Test fun theNextActionIsPlannedWhileTheVerdictIsStillInFlight() = runTest {
        // Measured on device: a verdict took ~1.4s sitting in front of a reasoner call that takes
        // 2.5-4.3s and can hide it completely. The verdict is not needed until the next action is
        // about to run, so it is started and resolved a step later.
        //
        // This test deadlocks if reflection ever goes back on the critical path: the reflector
        // only unblocks when the SECOND planning call happens, which an inline await prevents.
        val reflecting = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var onNext = false
        val actuator = FakeActuator(
            perceiveFn = { if (onNext) listOf(el(0, "Results")) else listOf(el(0, "Search")) },
            onTap = { onNext = true; true },
        )
        var calls = 0
        val ctrl = PilotController(
            reasoner = PilotReasoner { _, _, _, _ ->
                if (++calls == 1) "tap" to """{"id":0}"""
                else { release.complete(Unit); "done" to """{"result":"overlapped"}""" }
            },
            actuator = actuator,
            reflector = { _, _, _, _ ->
                reflecting.complete(Unit); release.await(); Reflection.MATCHED
            },
        )
        val events = ctrl.run("search").toList()

        assertTrue("the verdict should have been started", reflecting.isCompleted)
        assertEquals("overlapped", (events.last() as AgentEvent.Final).text)
    }

    @Test fun aNoOpScreenIsDecidedWithoutAskingTheReflector() = runTest {
        // "Nothing happened" is free — identical screen signatures. Paying a model call to
        // re-derive it would be the whole cost of reflection for none of the benefit.
        var reflections = 0
        val script = ArrayDeque(listOf("tap" to """{"id":0}""", "done" to """{"result":"ok"}"""))
        val actuator = FakeActuator(perceiveFn = { oneElement })
        val ctrl = PilotController(
            reasoner = { _, _, _, _ -> script.removeFirst() },
            actuator = actuator,
            reflector = { _, _, _, _ -> reflections++; Reflection.WRONG_PAGE },
        )
        ctrl.run("tap it").toList()

        assertEquals("an inert screen must not cost a reflection", 0, reflections)
        assertEquals(0, actuator.backs)
    }

    @Test fun anIncrementalScreenChangeDoesNotCostAReflection() = runTest {
        // A list that scrolled, or a field that gained text, keeps most of its elements. Only a
        // real navigation can land on the wrong page, so only that is worth asking about.
        var scrolled = false
        var reflections = 0
        val script = ArrayDeque(listOf("tap" to """{"id":0}""", "done" to """{"result":"ok"}"""))
        val actuator = FakeActuator(
            perceiveFn = {
                listOf(el(0, "Alpha"), el(1, "Beta"), el(2, "Gamma"),
                    el(3, if (scrolled) "Delta" else "Epsilon"))
            },
            onTap = { scrolled = true; true },
        )
        val ctrl = PilotController(
            reasoner = { _, _, _, _ -> script.removeFirst() },
            actuator = actuator,
            reflector = { _, _, _, _ -> reflections++; Reflection.WRONG_PAGE },
        )
        ctrl.run("scroll the list").toList()

        assertEquals("3 of 4 elements survived — same page, no reflection", 0, reflections)
        assertEquals(0, actuator.backs)
    }

    @Test fun aFailingReflectorLeavesTheRunAlone() = runTest {
        // Reflection is advisory. A network blip on it must not cost a task that is going fine.
        var onNext = false
        val actuator = FakeActuator(
            perceiveFn = { if (onNext) listOf(el(0, "Results")) else listOf(el(0, "Search")) },
            onTap = { onNext = true; true },
        )
        val script = ArrayDeque(listOf("tap" to """{"id":0}""", "done" to """{"result":"found it"}"""))
        val ctrl = PilotController(
            reasoner = { _, _, _, _ -> script.removeFirst() },
            actuator = actuator,
            reflector = { _, _, _, _ -> error("reflector is down") },
        )
        val events = ctrl.run("search").toList()

        assertEquals("found it", (events.last() as AgentEvent.Final).text)
        assertEquals("a broken reflector must not trigger a rollback", 0, actuator.backs)
    }

    @Test fun anUndoneActionIsNotTaughtAsARoutineStep() = runTest {
        // Saving the wrong turn would teach the detour as the route — the next replay would walk
        // straight back into the ad it just learned to avoid.
        var onAd = false
        var savedTrace: List<TraceStep>? = null
        val actuator = FakeActuator(
            perceiveFn = { if (onAd) listOf(el(0, "You won a prize")) else listOf(el(0, "Open")) },
            onTap = { onAd = true; true },
            onBack = { onAd = false },
        )
        val ctrl = PilotController(
            reasoner = PilotReasoner { _, _, _, _ ->
                if (actuator.backs > 0) "done" to """{"result":"stopped"}""" else "tap" to """{"id":0}"""
            },
            actuator = actuator,
            reflector = { _, _, _, _ -> Reflection.WRONG_PAGE },
            onTrace = { steps -> savedTrace = steps },
        )
        ctrl.run("open my orders").toList()

        assertEquals(1, actuator.backs)
        assertEquals("the undone tap must not become a routine step", null, savedTrace)
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
            override suspend fun pressEnter(e: PilotElement) = true
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
