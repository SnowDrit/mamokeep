package io.github.snowdrit.mamokeep.data.scanner

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class ScannerImageTest {
    @Test fun cameraExifOrientationIsAppliedWithoutModifyingThePhoto() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = File.createTempFile("source-", ".jpg", context.cacheDir)
        try {
            val source = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
            try {
                source.eraseColor(Color.WHITE)
                photo.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            } finally { source.recycle() }
            ExifInterface(photo.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val original = photo.readBytes()
            val upright = readScannerImage(context, Uri.fromFile(photo))
            try { assertEquals(40, upright.width); assertEquals(80, upright.height) }
            finally { upright.recycle() }
            assertArrayEquals(original, photo.readBytes())
        } finally { photo.delete() }
    }

    @Test fun corruptImageFailsAndRemovesOnlyItsTemporaryCopy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = File.createTempFile("source-", ".jpg", context.cacheDir)
        val before = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("ocr-input-") }.toSet()
        try {
            photo.writeText("This is not a photo")
            try { readScannerImage(context, Uri.fromFile(photo)); fail("Invalid image was accepted") }
            catch (_: IOException) { }
            assertEquals("This is not a photo", photo.readText())
            assertEquals(before, context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("ocr-input-") }.toSet())
        } finally { photo.delete() }
    }
}
