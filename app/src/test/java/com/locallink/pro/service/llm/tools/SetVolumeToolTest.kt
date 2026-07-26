package com.locallink.pro.service.llm.tools

import com.locallink.pro.service.llm.tools.SetVolumeTool.Companion.indexFor
import com.locallink.pro.service.llm.tools.SetVolumeTool.Companion.percentOf
import com.locallink.pro.service.llm.tools.SetVolumeTool.Companion.stepIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The index arithmetic, which is the only part of volume control that can be wrong quietly.
 *
 * Android streams have coarse ranges — commonly 7 or 15 steps, not 100 — so percentages do not map
 * cleanly and a naive relative step rounds to nothing.
 */
class SetVolumeToolTest {

    @Test fun percentMapsOntoTheDeviceSteps() {
        assertEquals(0, indexFor(0, 15))
        assertEquals(15, indexFor(100, 15))
        assertEquals(8, indexFor(50, 15))   // 7.5 rounds up
        assertEquals(4, indexFor(50, 7))    // 3.5 rounds up
    }

    @Test fun percentIsClampedRatherThanRejected() {
        assertEquals(0, indexFor(-20, 15))
        assertEquals(15, indexFor(500, 15))
    }

    @Test fun aRelativeStepNeverRoundsDownToNothing() {
        // The bug this guards: 10% of a 7-step stream is 0.7, which rounds to 0. "Turn it up"
        // would report success and move nothing, which is worse than failing.
        assertEquals(1, stepIndex(10, 7))
        assertEquals(1, stepIndex(1, 15))
        assertTrue("any step must move at least one notch", stepIndex(1, 100) >= 1)
    }

    @Test fun aRelativeStepScalesWithTheRange() {
        assertEquals(3, stepIndex(20, 15))
        assertEquals(8, stepIndex(50, 15))
    }

    @Test fun percentReportedBackIsTheInverse() {
        assertEquals(0, percentOf(0, 15))
        assertEquals(100, percentOf(15, 15))
        assertEquals(53, percentOf(8, 15))
    }

    @Test fun aStreamWithNoRangeDoesNotDivideByZero() {
        assertEquals(0, percentOf(0, 0))
    }
}
