package com.locallink.pro.service.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * On-device wake-word ("Hey Omni"), fully offline. Owns its own mic (AudioRecord) on a
 * background thread; calls [onWake] when the wake word is detected.
 *
 * The detection itself lives behind [WakeWordDetector] — currently
 * [OpenWakeWordDetector], a model trained specifically on this phrase. This class stays
 * responsible only for the mic, because that part is where the constraints are:
 *
 * IMPORTANT: the mic must be exclusive — [VoiceLoopController] stops this before STT/TTS.
 */
@Singleton
class WakeWordEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "WakeWordEngine"
        private const val SAMPLE_RATE = 16000
    }

    var onWake: (() -> Unit)? = null

    @Volatile private var running = false
    private var detector: WakeWordDetector? = null
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @Synchronized
    private fun ensureDetector() {
        if (detector != null) return
        detector = OpenWakeWordDetector.fromAssets(context.assets)
        Log.d(TAG, "detector ready")
    }


    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO granted
    @Synchronized
    fun start() {
        if (running) return
        // Defensive: make sure no previous worker is still draining the mic before we open it
        // again. Without this, a just-finished turn's thread can race the new one → mic contention.
        worker?.let { old ->
            if (old !== Thread.currentThread()) { try { old.join(800) } catch (_: InterruptedException) {} }
        }
        worker = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        try {
            ensureDetector()
        } catch (e: Throwable) {
            Log.e(TAG, "ensureDetector failed", e)
            return
        }
        val det = detector ?: return
        det.reset()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(SAMPLE_RATE / 4)
        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2,
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed"); ar.release(); return
        }
        record = ar
        running = true
        ar.startRecording()

        worker = thread(name = "wakeword", isDaemon = true) {
            val buf = ShortArray(minBuf)
            var reads = 0L
            var fired = false
            try {
                while (running) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (++reads % 20L == 0L) Log.d(TAG, "listening… ($reads reads)")
                    if (det.accept(buf, n)) {
                        running = false   // exit the read loop; mic released in finally
                        fired = true
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "wake loop error", e)
            } finally {
                // This worker owns the mic — release it HERE so it's gone before STT starts.
                // Do NOT take the instance lock here: stop()/start() may be holding it while
                // join()-ing this very thread, which would deadlock. They null out `record`.
                try { ar.stop() } catch (_: Exception) {}
                try { ar.release() } catch (_: Exception) {}
                // Notify only after the mic is fully released, so STT opens a free mic.
                if (fired) onWake?.invoke()
            }
        }
    }

    /**
     * Stop listening and BLOCK until the worker thread has exited and released the mic.
     * Must be synchronous: STT/TTS grab the same mic immediately after, so a lingering
     * AudioRecord here causes "No speech recognized" (mic contention).
     */
    fun stop() {
        val t: Thread?
        synchronized(this) {
            running = false
            t = worker
            worker = null
        }
        // Join OUTSIDE the lock (the worker's finally takes the lock to clear `record`).
        if (t != null && t !== Thread.currentThread()) {
            try { t.join(800) } catch (_: InterruptedException) {}
        }
        // Safety net if the worker never started/owned the record.
        synchronized(this) {
            try { record?.stop() } catch (_: Exception) {}
            try { record?.release() } catch (_: Exception) {}
            record = null
        }
    }

    fun shutdown() {
        stop()
        try { detector?.close() } catch (_: Exception) {}
        detector = null
    }
}
