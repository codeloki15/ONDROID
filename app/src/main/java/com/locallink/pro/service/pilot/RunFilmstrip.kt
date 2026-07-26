package com.locallink.pro.service.pilot

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.locallink.pro.service.llm.AgentEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** One captured moment of a run: a small JPEG plus what the pilot had just done. */
data class Frame(val jpeg: ByteArray, val caption: String) {
    // ByteArray in a data class gives identity equals/hashCode, which is wrong and lint-worthy.
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * Collects a strip of screenshots while an automation runs, so the chat can show what happened
 * instead of only saying it.
 *
 * Capture is fire-and-forget on its own scope: a frame is a nicety, and the pilot loop must never
 * wait on one. Frames are held as small JPEGs rather than Bitmaps — two dozen ARGB_8888 screens
 * is tens of megabytes, the same run as JPEGs is about one.
 */
@Singleton
class RunFilmstrip @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "RunFilmstrip"
        /** Enough to tell the story; past this the recap is longer than the task was. */
        private const val MAX_FRAMES = 24
        /** The platform throttles accessibility screenshots to roughly one a second. */
        private const val MIN_GAP_MS = 900L
        /** Frames are for a phone-sized recap, not forensics. */
        private const val TARGET_WIDTH = 360
        private const val JPEG_QUALITY = 70
        /** Let the screen finish its transition before photographing it. */
        private const val AFTER_ACTION_MS = 450L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /**
     * A plain monitor, not a coroutine Mutex.
     *
     * begin() used to flip `recording` inside a launched coroutine, which meant the flag was set
     * asynchronously while events were already arriving — every event lost the race and the strip
     * stayed empty for a whole run with no sign of why. Nothing held here suspends (the capture
     * itself happens outside), so there is no reason for it to be a suspending lock.
     */
    private val listLock = Any()
    private val frames = ArrayList<Frame>()
    @Volatile private var recording = false
    @Volatile private var lastCaptureAt = 0L
    @Volatile private var dropped = 0

    fun begin() = synchronized(listLock) {
        frames.clear(); dropped = 0; lastCaptureAt = 0L; recording = true
    }

    /** Feed the run's events; the interesting moments capture themselves. */
    fun onEvent(event: AgentEvent) {
        if (!recording) return
        // The screen a step LANDED on is the one worth keeping — a frame taken before the action
        // just shows the same thing as the previous frame.
        val caption = when (event) {
            is AgentEvent.ToolResult -> if (event.success) event.result else null
            else -> null
        } ?: return
        scope.launch { snap(caption) }
    }

    private suspend fun snap(caption: String) {
        val svc = OmniAccessibilityService.instance ?: run {
            Log.d(TAG, "snap: no accessibility service"); return
        }
        val now = System.currentTimeMillis()
        if (now - lastCaptureAt < MIN_GAP_MS) { dropped++; return }
        lastCaptureAt = now
        delay(AFTER_ACTION_MS)
        val bmp = svc.captureScreen() ?: run {
            Log.d(TAG, "capture unavailable")
            return
        }
        val jpeg = shrink(bmp)
        bmp.recycle()
        synchronized(listLock) {
            if (!recording) return
            if (frames.size >= MAX_FRAMES) { dropped++; return }
            frames.add(Frame(jpeg, caption))
            Log.d(TAG, "frame ${frames.size} (${jpeg.size}B) — $caption")
        }
    }

    /** Stop recording and hand back what was captured, oldest first. */
    fun finish(): List<Frame> = synchronized(listLock) {
        recording = false
        if (dropped > 0) Log.d(TAG, "$dropped frame(s) not kept (throttled or over cap)")
        ArrayList(frames)
    }

    private fun shrink(src: Bitmap): ByteArray {
        val scale = TARGET_WIDTH.toFloat() / src.width
        val out = if (scale >= 1f) src else Bitmap.createScaledBitmap(
            src, TARGET_WIDTH, (src.height * scale).toInt().coerceAtLeast(1), true,
        )
        val bytes = ByteArrayOutputStream().use { s ->
            out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, s); s.toByteArray()
        }
        if (out !== src) out.recycle()
        return bytes
    }
}
