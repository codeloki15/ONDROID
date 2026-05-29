package com.locallink.pro.service.llm

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ModelState {
    data object Checking : ModelState
    data object Ready : ModelState
    data class Missing(val expectedPath: String) : ModelState
    data class Error(val message: String) : ModelState
}

/**
 * The model is too large to bundle in the APK, so it is pushed to the device once via:
 *   adb push <model>.task <externalFilesDir>/models/
 * and loaded in place from there.
 *
 * Must be a MediaPipe tasks-genai compatible `.task` bundle (NOT `.litertlm`, which the
 * tasks-genai LlmInference native engine cannot parse — it aborts with
 * "Unknown model type: tf_lite_audio_adapter").
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "ModelManager"
        const val MODEL_FILENAME = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    }

    private val _state = MutableStateFlow<ModelState>(ModelState.Checking)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    /** External files dir is adb-pushable without extra permissions. */
    fun modelFile(): File = File(context.getExternalFilesDir("models"), MODEL_FILENAME)

    fun isReady(): Boolean = modelFile().exists() && modelFile().length() > 0

    /** Check the pushed model is present and non-empty. Idempotent. */
    suspend fun prepare() = withContext(Dispatchers.IO) {
        try {
            _state.value = ModelState.Checking
            val f = modelFile()
            if (f.exists() && f.length() > 0) {
                Log.d(TAG, "Model present at ${f.absolutePath} (${f.length()} bytes)")
                _state.value = ModelState.Ready
            } else {
                Log.w(TAG, "Model missing at ${f.absolutePath}")
                _state.value = ModelState.Missing(f.absolutePath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Model check failed", e)
            _state.value = ModelState.Error(e.message ?: "Failed to check model")
        }
    }
}
