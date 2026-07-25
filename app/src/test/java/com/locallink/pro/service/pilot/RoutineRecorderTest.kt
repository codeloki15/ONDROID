package com.locallink.pro.service.pilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the recorder's filtering rules, which is where a taught routine quietly becomes
 * unreplayable. The launcher case is not hypothetical: a taught Amazon routine failed on its
 * first step because opening the app by tapping its home-screen icon had been recorded as a tap
 * on `com.android.launcher:id/icon`, a target that cannot exist when the routine runs.
 *
 * The recorder reads live AccessibilityEvents, which can't be constructed in a JVM test, so
 * these exercise the decision functions behind it rather than the event plumbing.
 */
class RoutineRecorderTest {

    private val recorder = RoutineRecorder()

    private fun isRecordable(pkg: String): Boolean {
        val m = RoutineRecorder::class.java.getDeclaredMethod("looksLikeApp", String::class.java)
        m.isAccessible = true
        return m.invoke(recorder, pkg) as Boolean
    }

    @Test fun launcherIsNeverPartOfARoutine() {
        // The exact package behind the observed failure.
        assertEquals(false, isRecordable("com.android.launcher"))
        assertEquals(false, isRecordable("com.oneplus.launcher"))
        assertEquals(false, isRecordable("com.google.android.apps.nexuslauncher"))
    }

    @Test fun systemUiAndKeyboardsAreNotSteps() {
        assertEquals(false, isRecordable("com.android.systemui"))
        assertEquals(false, isRecordable("com.google.android.inputmethod.latin"))
    }

    @Test fun realAppsAreRecordable() {
        assertEquals(true, isRecordable("in.amazon.mShop.android.shopping"))
        assertEquals(true, isRecordable("com.google.android.gm"))
        assertEquals(true, isRecordable("com.oneplus.deskclock"))
    }

    @Test fun startClearsAnyPreviousRecording() {
        recorder.start("com.locallink.pro", "first")
        recorder.start("com.locallink.pro", "second")

        assertEquals("second", recorder.pendingName.value)
        assertTrue(recorder.steps.value.isEmpty())
        assertTrue(recorder.isRecording.value)
    }

    @Test fun stopEndsRecordingButKeepsWhatWasCaptured() {
        recorder.start("com.locallink.pro", "routine")
        val captured = recorder.stop()

        assertEquals(false, recorder.isRecording.value)
        assertEquals(emptyList<TraceStep>(), captured)
    }

    @Test fun cancelDiscardsEverythingIncludingTheName() {
        recorder.start("com.locallink.pro", "routine")
        recorder.cancel()

        assertEquals("", recorder.pendingName.value)
        assertTrue(recorder.steps.value.isEmpty())
        assertEquals(false, recorder.isRecording.value)
    }
}
