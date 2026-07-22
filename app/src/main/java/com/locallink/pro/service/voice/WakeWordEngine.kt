package com.locallink.pro.service.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * On-device wake-word ("Hey Omni") via sherpa-onnx KeywordSpotter — reuses the same native
 * libs already bundled for Kokoro TTS (no new dependency, fully offline). Owns its own mic
 * (AudioRecord) on a background thread; calls [onWake] when the keyword is detected.
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
        private const val DIR = "kws"
    }

    var onWake: (() -> Unit)? = null

    @Volatile private var running = false
    private var spotter: KeywordSpotter? = null
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @Synchronized
    private fun ensureSpotter() {
        if (spotter != null) return
        val transducer = OnlineTransducerModelConfig(
            encoder = "$DIR/encoder.onnx",
            decoder = "$DIR/decoder.onnx",
            joiner = "$DIR/joiner.onnx",
        )
        val modelConfig = OnlineModelConfig(
            transducer = transducer,
            tokens = "$DIR/tokens.txt",
            numThreads = 1,
            provider = "cpu",
        )
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            keywordsFile = "$DIR/keywords.txt",
            // More sensitive than defaults so a short phrase like "Hey Omni" triggers.
            keywordsScore = 3.0f,       // boost keyword tokens
            keywordsThreshold = 0.10f,  // lower = easier to fire (default ~0.25)
        )
        spotter = KeywordSpotter(assetManager = context.assets, config = config)
        Log.d(TAG, "KeywordSpotter ready")
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
            ensureSpotter()
        } catch (e: Throwable) {
            Log.e(TAG, "ensureSpotter failed", e)
            return
        }
        val sp = spotter ?: return
        val stream = sp.createStream()

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
            val floats = FloatArray(minBuf)
            var reads = 0L
            var fired = false
            try {
                while (running) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (++reads % 20L == 0L) Log.d(TAG, "listening… ($reads reads)")
                    for (i in 0 until n) floats[i] = buf[i] / 32768f
                    stream.acceptWaveform(floats.copyOf(n), SAMPLE_RATE)
                    while (sp.isReady(stream)) {
                        sp.decode(stream)
                        val kw = sp.getResult(stream).keyword
                        if (kw.isNotEmpty()) {
                            Log.d(TAG, "wake detected: $kw")
                            sp.reset(stream)
                            running = false   // exit the read loop; mic released in finally
                            fired = true
                        }
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
                try { stream.release() } catch (_: Exception) {}
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
        try { spotter?.release() } catch (_: Exception) {}
        spotter = null
    }
}
