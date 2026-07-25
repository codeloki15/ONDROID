package com.locallink.pro.service.pilot

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class ScreenCapturer(private val metrics: DisplayMetrics) {
    companion object {
        /**
         * Longest edge, in pixels, of the JPEG handed to the vision model.
         *
         * The frame is captured at native resolution but sent downscaled: a modern phone is
         * ~1440×3120, and that image is base64'd inline into the prompt on EVERY pilot step, up
         * to 60 steps per run. Scaling the long edge to 1280 cuts the pixel count (and so the
         * image tokens and upload time) by roughly 4× while leaving on-screen text and controls
         * comfortably legible — the model only needs to identify elements, not read fine print.
         */
        const val MAX_LONG_EDGE = 1280
    }

    /** Grab one frame via a short-lived VirtualDisplay, return a downscaled JPEG (quality 50). */
    suspend fun capture(mp: MediaProjection): ByteArray? = suspendCancellableCoroutine { cont ->
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var vd: VirtualDisplay? = null
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val bmp = Bitmap.createBitmap(
                    rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888,
                ).apply { copyPixelsFromBuffer(plane.buffer) }
                val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                val out = ByteArrayOutputStream()
                downscale(cropped).compress(Bitmap.CompressFormat.JPEG, 50, out)
                if (cont.isActive) cont.resume(out.toByteArray())
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            } finally {
                image.close(); vd?.release(); reader.close()
            }
        }, null)
        vd = mp.createVirtualDisplay(
            "omni-pilot", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null,
        )
        cont.invokeOnCancellation { runCatching { vd?.release(); reader.close() } }
    }

    /** Shrink so the long edge is at most [MAX_LONG_EDGE], preserving aspect ratio. */
    private fun downscale(src: Bitmap): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= MAX_LONG_EDGE) return src
        val scale = MAX_LONG_EDGE.toFloat() / longEdge
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, /* filter = */ true)
    }
}
