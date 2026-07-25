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
    private val settings: com.locallink.pro.data.local.SettingsPreferences,
) {
    companion object {
        private const val TAG = "WakeWordEngine"
        private const val SAMPLE_RATE = 16000
        private const val DIR = "kws"

        /**
         * Repeat mode: how much of the strict threshold the lenient pass uses. Lower = easier to
         * register a candidate. Deliberately well below the strict value — a lenient hit alone
         * never wakes anything, it only counts toward a repeat.
         */
        private const val LENIENT_FACTOR = 0.35f

        /** Two lenient hits inside this window read as a deliberate, repeated invocation. */
        private const val REPEAT_WINDOW_MS = 4_000L
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
            // Per-keyword :boost/#threshold in keywords.txt override these globals.
            keywordsScore = 3.5f,       // boost keyword tokens
            keywordsThreshold = 0.08f,  // lower = easier to fire (default ~0.25)
        )
        spotter = KeywordSpotter(assetManager = context.assets, config = config)
        Log.d(TAG, "KeywordSpotter ready")
    }

    /**
     * The bundled keyword spec re-emitted with every `#threshold` scaled by [LENIENT_FACTOR].
     *
     * Lines look like `▁HE Y ▁O M N I :3.5 #0.06 @HEY_OMNI`; only the `#` field is rewritten so
     * the phonetic tokens and boost stay exactly as tuned. Returns null if the asset can't be
     * read or parsed, which simply means repeat mode stays off.
     */
    private fun lenientKeywordSpec(): String? = runCatching {
        val lines = context.assets.open("$DIR/keywords.txt").bufferedReader().use { it.readLines() }
        val out = lines.mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            Regex("#([0-9]*\\.?[0-9]+)").find(line)?.let { m ->
                val relaxed = (m.groupValues[1].toFloat() * LENIENT_FACTOR).coerceAtLeast(0.005f)
                line.replaceRange(m.range, "#%.4f".format(relaxed))
            } ?: line
        }
        out.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }.onFailure { Log.w(TAG, "could not build lenient keyword spec", it) }.getOrNull()

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

        // Optional second pass at a much lower threshold. A hit here NEVER wakes on its own —
        // two hits inside REPEAT_WINDOW_MS do, which is what makes saying "Hey Omni" twice work
        // for a voice the strict threshold keeps missing. Strict single-shot waking is untouched.
        // Any failure (unreadable asset, spec the native side rejects) degrades to single-stream.
        val repeatMode = runCatching { kotlinx.coroutines.runBlocking { settings.loadWakeRepeat() } }
            .getOrDefault(false)
        val lenient = if (!repeatMode) null else lenientKeywordSpec()?.let { spec ->
            runCatching { sp.createStream(spec) }
                .onFailure { Log.w(TAG, "lenient stream rejected — repeat mode off", it) }
                .getOrNull()
        }
        if (lenient != null) Log.i(TAG, "repeat mode on (say \"Hey Omni\" twice)")

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
            var lastLenientHitAt = 0L
            try {
                while (running) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (++reads % 20L == 0L) Log.d(TAG, "listening… ($reads reads)")
                    for (i in 0 until n) floats[i] = buf[i] / 32768f
                    val chunk = floats.copyOf(n)
                    stream.acceptWaveform(chunk, SAMPLE_RATE)
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

                    if (lenient != null && running) {
                        lenient.acceptWaveform(chunk, SAMPLE_RATE)
                        while (sp.isReady(lenient)) {
                            sp.decode(lenient)
                            if (sp.getResult(lenient).keyword.isEmpty()) continue
                            sp.reset(lenient)
                            val now = System.currentTimeMillis()
                            if (now - lastLenientHitAt <= REPEAT_WINDOW_MS) {
                                Log.i(TAG, "wake on repeated attempt")
                                lastLenientHitAt = 0L
                                running = false
                                fired = true
                            } else {
                                // First candidate: remember it and wait to see if it's repeated.
                                lastLenientHitAt = now
                            }
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
