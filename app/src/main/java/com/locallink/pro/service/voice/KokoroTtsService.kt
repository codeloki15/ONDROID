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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
        private const val MODEL_DIR = "kokoro-en-v0_19"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null

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
                initTts()
                _isReady.value = true
                Log.d(TAG, "Kokoro TTS initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Kokoro TTS", e)
            }
        }
    }

    private fun initTts() {
        val dataDir = File(context.getExternalFilesDir(null), "$MODEL_DIR/espeak-ng-data").absolutePath

        val config = getOfflineTtsConfig(
            modelDir = MODEL_DIR,
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

        tts = OfflineTts(assetManager = context.assets, config = config)
        Log.d(TAG, "TTS created with ${tts?.numSpeakers()} speakers, sample rate: ${tts?.sampleRate()}")
    }

    // Set by stopSpeaking(); the synthesis callback checks it and aborts generation.
    @Volatile private var stopRequested = false

    fun speak(text: String) {
        if (tts == null) {
            Log.w(TAG, "TTS not initialized")
            return
        }

        // Stop any current playback
        stopSpeaking()
        stopRequested = false

        playbackJob = scope.launch {
            try {
                _isSpeaking.value = true
                val engine = tts!!
                val sampleRate = engine.sampleRate()

                val bufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT
                ).coerceAtLeast(sampleRate) // ≥1s of headroom between synth chunks

                val track = AudioTrack.Builder()
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
                audioTrack = track
                track.play()

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
                val job = coroutineContext[kotlinx.coroutines.Job]
                val onSamples = object : Function1<FloatArray, Int> {
                    override fun invoke(samples: FloatArray): Int {
                        if (stopRequested || job?.isActive == false) return 0
                        if (first) { first = false; Log.i(TAG, "first audio in ${System.currentTimeMillis() - t0}ms") }
                        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        return 1
                    }
                }
                engine.generateWithCallback(text, speakerId, speed, onSamples)
                if (!stopRequested) {
                    // Let the buffered tail drain before tearing the track down.
                    runCatching { track.stop() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during TTS playback", e)
            } finally {
                runCatching { audioTrack?.release() }
                audioTrack = null
                _isSpeaking.value = false
            }
        }
    }

    fun stopSpeaking() {
        stopRequested = true
        playbackJob?.cancel()
        playbackJob = null
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
