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
    data object Preparing : ModelState
    data class Copying(val progress: Float) : ModelState
    data object Ready : ModelState
    data class Error(val message: String) : ModelState
}

@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "ModelManager"
        const val ASSET_PATH = "models/gemma-3n-E2B-it-int4.litertlm"
        const val MODEL_FILENAME = "gemma-3n-E2B-it-int4.litertlm"
    }

    private val _state = MutableStateFlow<ModelState>(ModelState.Preparing)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    fun modelFile(): File = File(context.filesDir, "models/$MODEL_FILENAME")

    fun isReady(): Boolean = modelFile().exists() && modelFile().length() > 0

    /** Copy bundled asset model to filesDir (MediaPipe needs a real file path). Idempotent. */
    suspend fun prepare() = withContext(Dispatchers.IO) {
        try {
            val target = modelFile()
            val assetSize = context.assets.openFd(ASSET_PATH).use { it.length }
            if (target.exists() && target.length() == assetSize) {
                _state.value = ModelState.Ready
                return@withContext
            }
            target.parentFile?.mkdirs()
            _state.value = ModelState.Copying(0f)
            context.assets.open(ASSET_PATH).use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(8 * 1024 * 1024)
                    var copied = 0L
                    var read = input.read(buf)
                    while (read >= 0) {
                        output.write(buf, 0, read)
                        copied += read
                        if (assetSize > 0) _state.value = ModelState.Copying(copied.toFloat() / assetSize)
                        read = input.read(buf)
                    }
                }
            }
            _state.value = ModelState.Ready
            Log.d(TAG, "Model ready at ${target.absolutePath} (${target.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Model prepare failed", e)
            _state.value = ModelState.Error(e.message ?: "Failed to prepare model")
        }
    }
}
