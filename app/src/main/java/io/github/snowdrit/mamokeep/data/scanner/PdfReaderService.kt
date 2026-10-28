package io.github.snowdrit.mamokeep.data.scanner

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import java.io.IOException
import kotlin.math.roundToInt

/** Pdfium only sees the descriptors granted by the app, in the isolated :pdf process. */
class PdfReaderService : Service() {
    private val lock = Any()
    private var renderer: PdfRenderer? = null

    private val remote = object : PdfReaderRemote.Stub() {
        override fun open(input: ParcelFileDescriptor): Int = synchronized(lock) {
            if (renderer != null) {
                input.close()
                throw IllegalArgumentException(PdfReaderErrors.INVALID)
            }
            pdfOperation {
                // PdfRenderer takes ownership only after its constructor succeeds.
                val document = try {
                    PdfRenderer(input)
                } catch (error: Exception) {
                    input.close()
                    throw error
                }
                try {
                    val count = document.pageCount
                    if (count > 10) {
                        throw PdfReadException(PdfReaderErrors.TOO_MANY_PAGES)
                    }
                    if (count <= 0) throw IOException("PDF has no pages")
                    renderer = document
                    count
                } catch (error: Exception) {
                    document.close()
                    throw error
                }
            }
        }

        override fun readPage(index: Int, output: ParcelFileDescriptor, forceRaster: Boolean): String? = synchronized(lock) {
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                pdfOperation {
                    val document = checkNotNull(renderer) { "PDF is not open" }
                    document.openPage(index).use { page ->
                        if (!forceRaster && Build.VERSION.SDK_INT >= 35) {
                            val text = readNativeText(page)
                            if (text.isNotBlank()) return@pdfOperation text
                        }
                        if (page.width <= 0 || page.height <= 0) throw IOException("Invalid PDF page dimensions")
                        val scale = OCR_MAX_IMAGE_SIDE.toDouble() / maxOf(page.width, page.height)
                        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            image.eraseColor(Color.WHITE)
                            page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            if (!image.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                                throw IOException("PDF page cannot be encoded")
                            }
                        } finally {
                            image.recycle()
                        }
                        null
                    }
                }
            }
        }

        override fun close() = closeRenderer()
    }

    override fun onBind(intent: Intent?): IBinder = remote

    override fun onUnbind(intent: Intent?): Boolean {
        closeRenderer()
        return false
    }

    override fun onDestroy() {
        closeRenderer()
        super.onDestroy()
    }

    private fun closeRenderer() = synchronized(lock) {
        val document = renderer
        renderer = null
        document?.close()
        Unit
    }

    @RequiresApi(35)
    private fun readNativeText(page: PdfRenderer.Page): String = buildString {
        for (content in page.textContents) {
            val text = content.text
            val separatorLength = if (isEmpty()) 0 else 1
            if (text.length > MAX_SCANNER_TEXT - length - separatorLength) {
                throw PdfReadException(PdfReaderErrors.TOO_LARGE)
            }
            if (separatorLength != 0) append('\n')
            append(text)
        }
    }

    private inline fun <T> pdfOperation(block: () -> T): T = try {
        block()
    } catch (error: PdfReadException) {
        throw IllegalArgumentException(error.message)
    } catch (error: IOException) {
        throw IllegalArgumentException(PdfReaderErrors.INVALID, error)
    } catch (error: SecurityException) {
        throw IllegalArgumentException(PdfReaderErrors.INVALID, error)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException(PdfReaderErrors.INVALID, error)
    }
}

private class PdfReadException(reason: String) : Exception(reason)
