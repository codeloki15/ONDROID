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
    /** Grab one frame via a short-lived VirtualDisplay, return a JPEG (quality 50). */
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
                cropped.compress(Bitmap.CompressFormat.JPEG, 50, out)
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
}
