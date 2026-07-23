package com.locallink.pro.service.pilot

import com.locallink.pro.service.llm.AgentEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPilotTest {

    /** Actuator over a fixed screen; records what was driven. */
    private class FakeActuator(
        private val screen: List<PilotElement>,
    ) : PilotActuator {
        val driven = ArrayList<String>()
        override fun perceive(): List<PilotElement> = screen
        override suspend fun tap(e: PilotElement): Boolean { driven.add("tap:${e.id}"); return true }
        override suspend fun longPress(e: PilotElement): Boolean { driven.add("long:${e.id}"); return true }
        override suspend fun doubleTap(e: PilotElement): Boolean { driven.add("double:${e.id}"); return true }
        override suspend fun drag(from: PilotElement, to: PilotElement): Boolean { driven.add("drag"); return true }
        override suspend fun type(e: PilotElement, text: String): Boolean { driven.add("type:${e.id}:$text"); return true }
        override fun clear(e: PilotElement): Boolean { driven.add("clear:${e.id}"); return true }
        override suspend fun swipe(direction: String): Boolean { driven.add("swipe:$direction"); return true }
        override fun launchApp(app: String): Boolean { driven.add("launch:$app"); return true }
        override fun back(): Boolean { driven.add("back"); return true }
        override fun home(): Boolean { driven.add("home"); return true }
        override fun recents(): Boolean = true
        override fun notifications(): Boolean = true
        override fun quickSettings(): Boolean = true
        override fun cancelled(): Boolean = false
    }

    private fun el(id: Int, text: String? = null, resId: String? = null) =
        PilotElement(id, text, null, resId, "android.widget.Button", intArrayOf(0, 0, 9, 9), true, false)

    @Test fun replaysSavedExperienceWithoutCallingReasoner() = runTest {
        val actuator = FakeActuator(listOf(el(0, text = "Search", resId = "yt:id/search")))
        var reasonerCalls = 0
        var bumped: Long? = null
        val pilot = MemoryPilot(
            reasoner = { _, _, _, _ -> reasonerCalls++; "done" to """{"result":"x"}""" },
            actuator = actuator,
            find = { SavedExperience(7L, listOf(TraceStep("launch_app", "youtube"), TraceStep("tap", targetResId = "yt:id/search")), 3) },
            save = { _, _ -> },
            bump = { bumped = it },
        )
        val events = pilot.run("open youtube search").toList()
        assertEquals(0, reasonerCalls)
        assertEquals(listOf("launch:youtube", "tap:0"), actuator.driven)
        assertEquals(7L, bumped)
        val final = events.last() as AgentEvent.Final
        assertTrue(final.text.contains("learned"))
    }

    @Test fun fallsBackToReasoningWhenReplayTargetMissing() = runTest {
        val actuator = FakeActuator(listOf(el(0, text = "Home")))  // no "Search" on screen
        var reasonerCalls = 0
        var saved: List<TraceStep>? = null
        val pilot = MemoryPilot(
            reasoner = { _, _, _, _ -> reasonerCalls++; "done" to """{"result":"recovered"}""" },
            actuator = actuator,
            find = { SavedExperience(1L, listOf(TraceStep("tap", targetText = "Search")), 1) },
            save = { _, steps -> saved = steps },
            bump = { },
        )
        val events = pilot.run("whatever").toList()
        assertTrue("reasoner should take over after replay aborts", reasonerCalls >= 1)
        // The failed replay emits a failed ToolResult before the fallback runs.
        assertTrue(events.filterIsInstance<AgentEvent.ToolResult>().any { !it.success })
        assertTrue(events.last() is AgentEvent.Final)
        assertNull("done-only run has no executed steps to save", saved)
    }

    @Test fun liveAskPausesAndResumesWithTheAnswer() = runTest {
        val actuator = FakeActuator(listOf(el(0, text = "Search")))
        var call = 0
        var historySeen: List<String>? = null
        val pilot = MemoryPilot(
            reasoner = { _, _, _, history ->
                call++
                if (call == 1) "ask" to """{"question":"Which song?"}"""
                else { historySeen = history.toList(); "done" to """{"result":"played"}""" }
            },
            actuator = actuator,
            find = { null },
            save = { _, _ -> },
            bump = { },
            askUser = { q -> "Lo-fi beats" },
        )
        val events = pilot.run("play music").toList()
        // The ask paused the loop, the answer entered history, and the run CONTINUED to done.
        assertEquals("played", (events.last() as AgentEvent.Final).text)
        assertTrue(events.any { it is AgentEvent.InputRequested })
        assertTrue(historySeen!!.any { it.contains("Lo-fi beats") })
    }

    @Test fun partialReplayPrimesTheReasonerAndContinues() = runTest {
        // Screen has the search box but NOT the old song result — replay does step 1, fails at 2.
        val actuator = FakeActuator(listOf(el(0, resId = "yt:id/search")))
        var historySeen: List<String>? = null
        val pilot = MemoryPilot(
            reasoner = { _, _, _, history ->
                historySeen = history.toList()
                "done" to """{"result":"picked the new song"}"""
            },
            actuator = actuator,
            find = { SavedExperience(3L, listOf(
                TraceStep("tap", targetResId = "yt:id/search"),
                TraceStep("tap", targetText = "Old Song That Is Gone"),
            ), 2) },
            save = { _, _ -> },
            bump = { },
        )
        val events = pilot.run("play new song on youtube").toList()
        assertEquals("picked the new song", (events.last() as AgentEvent.Final).text)
        // The deterministic prefix ran…
        assertEquals(listOf("tap:0"), actuator.driven)
        // …and the reasoner started PRIMED with what was already done.
        assertTrue(historySeen!!.any { it.contains("replayed learned step") })
        assertTrue(historySeen!!.any { it.contains("continue the task") })
    }

    @Test fun learnsTraceFromFreshRunAndSavesIt() = runTest {
        val actuator = FakeActuator(listOf(el(0, text = "Wi-Fi", resId = "settings:id/wifi")))
        var saved: List<TraceStep>? = null
        var call = 0
        val pilot = MemoryPilot(
            reasoner = { _, _, _, _ ->
                call++
                if (call == 1) "tap" to """{"id":0}"""
                else "done" to """{"result":"toggled"}"""
            },
            actuator = actuator,
            find = { null },
            save = { _, steps -> saved = steps },
            bump = { },
        )
        pilot.run("toggle wifi").toList()
        assertEquals(listOf("tap:0"), actuator.driven)
        assertEquals(1, saved?.size)
        assertEquals("tap", saved!![0].action)
        assertEquals("settings:id/wifi", saved!![0].targetResId)
    }
}
