package io.github.snowdrit.mamokeep.data.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** Decode untrusted picker/camera input off the UI thread, before allocating its full pixels. */
internal suspend fun readScannerImage(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
    val input = copyScannerInput(context, uri, ".image")
    try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0 ||
            options.outWidth.toLong() * options.outHeight > 40_000_000
        ) throw IOException("Image dimensions are unsupported")
        val orientation = ExifInterface(input.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        while (maxOf(options.outWidth, options.outHeight) / (options.inSampleSize.coerceAtLeast(1) * 2) >= OCR_MAX_IMAGE_SIDE) {
            options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        }
        var bitmap = BitmapFactory.decodeFile(input.path, options) ?: throw IOException("Image cannot be decoded")
        try {
            currentCoroutineContext().ensureActive()
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
                val scale = minOf(1f, OCR_MAX_IMAGE_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
                postScale(scale, scale)
            }
            val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (upright !== bitmap) bitmap.recycle()
            bitmap = upright
            currentCoroutineContext().ensureActive()
            bitmap
        } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
    } finally {
        input.delete()
    }
}
