package com.locallink.pro.service.pilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperienceTest {

    // ─── TaskNorm ────────────────────────────────────────────────────────

    @Test fun normalizeStripsPunctuationCaseAndSpace() {
        assertEquals("play lo fi on youtube", TaskNorm.normalize("  Play LO-FI on YouTube!! "))
    }

    @Test fun matchesExactNormalizedPhrasings() {
        assertTrue(TaskNorm.matches("Open settings, turn on dark mode", "open settings turn on dark mode"))
    }

    @Test fun matchesNearIdenticalTokenSets() {
        // one extra token out of 5+ → Jaccard ≥ 0.9 fails; identical sets pass regardless of order
        assertTrue(TaskNorm.matches("play music on youtube now", "now play music on youtube"))
        assertFalse(TaskNorm.matches("play music on youtube", "play podcasts on youtube"))
        assertFalse(TaskNorm.matches("open settings", "open camera"))
    }

    // ─── TraceStep serialization ─────────────────────────────────────────

    @Test fun traceStepsRoundTripThroughJson() {
        val steps = listOf(
            TraceStep("launch_app", "youtube"),
            TraceStep("tap", targetResId = "com.yt:id/search", targetText = "Search", targetCls = "android.widget.Button"),
            TraceStep("type", arg = "lo-fi beats", targetResId = "com.yt:id/query"),
            TraceStep("wait", "800"),
        )
        val restored = TraceStep.listFromJson(TraceStep.listToJson(steps))
        assertEquals(steps, restored)
    }

    @Test fun malformedJsonYieldsEmptyList() {
        assertTrue(TraceStep.listFromJson("not json").isEmpty())
    }

    @Test fun needsTargetOnlyForElementActions() {
        assertTrue(TraceStep("tap", targetText = "x").needsTarget)
        assertTrue(TraceStep("type", "hi", targetResId = "r").needsTarget)
        assertFalse(TraceStep("launch_app", "youtube").needsTarget)
        assertFalse(TraceStep("back").needsTarget)
    }

    // ─── Locator fallback chain ──────────────────────────────────────────

    private fun el(id: Int, text: String? = null, desc: String? = null, resId: String? = null, cls: String? = null) =
        PilotElement(id, text, desc, resId, cls, intArrayOf(0, 0, 10, 10), clickable = true, editable = false)

    @Test fun locatorPrefersResourceIdThenTextThenDesc() {
        val elements = listOf(
            el(0, text = "Search"),
            el(1, resId = "app:id/search_btn", text = "Search"),
            el(2, desc = "Search"),
        )
        val byRes = ExperienceReplayer.findTarget(TraceStep("tap", targetResId = "app:id/search_btn", targetText = "Search"), elements)
        assertEquals(1, byRes?.id)
        val byText = ExperienceReplayer.findTarget(TraceStep("tap", targetText = "Search"), elements)
        assertEquals(0, byText?.id)
        val byDesc = ExperienceReplayer.findTarget(TraceStep("tap", targetDesc = "Search"), elements)
        assertEquals(2, byDesc?.id)
    }

    @Test fun locatorPrefersMatchingClassAmongDuplicates() {
        val elements = listOf(
            el(0, text = "Play", cls = "android.widget.TextView"),
            el(1, text = "Play", cls = "android.widget.Button"),
        )
        val hit = ExperienceReplayer.findTarget(
            TraceStep("tap", targetText = "Play", targetCls = "android.widget.Button"), elements,
        )
        assertEquals(1, hit?.id)
    }

    @Test fun locatorReturnsNullWhenAbsent() {
        assertNull(ExperienceReplayer.findTarget(TraceStep("tap", targetText = "Nope"), listOf(el(0, text = "Yes"))))
    }
}
