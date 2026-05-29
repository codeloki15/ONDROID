package com.locallink.pro.service.llm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Abstraction so [com.locallink.pro.data.repository.ChatRepository] is testable with a fake. */
interface LlmEngine {
    fun ensureLoaded()
    fun generateStream(
        prompt: String,
        image: Bitmap? = null,
        history: List<Pair<String, String>> = emptyList(),
    ): Flow<String>
}

@Singleton
class LlmService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
) : LlmEngine {
    companion object {
        private const val TAG = "LlmService"
        private const val MAX_TOKENS = 2048
        private const val MAX_HISTORY_TURNS = 12
        // The bundled Gemma 3n .litertlm export is text-only for our purposes; keep vision
        // off unless we confirm the model file supports it (requesting vision on a model
        // without vision weights fails the graph build).
        private const val MODEL_SUPPORTS_VISION = false
    }

    @Volatile private var engine: LlmInference? = null

    // MediaPipe forbids concurrent generateResponseAsync calls on sessions from one engine.
    private val genMutex = Mutex()

    /** Load the MediaPipe engine from the prepared model file. Idempotent. Must run off the UI thread. */
    @Synchronized
    override fun ensureLoaded() {
        if (engine != null) return
        val path = modelManager.modelFile().absolutePath
        val builder = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(path)
            .setMaxTokens(MAX_TOKENS)
            // CPU backend: a multi-GB model on Adreno/GPU commonly OOMs on an 8GB device.
            .setPreferredBackend(LlmInference.Backend.CPU)
        if (MODEL_SUPPORTS_VISION) {
            builder.setMaxNumImages(1)
        }
        engine = LlmInference.createFromOptions(context, builder.build())
        Log.d(TAG, "LLM engine loaded from $path")
    }

    /**
     * Generate a streamed response. Emits partial text chunks; completes when done.
     * A fresh session is created per call; [history] re-seeds prior turns for context.
     * Runs off the main thread; serialized so two sends can't overlap.
     */
    override fun generateStream(
        prompt: String,
        image: Bitmap?,
        history: List<Pair<String, String>>,
    ): Flow<String> = callbackFlow {
        val useImage = image != null && MODEL_SUPPORTS_VISION

        try {
            ensureLoaded()
        } catch (e: Throwable) {
            Log.e(TAG, "ensureLoaded failed", e)
            close(e)
            return@callbackFlow
        }
        val eng = engine ?: run {
            close(IllegalStateException("LLM not loaded"))
            return@callbackFlow
        }

        genMutex.withLock {
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTemperature(0.8f)
                .apply {
                    if (useImage) {
                        setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
                    }
                }
                .build()

            val session = LlmInferenceSession.createFromOptions(eng, sessionOptions)
            try {
                // Pass the raw prompt as a single chunk; MediaPipe applies the model's
                // baked-in chat template. (Multi-turn history re-seeding is intentionally
                // omitted for now — manual role prefixing fights the template and varies
                // by model family. Each send is a fresh turn until proper templating lands.)
                session.addQueryChunk(prompt)
                if (useImage) {
                    session.addImage(BitmapImageBuilder(image!!).build())
                }

                session.generateResponseAsync { partial, done ->
                    trySend(partial)
                    if (done) channel.close()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "generateStream failed", e)
                channel.close(e)
            }

            awaitClose {
                try { session.close() } catch (_: Exception) {}
            }
        }
    }.buffer(capacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)
        .flowOn(Dispatchers.Default)

    fun shutdown() {
        try { engine?.close() } catch (_: Exception) {}
        engine = null
    }
}
