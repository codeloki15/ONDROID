package com.locallink.pro.service.voice

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
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
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TtsModelState {
    data object Missing : TtsModelState
    data class Downloading(val done: Long, val total: Long) : TtsModelState
    data object Ready : TtsModelState
    data class Error(val message: String) : TtsModelState
}

/**
 * Fetches Kokoro's acoustic model on demand instead of shipping it in the APK.
 *
 * model.onnx is 330 MB — well over half the download, and the single biggest reason someone
 * would abandon installing. Everything else Kokoro needs (voices, tokens, espeak data) is small
 * enough to stay bundled, so this is one file with resume support.
 *
 * Nothing breaks while it's missing: [VoiceService] already falls back to Android's built-in
 * TTS, so the app speaks out of the box and the download is an upgrade to the better voice.
 */
@Singleton
class TtsModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "TtsModelManager"
        private const val URL =
            "https://huggingface.co/csukuangfj/kokoro-en-v0_19/resolve/main/model.onnx"
        /** Exact size, so progress is honest from the first byte and truncation is detectable. */
        const val TOTAL_BYTES = 345_555_491L
        const val MODEL_FILE = "model.onnx"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Where Kokoro expects the model, alongside the assets copied out at first run. */
    fun modelFile(): File =
        File(context.getExternalFilesDir(null), "${KokoroTtsService.MODEL_DIR}/$MODEL_FILE")

    fun isPresent(): Boolean = modelFile().let { it.exists() && it.length() == TOTAL_BYTES }

    private val _state = MutableStateFlow<TtsModelState>(
        if (isPresent()) TtsModelState.Ready else TtsModelState.Missing,
    )
    val state: StateFlow<TtsModelState> = _state.asStateFlow()

    fun refresh() {
        if (_state.value is TtsModelState.Downloading) return
        _state.value = if (isPresent()) TtsModelState.Ready else TtsModelState.Missing
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun startDownload() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                val dest = modelFile().apply { parentFile?.mkdirs() }
                if (dest.exists() && dest.length() == TOTAL_BYTES) {
                    _state.value = TtsModelState.Ready
                    return@launch
                }
                _state.value = TtsModelState.Downloading(0, TOTAL_BYTES)
                download(dest)
                _state.value = if (isPresent()) TtsModelState.Ready
                else TtsModelState.Error("Download finished but the file failed verification")
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) {
                    _state.value = if (isPresent()) TtsModelState.Ready else TtsModelState.Missing
                } else {
                    Log.e(TAG, "download failed", e)
                    _state.value = TtsModelState.Error(e.message ?: "Download failed")
                }
            }
        }
    }

    /**
     * Download to a `.part` file, resuming via HTTP Range so a dropped connection doesn't cost
     * the whole 330 MB. Only renamed into place once the length matches exactly, so a truncated
     * file can never be mistaken for a usable model.
     */
    private fun CoroutineScope.download(dest: File) {
        val part = File(dest.parentFile, "${dest.name}.part")
        val from = if (part.exists()) part.length() else 0L

        val req = Request.Builder().url(URL)
            .apply { if (from > 0) addHeader("Range", "bytes=$from-") }
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            // A server that ignores Range restarts the file; don't append onto a partial one.
            val appending = from > 0 && resp.code == 206
            if (!appending && part.exists()) part.delete()

            var done = if (appending) from else 0L
            val body = resp.body ?: throw IllegalStateException("empty body")
            RandomAccessFile(part, "rw").use { out ->
                out.seek(done)
                val buf = ByteArray(1 shl 16)
                body.byteStream().use { input ->
                    while (isActive) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        _state.value = TtsModelState.Downloading(done, TOTAL_BYTES)
                    }
                }
            }
            if (!isActive) return
        }

        if (part.length() != TOTAL_BYTES) {
            throw IllegalStateException("expected $TOTAL_BYTES bytes, got ${part.length()}")
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw IllegalStateException("could not move the model into place")
    }
}
