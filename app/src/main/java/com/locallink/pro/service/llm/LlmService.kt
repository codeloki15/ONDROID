package com.locallink.pro.service.llm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
    }

    @Volatile private var engine: LlmInference? = null

    /** Load the MediaPipe engine from the prepared model file. Idempotent. */
    @Synchronized
    override fun ensureLoaded() {
        if (engine != null) return
        val path = modelManager.modelFile().absolutePath
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(path)
            .setMaxTokens(MAX_TOKENS)
            .setMaxNumImages(1)
            .build()
        engine = LlmInference.createFromOptions(context, options)
        Log.d(TAG, "LLM engine loaded from $path")
    }

    /**
     * Generate a streamed response. Emits partial text chunks; completes when done.
     * A fresh session is created per call; [history] re-seeds prior turns for context.
     */
    override fun generateStream(
        prompt: String,
        image: Bitmap?,
        history: List<Pair<String, String>>,
    ): Flow<String> = callbackFlow {
        ensureLoaded()
        val eng = engine ?: throw IllegalStateException("LLM not loaded")

        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(40)
            .setTemperature(0.8f)
            .setGraphOptions(GraphOptions.builder().setEnableVisionModality(image != null).build())
            .build()

        val session = LlmInferenceSession.createFromOptions(eng, sessionOptions)
        try {
            history.takeLast(MAX_HISTORY_TURNS).forEach { (role, text) ->
                val speaker = if (role == "assistant") "Model" else "User"
                session.addQueryChunk("$speaker: $text")
            }
            session.addQueryChunk("User: $prompt")
            if (image != null) {
                session.addImage(BitmapImageBuilder(image).build())
            }

            session.generateResponseAsync { partial, done ->
                trySend(partial)
                if (done) channel.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateStream failed", e)
            channel.close(e)
        }

        awaitClose {
            try { session.close() } catch (_: Exception) {}
        }
    }

    fun shutdown() {
        try { engine?.close() } catch (_: Exception) {}
        engine = null
    }
}
