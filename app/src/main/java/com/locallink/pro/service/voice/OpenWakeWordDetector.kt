package com.locallink.pro.service.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.util.Log
import java.nio.FloatBuffer

/**
 * "Hey Omni" detection via a purpose-trained openWakeWord model (see
 * `tools/wakeword-training/`). Replaces the generic sherpa-onnx KeywordSpotter, which
 * scored a phonetic transcription against hand-tuned thresholds and was therefore tuned
 * for no particular voice.
 *
 * Three ONNX models run in series, which is openWakeWord's architecture:
 *
 *   audio ──▶ melspectrogram ──▶ speech embedding ──▶ hey_omni classifier ──▶ score
 *            (80ms → 8 frames)   (76 frames → 96-d)   (16 embeddings → 0..1)
 *
 * The first two are Google's fixed feature extractors, shipped verbatim by openWakeWord;
 * only the last is ours. This class is a direct port of `openwakeword.utils.AudioFeatures`
 * streaming path — the numerical details below are load-bearing, not incidental:
 *
 *  - Audio is fed as **int16 sample values widened to float** (±32768), NOT normalised to
 *    ±1.0. The melspectrogram model was traced on raw PCM magnitudes; dividing by 32768
 *    first shifts every mel bin by ~log(32768) and the classifier never fires.
 *  - The melspectrogram is post-scaled by `x/10 + 2`, which is what upstream uses to line
 *    the ONNX export up with Google's original TensorFlow implementation.
 *  - Each 1280-sample (80 ms) chunk is turned into mel frames together with 480 samples of
 *    preceding context (`n + 480` in, `n/160 - 3` frames out — so exactly 8 new frames).
 *
 * Not thread-safe: [WakeWordEngine] drives it from its single mic worker thread.
 */
