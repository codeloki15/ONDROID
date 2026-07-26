package com.locallink.pro.service.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KokoroTtsService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "KokoroTtsService"
        const val MODEL_DIR = "kokoro-en-v0_19"
        /**
         * How long the playback worker keeps the AudioTrack open waiting for the next
         * queued sentence. Long enough to bridge the gap while the LLM streams the next
         * sentence, short enough that the track doesn't linger after a reply ends.
         */
        private const val IDLE_TIMEOUT_MS = 2_500L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null

    /**
     * Pending utterances. A single long-lived worker drains this into ONE AudioTrack so
     * consecutive sentences play gaplessly — that is what lets us start speaking sentence 1
     * while the model is still generating sentence 3.
     */
    private val queue = Channel<String>(Channel.UNLIMITED)
    private var workerJob: Job? = null

    /** Jitter-buffer state: playback is held until enough audio is queued (see [synthesizeInto]). */
    @Volatile private var playbackStarted = false
    private var bufferedSamples = 0

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private var speed: Float = 1.0f
    private var speakerId: Int = 0 // default voice

    fun initialize() {
        scope.launch {
            try {
                copyAssetsIfNeeded()
                // The acoustic model is downloaded on demand; until it is, stay un-ready and let
                // VoiceService fall back to Android TTS rather than crashing on a missing file.
                val model = File(context.getExternalFilesDir(null), "$MODEL_DIR/model.onnx")
                if (!model.exists()) {
                    Log.i(TAG, "Kokoro model not downloaded yet — using system TTS until it is")
                    return@launch
                }
                initTts()
                warmUp()
                _isReady.value = true
                Log.d(TAG, "Kokoro TTS initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Kokoro TTS", e)
            }
        }
    }

    private fun initTts() {
        // Everything is addressed on the filesystem now, not through the AssetManager: the 330 MB
        // acoustic model is downloaded rather than bundled, so it has no asset path to load from.
        // Passing no assetManager makes sherpa treat these as ordinary file paths.
        val base = File(context.getExternalFilesDir(null), MODEL_DIR).absolutePath
        val dataDir = "$base/espeak-ng-data"

        val config = getOfflineTtsConfig(
            modelDir = base,
            modelName = "model.onnx",
            acousticModelName = "",
            vocoder = "",
            voices = "voices.bin",
            lexicon = "",
            dataDir = dataDir,
            dictDir = "",
            ruleFsts = "",
            ruleFars = "",
        )

        // Synthesis is the latency bottleneck and it is CPU-bound (sherpa's Android build ships
        // the CPU execution provider; there is no working GPU/NNAPI path for Kokoro). The helper
        // defaults to 4 threads regardless of the device, so scale with the actual core count —
        // capped, because spilling onto a big.LITTLE cluster's small cores costs more in
        // synchronisation than it wins in parallelism.
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        config.model.numThreads = threads

        tts = OfflineTts(config = config)
        Log.d(TAG, "TTS created with ${tts?.numSpeakers()} speakers, sample rate: " +
            "${tts?.sampleRate()}, threads=$threads")
    }

    /**
     * Run one throwaway synthesis so the ONNX graph pays its first-inference cost (memory
     * arena allocation, kernel selection) here instead of on the user's first real utterance.
     * The callback returns 0 on the very first chunk, so generation aborts immediately and no
     * audio is ever produced — we only want the warm-up, not the sound.
     */
    private fun warmUp() {
        val engine = tts ?: return
        val t0 = System.currentTimeMillis()
        runCatching {
            // Explicit Function1 object, never a lambda — sherpa's JNI resolves this callback by
            // its exact signature and a Kotlin lambda desugars to one the native side can't find.
            engine.generateWithCallback(
                "Hello.", speakerId, speed,
                object : Function1<FloatArray, Int> {
                    override fun invoke(samples: FloatArray): Int = 0 // abort after first chunk
                },
            )
        }.onFailure { Log.w(TAG, "TTS warm-up failed (harmless)", it) }
        Log.i(TAG, "TTS warm-up took ${System.currentTimeMillis() - t0}ms")
    }

    // Set by stopSpeaking(); the synthesis callback checks it and aborts generation.
    @Volatile private var stopRequested = false

    /**
     * Speak [text], replacing anything currently playing or queued (barge-in semantics —
     * what every one-shot caller expects). For streamed replies use [enqueue] instead.
     */
    fun speak(text: String) {
        if (tts == null) {
            Log.w(TAG, "TTS not initialized")
            return
        }
        stopSpeaking()
        enqueue(text)
    }

    /**
     * Append [text] to the playback queue WITHOUT interrupting what's already speaking.
     * Sentences enqueued back-to-back play gaplessly through a single AudioTrack, so the
     * reply can start being spoken while the model is still generating the rest of it.
     */
    fun enqueue(text: String) {
        if (tts == null) {
            Log.w(TAG, "TTS not initialized")
            return
        }
        if (text.isBlank()) return
        stopRequested = false
        ensureWorker()
        queue.trySend(text)
    }

    private fun ensureWorker() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch { runWorker() }
    }

    /**
     * Drains [queue] into one AudioTrack. The track is created on the first utterance and
     * kept open across sentences; it closes once the queue stays empty for [IDLE_TIMEOUT_MS],
     * which is what makes consecutive sentences seamless rather than restarting audio each time.
     */
    private suspend fun runWorker() {
        val engine = tts ?: return
        val sampleRate = engine.sampleRate()
        var track: AudioTrack? = null
        try {
            while (true) {
                val text = withTimeoutOrNull(IDLE_TIMEOUT_MS) { queue.receive() } ?: break
                if (stopRequested) break
                if (track == null) {
                    track = buildTrack(sampleRate)
                    audioTrack = track
                    // NOT play() yet — see primeSamples. Writing before play() buffers the data.
                    playbackStarted = false
                    bufferedSamples = 0
                }
                _isSpeaking.value = true
                synthesizeInto(track, engine, text, primeSamples = sampleRate / 2)
                // Utterance finished without reaching the prime threshold (a short sentence) —
                // start playback now rather than holding it back waiting for audio that won't come.
                if (!playbackStarted) {
                    runCatching { track.play() }
                    playbackStarted = true
                }
                if (stopRequested) break
            }
            // Let the buffered tail drain before tearing the track down.
            if (!stopRequested) runCatching { track?.stop() }
        } catch (e: Exception) {
            Log.e(TAG, "Error during TTS playback", e)
        } finally {
            runCatching { audioTrack?.release() }
            audioTrack = null
            _isSpeaking.value = false
        }
    }

    private fun buildTrack(sampleRate: Int): AudioTrack {
        // Headroom, in seconds of audio, between the synthesizer and the speaker.
        //
        // This is the lookahead budget. track.write() is WRITE_BLOCKING, so the buffer size is
        // what decides how far generation may run AHEAD of playback: with only ~1s of headroom
        // the engine is throttled to real time and the next sentence barely starts generating
        // before the current one ends, leaving an audible gap between them. With several seconds
        // the engine generates sentence N+1 while N is still playing, so playback is continuous.
        // At 24kHz float-mono this costs 24000 * 4 bytes per second — a few hundred KB, cheap.
        val headroomSeconds = 8
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(sampleRate * Float.SIZE_BYTES * headroomSeconds)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * Synthesize one utterance straight into [track]; blocks until it's generated.
     *
     * Playback does not begin until [primeSamples] frames are buffered. Kokoro generates faster
     * than real time on average but not uniformly, so starting the speaker on the very first
     * chunk let the track drain mid-sentence and the device logged repeated
     * "disabled due to previous underrun" — audible stutter. This is a jitter buffer.
     */
    private suspend fun synthesizeInto(
        track: AudioTrack,
        engine: OfflineTts,
        text: String,
        primeSamples: Int,
    ) {
        val t0 = System.currentTimeMillis()
        var first = true
        // STREAMING synthesis: the engine emits audio per segment as it generates;
        // each chunk is written to the live AudioTrack immediately, so speech starts
        // after the FIRST segment instead of after the whole reply is synthesized.
        // WRITE_BLOCKING doubles as backpressure. Return 1 = keep generating, 0 = abort.
        //
        // MUST be an explicit Function1 object, NOT a lambda: sherpa's JNI resolves the
        // callback by exact signature invoke([F)Ljava/lang/Integer;. Kotlin 2.x lambdas
        // compile via invokedynamic → D8 synthesizes a class with only the erased
        // invoke(Object)Object, and the native lookup SIGABRTs (NoSuchMethodError).
        val job = kotlin.coroutines.coroutineContext[Job]
        val onSamples = object : Function1<FloatArray, Int> {
            override fun invoke(samples: FloatArray): Int {
                if (stopRequested || job?.isActive == false) return 0
                if (first) { first = false; Log.i(TAG, "first audio in ${System.currentTimeMillis() - t0}ms") }
                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                if (!playbackStarted) {
                    bufferedSamples += samples.size
                    if (bufferedSamples >= primeSamples) {
                        track.play()
                        playbackStarted = true
                    }
                }
                return 1
            }
        }
        engine.generateWithCallback(text, speakerId, speed, onSamples)
    }

    fun stopSpeaking() {
        stopRequested = true
        workerJob?.cancel()
        workerJob = null
        // Drop anything still queued, or it would play the moment a new worker starts.
        while (queue.tryReceive().isSuccess) { /* discard */ }
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        _isSpeaking.value = false
    }

    fun setSpeed(newSpeed: Float) {
        speed = newSpeed.coerceIn(0.5f, 2.0f)
    }

    fun setSpeakerId(id: Int) {
        speakerId = id
    }

    fun getNumSpeakers(): Int {
        return tts?.numSpeakers() ?: 0
    }

    fun shutdown() {
        stopSpeaking()
        tts?.free()
        tts = null
        _isReady.value = false
    }

    private fun copyAssetsIfNeeded() {
        val externalDir = context.getExternalFilesDir(null) ?: return
        val modelDirFile = File(externalDir, MODEL_DIR)

        // Check if espeak-ng-data already copied
        val espeakDir = File(modelDirFile, "espeak-ng-data")
        if (espeakDir.exists() && espeakDir.listFiles()?.isNotEmpty() == true) {
            Log.d(TAG, "Model data already copied")
            return
        }

        Log.d(TAG, "Copying espeak-ng-data from assets...")
        copyAssetDir(MODEL_DIR)
    }

    private fun copyAssetDir(path: String) {
        val assets = context.assets.list(path) ?: return
        if (assets.isEmpty()) {
            // It's a file, copy it
            copyAssetFile(path)
        } else {
            // It's a directory
            val dir = File(context.getExternalFilesDir(null), path)
            dir.mkdirs()
            for (child in assets) {
                copyAssetDir("$path/$child")
            }
        }
    }

    private fun copyAssetFile(filename: String) {
        try {
            val outFile = File(context.getExternalFilesDir(null), filename)
            if (outFile.exists()) return
            context.assets.open(filename).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset: $filename", e)
        }
    }
}
