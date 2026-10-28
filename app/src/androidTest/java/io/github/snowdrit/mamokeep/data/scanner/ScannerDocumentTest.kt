package io.github.snowdrit.mamokeep.data.scanner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class ScannerDocumentTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun utf8TextKeepsReceiptCharactersAndRemovesBomWithoutChangingSource() = runBlocking {
        val file = File.createTempFile("receipt-test-", ".txt", context.cacheDir)
        try {
            val source = "\uFEFFЧек №123\nБензин 45,50 л\nTOTAL 1234.50\n22.10.2026"
            file.writeText(source)
            assertEquals(source.removePrefix("\uFEFF"), readScannerText(context, Uri.fromFile(file)))
            assertEquals(source, file.readText())
        } finally { file.delete() }
    }

    @Test fun invalidUtf8AndBinaryTextFailWithoutKeepingTemporaryCopies() = runBlocking {
        val file = File.createTempFile("receipt-test-", ".txt", context.cacheDir)
        val before = temporaryInputs()
        try {
            for (bytes in listOf(byteArrayOf(0xC3.toByte(), 0x28), "TOTAL\u0000123".toByteArray())) {
                file.writeBytes(bytes)
                try { readScannerText(context, Uri.fromFile(file)); fail("Invalid text accepted") }
                catch (_: IOException) { }
                assertEquals(before, temporaryInputs())
            }
        } finally { file.delete() }
    }

    @Test fun limitsRejectRatherThanTruncateAndCleanCopies() = runBlocking {
        val file = File.createTempFile("receipt-test-", ".txt", context.cacheDir)
        val before = temporaryInputs()
        try {
            file.writeText("A".repeat(MAX_SCANNER_TEXT))
            assertEquals(MAX_SCANNER_TEXT, readScannerText(context, Uri.fromFile(file)).length)
            file.appendText("B")
            try { readScannerText(context, Uri.fromFile(file)); fail("Oversize text accepted") }
            catch (failure: ScannerDocumentException) { assertEquals(ScannerDocumentFailure.TOO_LARGE, failure.reason) }
            try { copyScannerInput(context, Uri.fromFile(file), ".test", limit = 1024); fail("Oversize copy accepted") }
            catch (failure: ScannerDocumentException) { assertEquals(ScannerDocumentFailure.TOO_LARGE, failure.reason) }
            assertEquals(before, temporaryInputs())
            assertEquals(MAX_SCANNER_TEXT + 1, file.readText().length)
        } finally { file.delete() }
    }

    @Test fun isolatedPdfReadsEveryPageAndRendersImageOnlyPages() = runBlocking {
        val file = makePdf(2, scanned = false)
        try {
            withScannerPdf(context, file) { document ->
                assertEquals(2, document.pageCount)
                repeat(document.pageCount) { index ->
                    val page = document.readPage(index)
                    try {
                        if (Build.VERSION.SDK_INT >= 35) {
                            assertTrue("Page ${index + 1}: ${page.text}", page.text.orEmpty().contains("PAGE ${index + 1}"))
                            assertNull(page.image)
                        } else assertNotNull(page.image)
                    } finally { page.image?.recycle() }
                }
                val forced = document.readPage(0, forceRaster = true)
                try {
                    assertNull(forced.text)
                    assertNotNull(forced.image)
                } finally { forced.image?.recycle() }
            }
        } finally { file.delete() }
        val scanned = makePdf(1, scanned = true)
        try {
            withScannerPdf(context, scanned) { document ->
                val page = document.readPage(0)
                try {
                    assertNull(page.text)
                    val bitmap = requireNotNull(page.image)
                    assertTrue(bitmap.width > 0 && bitmap.height > 0)
                    assertTrue(maxOf(bitmap.width, bitmap.height) <= OCR_MAX_IMAGE_SIDE)
                } finally { page.image?.recycle() }
            }
        } finally { scanned.delete() }
    }

    @Test fun invalidAndOverlongPdfFailAsAWhole() = runBlocking {
        val file = File.createTempFile("receipt-test-", ".pdf", context.cacheDir)
        try {
            file.writeText("not a PDF")
            try { withScannerPdf(context, file) { fail("Corrupt PDF opened") }; fail("Corrupt PDF accepted") }
            catch (_: IOException) { }
        } finally { file.delete() }
        val long = makePdf(11, scanned = false)
        try {
            try { withScannerPdf(context, long) { fail("Partial PDF opened") }; fail("11 pages accepted") }
            catch (failure: ScannerDocumentException) { assertEquals(ScannerDocumentFailure.TOO_MANY_PAGES, failure.reason) }
        } finally { long.delete() }
    }

    private fun temporaryInputs() = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("ocr-input-") }.toSet()

    private fun makePdf(pages: Int, scanned: Boolean): File {
        val file = File.createTempFile("receipt-test-", ".pdf", context.cacheDir)
        val document = PdfDocument()
        try {
            repeat(pages) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(400, 300, index + 1).create())
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 28f }
                if (scanned) {
                    val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = android.graphics.Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)
                        canvas.drawText("TOTAL 1234.50", 20f, 80f, paint)
                        page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    } finally { bitmap.recycle() }
                } else page.canvas.drawText("PAGE ${index + 1} TOTAL 1234.50", 20f, 80f, paint)
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        return file
    }
}