class OpenWakeWordDetector(
    melspecModel: ByteArray,
    embeddingModel: ByteArray,
    wakeModel: ByteArray,
    /** Score above which we call it a wake. openWakeWord's general-purpose default is 0.5. */
    private val threshold: Float = DEFAULT_THRESHOLD,
) : WakeWordDetector {

    companion object {
        private const val TAG = "OpenWakeWord"
        const val DEFAULT_THRESHOLD = 0.5f

        private const val DIR = "oww"
        private const val MELSPEC_MODEL = "$DIR/melspectrogram.onnx"
        private const val EMBEDDING_MODEL = "$DIR/embedding_model.onnx"
        private const val WAKE_MODEL = "$DIR/hey_omni.onnx"

        /**
         * Models come in as bytes rather than an [AssetManager] handle so the whole pipeline
         * can be exercised by a plain JVM test against the Python reference scores — the
         * numerical details in this class fail silently when wrong, so they need a test that
         * runs the real models.
         */
        fun fromAssets(assets: AssetManager, threshold: Float = DEFAULT_THRESHOLD) =
            OpenWakeWordDetector(
                melspecModel = assets.open(MELSPEC_MODEL).use { it.readBytes() },
                embeddingModel = assets.open(EMBEDDING_MODEL).use { it.readBytes() },
                wakeModel = assets.open(WAKE_MODEL).use { it.readBytes() },
                threshold = threshold,
            )

        /** 80 ms at 16 kHz — openWakeWord's fundamental step. */
        private const val CHUNK = 1280
        /** 160*3 samples of lookback so the mel windows at a chunk boundary have context. */
        private const val MEL_CONTEXT = 480
        private const val MEL_BINS = 32
        /** Mel frames per embedding window, and the stride between consecutive windows. */
        private const val MEL_WINDOW = 76
        private const val MEL_STRIDE = 8
        private const val EMBED_DIM = 96

        /** ~10 s of history, matching upstream's buffer caps. */
        private const val MEL_MAX_ROWS = 10 * 97
        private const val FEATURE_MAX_ROWS = 120
        private const val RAW_CAPACITY = 16000 * 10
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val melspec: OrtSession
    private val embedding: OrtSession
    private val classifier: OrtSession

    /** Feature frames the classifier consumes — read from the model, not assumed. */
    private val featureFrames: Int

    init {
        val opts = OrtSession.SessionOptions().apply {
            // One thread each: this runs continuously in the background, and the models are
            // small enough that extra threads cost more in wakeups than they save in latency.
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }
        melspec = env.createSession(melspecModel, opts)
        embedding = env.createSession(embeddingModel, opts)
        classifier = env.createSession(wakeModel, opts)

        // The classifier's window is decided at training time by the median clip duration,
        // so read it back rather than hardcoding 16 and silently misfeeding a retrained model.
        val shape = classifier.inputInfo.values.first().info.let {
            (it as ai.onnxruntime.TensorInfo).shape
        }
        featureFrames = shape.getOrNull(1)?.takeIf { it > 0 }?.toInt() ?: 16
        Log.d(TAG, "ready — classifier window ${featureFrames}x$EMBED_DIM, threshold $threshold")
    }

    private val melspecInput = melspec.inputNames.first()
    private val embeddingInput = embedding.inputNames.first()
    private val classifierInput = classifier.inputNames.first()

    // ── streaming state ─────────────────────────────────────────────────────────
    private val raw = FloatArray(RAW_CAPACITY)
    private var rawWrite = 0
    private var rawFilled = 0

    /** Samples held back when a block doesn't divide evenly into 80 ms chunks. */
    private var remainder = FloatArray(0)
    private var accumulated = 0

    private val mel = FloatArray(MEL_MAX_ROWS * MEL_BINS)
    private var melRows = 0

    private val features = FloatArray(FEATURE_MAX_ROWS * EMBED_DIM)
    private var featureRows = 0

    /** Embeddings computed from real audio since [reset] — gates scoring during warmup. */
    private var realFeatureRows = 0

    private var refractory = 0

    /**
     * Score of the most recent complete 80 ms chunk, 0..1. Exposed for the port's
     * agreement test against the Python reference, and useful when tuning [threshold].
     */
    @Volatile var lastScore: Float = 0f
        private set

    init { primeMelBuffer() }

    /**
     * Upstream seeds the mel buffer with `np.ones((76, 32))` so the very first window is
     * full-length. Without it the first embedding is computed from a short window and the
     * whole feature timeline shifts.
     */
    private fun primeMelBuffer() {
        melRows = 0
        java.util.Arrays.fill(mel, 0, MEL_WINDOW * MEL_BINS, 1f)
        melRows = MEL_WINDOW
    }

    override fun accept(pcm: ShortArray, n: Int): Boolean {
        // Widen to float WITHOUT normalising — see the class comment.
        var block = FloatArray(n) { pcm[it].toFloat() }
        if (remainder.isNotEmpty()) {
            block = remainder + block
            remainder = FloatArray(0)
        }

        if (accumulated + block.size >= CHUNK) {
            val rem = (accumulated + block.size) % CHUNK
            if (rem != 0) {
                val even = block.copyOfRange(0, block.size - rem)
                appendRaw(even, even.size)
                accumulated += even.size
                remainder = block.copyOfRange(block.size - rem, block.size)
            } else {
                appendRaw(block, block.size)
                accumulated += block.size
            }
        } else {
            appendRaw(block, block.size)
            accumulated += block.size
            return false
        }

        if (accumulated < CHUNK || accumulated % CHUNK != 0) return false

        val chunks = accumulated / CHUNK
        streamingMelspectrogram(accumulated)

        // One embedding per 80 ms chunk, oldest window first, so the feature timeline stays
        // ordered when a single read block covered several chunks.
        for (i in chunks - 1 downTo 0) {
            val end = melRows - MEL_STRIDE * i
            val start = end - MEL_WINDOW
            if (start < 0) continue
            pushFeature(embedWindow(start))
        }
        accumulated = 0

        return score()
    }

    /** Mel frames for the newest [nSamples], computed with [MEL_CONTEXT] samples of lookback. */
    private fun streamingMelspectrogram(nSamples: Int) {
        val want = nSamples + MEL_CONTEXT
        val take = minOf(want, rawFilled)
        if (take < 400) return  // the model's Conv needs at least a 25 ms window
        val audio = lastRaw(take)

        val out = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong()),
        ).use { t ->
            melspec.run(mapOf(melspecInput to t)).use { r ->
                val tensor = r[0] as OnnxTensor
                val buf = tensor.floatBuffer
                FloatArray(buf.remaining()).also { buf.get(it) }
            }
        }

        // Output is [1, 1, frames, 32]; the scaling aligns this export with Google's original.
        var i = 0
        while (i + MEL_BINS <= out.size) {
            val row = FloatArray(MEL_BINS) { out[i + it] / 10f + 2f }
            pushMelRow(row)
            i += MEL_BINS
        }
    }

    /**
     * Embed one 76-frame mel window.
     *
     * The window is submitted TWICE, as a batch of 2, and only the first row is used. This
     * is not an optimisation — it is a correctness workaround. Under onnxruntime the
     * embedding model returns materially different values for a batch of 1 than for any
     * batch >= 2 (differences of ~20 on individual dimensions), and the batch >= 2 answer is
     * the correct one: it matches what openWakeWord's *batch* feature path produces, which
     * is what every classifier — ours and the pretrained ones — was trained on.
     *
     * Streaming inference naturally wants a batch of 1, which is exactly the broken case.
     * This is why openWakeWord's own ONNX streaming path scores ~0 on its own test audio
     * while its batch path scores 1.0. Verified against `alexa_test.wav` and
     * `hey_mycroft_test.wav`: 0.0000 with batch 1, 1.0000 with batch 2.
     */
    private fun embedWindow(startRow: Int): FloatArray {
        val input = FloatArray(2 * MEL_WINDOW * MEL_BINS)
        for (r in 0 until MEL_WINDOW) {
            System.arraycopy(mel, (startRow + r) * MEL_BINS, input, r * MEL_BINS, MEL_BINS)
        }
        System.arraycopy(input, 0, input, MEL_WINDOW * MEL_BINS, MEL_WINDOW * MEL_BINS)
        return OnnxTensor.createTensor(
            env, FloatBuffer.wrap(input), longArrayOf(2, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1),
        ).use { t ->
            embedding.run(mapOf(embeddingInput to t)).use { r ->
                val buf = (r[0] as OnnxTensor).floatBuffer
                FloatArray(EMBED_DIM).also { buf.get(it, 0, EMBED_DIM) }
            }
        }
    }

    private fun score(): Boolean {
        if (realFeatureRows < featureFrames) return false  // still warming up
        if (refractory > 0) { refractory--; return false }

        val input = FloatArray(featureFrames * EMBED_DIM)
        val first = featureRows - featureFrames
        for (r in 0 until featureFrames) {
            System.arraycopy(features, (first + r) * EMBED_DIM, input, r * EMBED_DIM, EMBED_DIM)
        }

        val p = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(input), longArrayOf(1, featureFrames.toLong(), EMBED_DIM.toLong()),
        ).use { t ->
            classifier.run(mapOf(classifierInput to t)).use { r ->
                (r[0] as OnnxTensor).floatBuffer.get(0)
            }
        }

        lastScore = p
        if (p >= threshold) {
            Log.d(TAG, "wake detected (score=$p)")
            // Don't score again until the phrase has passed, or one utterance fires repeatedly.
            refractory = featureFrames
            return true
        }
        return false
    }

    override fun reset() {
        rawWrite = 0; rawFilled = 0
        remainder = FloatArray(0)
        accumulated = 0
        featureRows = 0; realFeatureRows = 0
        refractory = 0
        lastScore = 0f
        primeMelBuffer()
    }

    override fun close() {
        runCatching { melspec.close() }
        runCatching { embedding.close() }
        runCatching { classifier.close() }
    }

    // ── ring buffers ────────────────────────────────────────────────────────────

    private fun appendRaw(src: FloatArray, n: Int) {
        var off = 0
        var left = n
        while (left > 0) {
            val room = RAW_CAPACITY - rawWrite
            val take = minOf(room, left)
            System.arraycopy(src, off, raw, rawWrite, take)
            rawWrite = (rawWrite + take) % RAW_CAPACITY
            off += take; left -= take
        }
        rawFilled = minOf(rawFilled + n, RAW_CAPACITY)
    }

    private fun lastRaw(n: Int): FloatArray {
        val out = FloatArray(n)
        var start = rawWrite - n
        if (start < 0) start += RAW_CAPACITY
        val firstRun = minOf(n, RAW_CAPACITY - start)
        System.arraycopy(raw, start, out, 0, firstRun)
        if (firstRun < n) System.arraycopy(raw, 0, out, firstRun, n - firstRun)
        return out
    }

    /** Both feature stores are compacting arrays: cheap to append, cheap to slice a tail from. */
    private fun pushMelRow(row: FloatArray) {
        if (melRows == MEL_MAX_ROWS) {
            System.arraycopy(mel, MEL_BINS, mel, 0, (MEL_MAX_ROWS - 1) * MEL_BINS)
            melRows--
        }
        System.arraycopy(row, 0, mel, melRows * MEL_BINS, MEL_BINS)
        melRows++
    }

    private fun pushFeature(row: FloatArray) {
        if (featureRows == FEATURE_MAX_ROWS) {
            System.arraycopy(features, EMBED_DIM, features, 0, (FEATURE_MAX_ROWS - 1) * EMBED_DIM)
            featureRows--
        }
        System.arraycopy(row, 0, features, featureRows * EMBED_DIM, EMBED_DIM)
        featureRows++
        realFeatureRows++
    }
}
