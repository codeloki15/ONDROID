package com.locallink.pro.service.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * Fully offline speech-to-text: NVIDIA parakeet-tdt-0.6b-v3 (25-language) running through
 * the sherpa-onnx runtime already bundled for KWS/TTS. Endpointing is silero-VAD (assets);
 * on end-of-speech the captured utterance is decoded once (TDT is an offline transducer).
 *
 * Mirrors the SpeechRecognizer contract used by [VoiceService]: single-shot capture with
 * onFinal/onError, mic exclusive, [stop] decodes whatever was said so far.
 */
@Singleton
class ParakeetSttEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "ParakeetStt"
        private const val SAMPLE_RATE = 16000
        private const val VAD_WINDOW = 512              // silero v5 frame
        private const val NO_SPEECH_TIMEOUT_MS = 8000L  // give up if the user never speaks
        private const val MAX_UTTERANCE_S = 20f
        const val MODEL_DIR = "parakeet-tdt-v3"

        val MODEL_FILES = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
    }

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    @Volatile private var running = false
    @Volatile private var cancelled = false
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    fun modelDir(): File = File(context.getExternalFilesDir(null), MODEL_DIR)

    /** All model files present (encoder sanity-checked by size — a partial download won't load). */
    fun isModelPresent(): Boolean {
        val dir = modelDir()
        return MODEL_FILES.all { File(dir, it).exists() } &&
            File(dir, "encoder.int8.onnx").length() > 500_000_000L
    }

    val isLoaded: Boolean get() = recognizer != null

    /** Load the recognizer + VAD (slow: ~650 MB of weights). Call off the main thread. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (recognizer != null) return true
        if (!isModelPresent()) return false
        return try {
            val dir = modelDir().absolutePath
            val t0 = System.currentTimeMillis()
            recognizer = OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = "$dir/encoder.int8.onnx",
                            decoder = "$dir/decoder.int8.onnx",
                            joiner = "$dir/joiner.int8.onnx",
                        ),
                        tokens = "$dir/tokens.txt",
                        modelType = "nemo_transducer",
                        numThreads = 4,
                        provider = "cpu",
                    ),
                ),
            )
            Log.i(TAG, "parakeet loaded in ${System.currentTimeMillis() - t0}ms")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "failed to load parakeet", e)
            recognizer = null
            false
        }
    }

    @Synchronized
    private fun ensureVad(): Vad? {
        vad?.let { return it }
        return try {
            Vad(
                assetManager = context.assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "vad/silero_vad.onnx",
                        threshold = 0.45f,          // slightly permissive so quiet onsets aren't clipped
                        minSilenceDuration = 0.85f, // longer tail so mid-sentence pauses don't split
                        minSpeechDuration = 0.25f,
                        windowSize = VAD_WINDOW,
                        maxSpeechDuration = MAX_UTTERANCE_S,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                ),
            ).also { vad = it }
        } catch (e: Throwable) {
            Log.e(TAG, "failed to load silero vad", e)
            null
        }
    }

    /**
     * Single-shot capture: opens the mic, waits for one utterance (VAD-endpointed), decodes
     * it and calls [onFinal]. [onError] fires on silence timeout / mic failure. Exactly one
     * of the two callbacks fires per start.
     */
    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO granted
    @Synchronized
    fun start(
        onSpeechStart: () -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (running) return false
        val rec = recognizer ?: run { onError("On-device model not loaded"); return false }
        val v = ensureVad() ?: run { onError("VAD unavailable"); return false }
        v.reset()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(SAMPLE_RATE / 4)
        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2,
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release(); onError("Microphone busy"); return false
        }
        record = ar
        running = true
        cancelled = false
        ar.startRecording()

        worker = thread(name = "parakeet-stt", isDaemon = true) {
            val buf = ShortArray(VAD_WINDOW)
            val window = FloatArray(VAD_WINDOW)
            val utterance = ArrayList<Float>(SAMPLE_RATE * 10)
            var sawSpeech = false
            var announcedSpeech = false
            val startedAt = System.currentTimeMillis()
            var outcome: (() -> Unit)? = null
            try {
                loop@ while (running) {
                    var filled = 0
                    while (filled < VAD_WINDOW && running) {
                        val n = ar.read(buf, filled, VAD_WINDOW - filled)
                        if (n <= 0) continue@loop
                        filled += n
                    }
                    if (!running) break
                    for (i in 0 until VAD_WINDOW) window[i] = buf[i] / 32768f
                    v.acceptWaveform(window.copyOf())

                    if (v.isSpeechDetected()) {
                        sawSpeech = true
                        if (!announcedSpeech) { announcedSpeech = true; onSpeechStart() }
                    }
                    // Completed speech segments become the utterance.
                    while (!v.empty()) {
                        utterance.addAll(v.front().samples.toList())
                        v.pop()
                    }
                    // End of utterance: we had speech, VAD went quiet, and segment(s) were flushed.
                    if (sawSpeech && !v.isSpeechDetected() && utterance.isNotEmpty()) {
                        running = false
                        val text = decode(rec, utterance.toFloatArray())
                        outcome = if (text.isBlank()) ({ onError("No speech recognized") }) else ({ onFinal(text) })
                        break
                    }
                    if (!sawSpeech && System.currentTimeMillis() - startedAt > NO_SPEECH_TIMEOUT_MS) {
                        running = false
                        outcome = { onError("Speech timeout") }
                        break
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "capture loop failed", e)
                if (outcome == null && !cancelled) outcome = { onError("Recognition failed: ${e.message}") }
            } finally {
                try { ar.stop() } catch (_: Exception) {}
                try { ar.release() } catch (_: Exception) {}
                synchronized(this@ParakeetSttEngine) { if (record === ar) record = null }
                running = false
                // Fire the outcome only after the mic is fully released, so callers can
                // immediately hand the mic to TTS / the next capture. A [stop] (cancel)
                // fires nothing — matching SpeechRecognizer.destroy() semantics.
                if (!cancelled) outcome?.invoke()
            }
        }
        return true
    }

    private fun decode(rec: OfflineRecognizer, samples: FloatArray): String {
        val t0 = System.currentTimeMillis()
        val stream = rec.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            val text = rec.getResult(stream).text.trim()
            Log.i(TAG, "decoded ${samples.size / SAMPLE_RATE}s in ${System.currentTimeMillis() - t0}ms: $text")
            text
        } catch (e: Throwable) {
            Log.e(TAG, "decode failed", e)
            ""
        } finally {
            runCatching { stream.release() }
        }
    }

    /**
     * Cancel the capture: no callback fires, buffered audio is dropped. Non-blocking —
     * the worker notices within one VAD frame (~32 ms) and releases the mic itself.
     */
    fun stop() {
        synchronized(this) {
            if (!running && worker == null) return
            cancelled = true
            running = false
            worker = null
        }
    }

    /** Free the native models. Blocks briefly for the worker (call off the main thread). */
    fun release() {
        val t: Thread? = synchronized(this) { worker }
        stop()
        if (t != null && t !== Thread.currentThread()) {
            try { t.join(4000) } catch (_: InterruptedException) {}
        }
        synchronized(this) {
            runCatching { recognizer?.release() }; recognizer = null
            runCatching { vad?.release() }; vad = null
        }
    }
}
