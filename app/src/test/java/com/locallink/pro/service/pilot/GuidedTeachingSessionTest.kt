package com.locallink.pro.service.pilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session's job is bookkeeping: which steps were taught, and which recorded actions belong
 * to each. Undo is where that can silently corrupt a routine — dropping a step but keeping its
 * actions leaves a saved routine that does something the user thought they removed.
 */
class GuidedTeachingSessionTest {

    private fun step(action: String, target: String? = null) =
        TraceStep(action = action, targetText = target)

    @Test fun startsEmptyAndNamed() {
        val s = GuidedTeachingSession()
        s.start("lavazza gusto crema price")

        assertEquals("lavazza gusto crema price", s.name.value)
        assertTrue(s.steps.value.isEmpty())
        assertTrue(s.trace.value.isEmpty())
        assertTrue(s.isActive)
    }

    @Test fun eachStepContributesItsActionsToOneTrace() {
        val s = GuidedTeachingSession()
        s.start("amazon price")
        s.addStep("open amazon", "Opened Amazon.", true, listOf(step("launch_app")))
        s.addStep("search lavazza", "Showed results.", true, listOf(step("tap"), step("type")))

        assertEquals(2, s.steps.value.size)
        // The trace is the routine — the steps list is only how it was taught.
        assertEquals(3, s.trace.value.size)
        assertEquals(listOf("launch_app", "tap", "type"), s.trace.value.map { it.action })
    }

    @Test fun undoRemovesTheStepAndExactlyItsOwnActions() {
        val s = GuidedTeachingSession()
        s.start("amazon price")
        s.addStep("open amazon", "Opened Amazon.", true, listOf(step("launch_app")))
        s.addStep("search lavazza", "Showed results.", true, listOf(step("tap"), step("type")))

        s.undoLast()

        assertEquals(1, s.steps.value.size)
        assertEquals("open amazon", s.steps.value.single().instruction)
        // Both of the second step's actions go, and the first step's survives untouched.
        assertEquals(listOf("launch_app"), s.trace.value.map { it.action })
    }

    @Test fun undoingAStepThatRecordedNothingLeavesTheTraceAlone() {
        val s = GuidedTeachingSession()
        s.start("amazon price")
        s.addStep("open amazon", "Opened Amazon.", true, listOf(step("launch_app")))
        // A step can fail without performing anything — undoing it must not eat a real action.
        s.addStep("check price", "Couldn't finish that step.", false, emptyList())

        s.undoLast()

        assertEquals(1, s.steps.value.size)
        assertEquals(listOf("launch_app"), s.trace.value.map { it.action })
    }

    @Test fun undoOnAnEmptySessionIsHarmless() {
        val s = GuidedTeachingSession()
        s.start("nothing taught")
        s.undoLast()
        assertTrue(s.steps.value.isEmpty())
        assertTrue(s.trace.value.isEmpty())
    }

    @Test fun failedStepsAreKeptSoTheUserCanSeeThem() {
        val s = GuidedTeachingSession()
        s.start("amazon price")
        s.addStep("check price", "Couldn't finish that step.", false, emptyList())

        assertEquals(1, s.steps.value.size)
        assertFalse(s.steps.value.single().succeeded)
    }

    @Test fun startingAgainDiscardsThePreviousSession() {
        val s = GuidedTeachingSession()
        s.start("first routine")
        s.addStep("open amazon", "Opened Amazon.", true, listOf(step("launch_app")))

        // Teaching a different routine must not append to the last one.
        s.start("second routine")

        assertEquals("second routine", s.name.value)
        assertTrue(s.steps.value.isEmpty())
        assertTrue(s.trace.value.isEmpty())
    }

    @Test fun clearEndsTheSession() {
        val s = GuidedTeachingSession()
        s.start("amazon price")
        s.addStep("open amazon", "Opened Amazon.", true, listOf(step("launch_app")))

        s.clear()

        assertFalse(s.isActive)
        assertTrue(s.trace.value.isEmpty())
    }
}
