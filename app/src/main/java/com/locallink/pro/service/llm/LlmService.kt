package com.locallink.pro.service.llm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
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

    /**
     * Generate from a fully-built prompt string (the caller applies the chat template,
     * e.g. [QwenChatTemplate]). Used by the agent/tool loop. [temperature] is lowered on
     * tool-decision turns for more deterministic tag/JSON output.
     */
    fun generateRaw(prompt: String, temperature: Float = 0.8f): Flow<String>
}

@Singleton
class LlmService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
) : LlmEngine {
    companion object {
        private const val TAG = "LlmService"
        private const val MAX_TOKENS = 4096
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
     * A fresh session is created per call. Runs off the main thread; serialized so two
     * sends can't overlap the single MediaPipe engine.
     */
    override fun generateStream(
        prompt: String,
        image: Bitmap?,
        history: List<Pair<String, String>>,
    ): Flow<String> = generateInternal(
        prompt = prompt,
        temperature = 0.8f,
        image = image?.takeIf { MODEL_SUPPORTS_VISION },
    )

    /** Run a pre-templated prompt (used by the tool/agent loop). No image, no extra templating. */
    override fun generateRaw(prompt: String, temperature: Float): Flow<String> =
        generateInternal(prompt = prompt, temperature = temperature, image = null)

    /**
     * Single serialized generation. The mutex is held for the ENTIRE generation: since
     * generateResponseAsync returns immediately (non-blocking), we suspend on a Deferred
     * that the callback completes on done=true. A plain withLock around the launch would
     * release mid-stream and let a second send collide ("Previous invocation still processing").
     */
    private fun generateInternal(
        prompt: String,
        temperature: Float,
        image: Bitmap?,
    ): Flow<String> = channelFlow {
        try {
            ensureLoaded()
        } catch (e: Throwable) {
            Log.e(TAG, "ensureLoaded failed", e)
            close(e); return@channelFlow
        }
        val eng = engine ?: run { close(IllegalStateException("LLM not loaded")); return@channelFlow }

        genMutex.withLock {
            val builder = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTemperature(temperature)
            if (image != null) {
                builder.setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
            }
            val session = LlmInferenceSession.createFromOptions(eng, builder.build())
            val done = CompletableDeferred<Unit>()
            try {
                session.addQueryChunk(prompt)
                if (image != null) session.addImage(BitmapImageBuilder(image).build())
                session.generateResponseAsync { partial, isDone ->
                    trySend(partial)
                    if (isDone) done.complete(Unit)
                }
                // Hold the lock until generation finishes OR the consumer cancels.
                done.await()
            } catch (e: Throwable) {
                Log.e(TAG, "generate failed", e)
                throw e
            } finally {
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
