package com.locallink.pro.service.voice

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SttModelState {
    data object Missing : SttModelState
    /** [downloaded]/[total] bytes across all files. */
    data class Downloading(val downloaded: Long, val total: Long) : SttModelState
    data object Ready : SttModelState
    data class Error(val message: String) : SttModelState
}

/**
 * Downloads the parakeet-tdt-0.6b-v3 int8 ONNX export (sherpa-onnx format, ~670 MB) from
 * Hugging Face into the app's external files dir. Per-file resume via HTTP Range +
 * `.part` files, so an interrupted download continues where it left off.
 */
@Singleton
class SttModelManager @Inject constructor(
    private val engine: ParakeetSttEngine,
) {
    companion object {
        private const val TAG = "SttModelManager"
        private const val BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"
        // Sizes known up front so overall progress is accurate from the first byte.
        private val FILES = listOf(
            "encoder.int8.onnx" to 652_184_281L,
            "decoder.int8.onnx" to 11_845_275L,
            "joiner.int8.onnx" to 6_355_277L,
            "tokens.txt" to 93_939L,
        )
        val TOTAL_BYTES = FILES.sumOf { it.second }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<SttModelState>(
        if (engine.isModelPresent()) SttModelState.Ready else SttModelState.Missing,
    )
    val state: StateFlow<SttModelState> = _state.asStateFlow()

    fun refresh() {
        if (_state.value is SttModelState.Downloading) return
        _state.value = if (engine.isModelPresent()) SttModelState.Ready else SttModelState.Missing
    }

    fun startDownload() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                val dir = engine.modelDir().apply { mkdirs() }
                var done = FILES.sumOf { (name, size) ->
                    val f = File(dir, name)
                    if (f.exists() && f.length() == size) size else 0L
                }
                _state.value = SttModelState.Downloading(done, TOTAL_BYTES)
                for ((name, size) in FILES) {
                    val dest = File(dir, name)
                    if (dest.exists() && dest.length() == size) continue
                    downloadFile("$BASE/$name", dest, size) { delta ->
                        done += delta
                        _state.value = SttModelState.Downloading(done, TOTAL_BYTES)
                    }
                }
                _state.value = if (engine.isModelPresent()) SttModelState.Ready
                else SttModelState.Error("Download finished but files failed verification")
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) {
                    _state.value = if (engine.isModelPresent()) SttModelState.Ready else SttModelState.Missing
                } else {
                    Log.e(TAG, "download failed", e)
                    _state.value = SttModelState.Error(e.message ?: "Download failed")
                }
            }
        }
    }

    private fun CoroutineScope.downloadFile(url: String, dest: File, expected: Long, onDelta: (Long) -> Unit) {
        val part = File(dest.parentFile, dest.name + ".part")
        var offset = if (part.exists()) part.length() else 0L
        if (offset > 0) onDelta(offset)
        val req = Request.Builder().url(url).apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for ${dest.name}")
            if (offset > 0 && resp.code != 206) {
                // Server ignored the Range — restart the file from scratch.
                onDelta(-offset)
                offset = 0
                part.delete()
            }
            val body = resp.body ?: throw IllegalStateException("empty body for ${dest.name}")
            body.byteStream().use { input ->
                java.io.FileOutputStream(part, offset > 0).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (!isActive) throw kotlinx.coroutines.CancellationException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        onDelta(n.toLong())
                    }
                }
            }
        }
        if (part.length() != expected) {
            throw IllegalStateException("${dest.name}: got ${part.length()} bytes, expected $expected")
        }
        if (!part.renameTo(dest)) throw IllegalStateException("could not finalize ${dest.name}")
    }

    fun cancelDownload() {
        job?.cancel()
    }

    fun deleteModel() {
        cancelDownload()
        scope.launch {
            engine.release()
            engine.modelDir().listFiles()?.forEach { it.delete() }
            refresh()
        }
    }
}
