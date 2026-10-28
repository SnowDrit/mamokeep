package io.github.snowdrit.mamokeep.data.scanner

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal const val MAX_SCANNER_TEXT = 100_000
internal const val MAX_SCANNER_FILE_BYTES = 25L * 1024 * 1024

internal enum class ScannerDocumentFailure { UNSUPPORTED, TOO_LARGE, TOO_MANY_PAGES, INVALID }
internal class ScannerDocumentException(val reason: ScannerDocumentFailure) : IOException(reason.name)

/** A bounded, seekable local copy; never persist an external provider's URI grant. */
internal suspend fun copyScannerInput(context: Context, uri: Uri, suffix: String, limit: Long = MAX_SCANNER_FILE_BYTES): File {
    var created: File? = null
    try {
        return withContext(Dispatchers.IO) {
            val file = File.createTempFile("ocr-input-", suffix, context.cacheDir)
            created = file
            val source = context.contentResolver.openInputStream(uri) ?: throw IOException("Document is unavailable")
            source.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > limit) throw ScannerDocumentException(ScannerDocumentFailure.TOO_LARGE)
                        output.write(buffer, 0, count)
                    }
                }
            }
            file
        }
    } catch (failure: Exception) {
        // Also covers cancellation when withContext returns ownership to the caller.
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { created?.delete() }
        throw failure
    }
}

internal fun checkedScannerText(text: String): String {
    if (text.length > MAX_SCANNER_TEXT) throw ScannerDocumentException(ScannerDocumentFailure.TOO_LARGE)
    if (text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
        throw ScannerDocumentException(ScannerDocumentFailure.INVALID)
    }
    return text.removePrefix("\uFEFF")
}

internal suspend fun readScannerText(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val file = copyScannerInput(context, uri, ".txt", MAX_SCANNER_TEXT * 4L + 3)
    try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        checkedScannerText(decoder.decode(ByteBuffer.wrap(file.readBytes())).toString())
    } finally {
        file.delete()
    }
}

internal suspend fun readScannerPdf(context: Context, uri: Uri, manager: ScannerModelManager, forceRaster: Boolean = false): String {
    val file = copyScannerInput(context, uri, ".pdf")
    try {
        return withScannerPdf(context, file) { document ->
            val text = StringBuilder()
            repeat(document.pageCount) { index ->
                currentCoroutineContext().ensureActive()
                val page = document.readPage(index, forceRaster)
                val pageText = try {
                    page.text ?: manager.recognize(requireNotNull(page.image)).text
                } finally {
                    page.image?.recycle()
                }
                if (text.length + pageText.length + (if (index == 0) 0 else 2) > MAX_SCANNER_TEXT) {
                    throw ScannerDocumentException(ScannerDocumentFailure.TOO_LARGE)
                }
                if (index > 0) text.append("\n\n")
                text.append(pageText)
            }
            text.toString()
        }
    } finally {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { file.delete() }
    }
}
