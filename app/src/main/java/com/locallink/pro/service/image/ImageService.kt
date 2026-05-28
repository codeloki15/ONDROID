package com.locallink.pro.service.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object { private const val MAX_DIM = 768 }

    /** Load a content URI into a downscaled, orientation-corrected Bitmap for inference. */
    suspend fun loadForInference(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it)
        } ?: error("Could not decode image")
        val scaled = downscale(raw, MAX_DIM)
        applyExifRotation(uri, scaled)
    }

    private fun downscale(src: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxDim) return src
        val ratio = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true
        )
    }

    private fun applyExifRotation(uri: Uri, bmp: Bitmap): Bitmap {
        return try {
            context.contentResolver.openInputStream(uri).use { input ->
                val exif = ExifInterface(input!!)
                val deg = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (deg == 0f) bmp else Bitmap.createBitmap(
                    bmp, 0, 0, bmp.width, bmp.height,
                    Matrix().apply { postRotate(deg) }, true
                )
            }
        } catch (_: Exception) { bmp }
    }
}
