package io.github.snowdrit.mamokeep.data.scanner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.File

internal object PdfReaderErrors {
    // IllegalArgumentException is one of the public exception types marshalled by Binder.
    const val INVALID = "PDF_INVALID"
    const val TOO_MANY_PAGES = "PDF_TOO_MANY_PAGES"
    const val TOO_LARGE = "PDF_TOO_LARGE"
}

private val pdfReaderMutex = Mutex()

/** One image at a time. The caller may recycle it before requesting the next page. */
internal data class ScannerPdfPage(val text: String?, val image: Bitmap?)

internal class ScannerPdfDocument(
    private val context: Context,
    private val remote: PdfReaderRemote,
    val pageCount: Int,
) : Closeable {
    private var image: Bitmap? = null

    /** Images are valid inside withScannerPdf, until the next readPage or explicit recycling. */
    suspend fun readPage(index: Int, forceRaster: Boolean = false): ScannerPdfPage = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        releaseImage()
        val output = File.createTempFile("ocr-pdf-page-", ".png", context.cacheDir)
        try {
            val text = ParcelFileDescriptor.open(
                output,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
            ).use { descriptor -> pdfCall { remote.readPage(index, descriptor, forceRaster) } }
            currentCoroutineContext().ensureActive()
            if (text != null) {
                ScannerPdfPage(text, null)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(output.path, bounds)
                if (bounds.outWidth !in 1..OCR_MAX_IMAGE_SIDE || bounds.outHeight !in 1..OCR_MAX_IMAGE_SIDE) {
                    throw ScannerDocumentException(ScannerDocumentFailure.INVALID)
                }
                val bitmap = BitmapFactory.decodeFile(output.path)
                    ?: throw ScannerDocumentException(ScannerDocumentFailure.INVALID)
                // Retain ownership until the outer scope closes, including cancellation while
                // withContext delivers this result back to its caller.
                image = bitmap
                currentCoroutineContext().ensureActive()
                ScannerPdfPage(null, bitmap)
            }
        } finally {
            output.delete()
        }
    }

    override fun close() {
        try {
            pdfCall { remote.close() }
        } finally {
            releaseImage()
        }
    }

    private fun releaseImage() {
        image?.let { if (!it.isRecycled) it.recycle() }
        image = null
    }
}

/** The supplied file was already copied and size-checked by the document reader. */
internal suspend fun <T> withScannerPdf(
    context: Context,
    file: File,
    block: suspend (ScannerPdfDocument) -> T,
): T = pdfReaderMutex.withLock {
    withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val connection = PdfReaderConnection()
        try {
            val bound = try {
                appContext.bindService(
                    Intent(appContext, PdfReaderService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            } catch (error: SecurityException) {
                throw ScannerDocumentException(ScannerDocumentFailure.INVALID).apply { initCause(error) }
            }
            if (!bound) throw ScannerDocumentException(ScannerDocumentFailure.INVALID)
            val remote = withTimeoutOrNull(10_000) { connection.ready.await() }
                ?: throw ScannerDocumentException(ScannerDocumentFailure.INVALID)
            currentCoroutineContext().ensureActive()
            val count = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                pdfCall { remote.open(descriptor) }
            }
            ScannerPdfDocument(appContext, remote, count).use { document ->
                currentCoroutineContext().ensureActive()
                block(document)
            }
        } finally {
            // Context requires this after false returns and SecurityException too.
            appContext.unbindService(connection)
        }
    }
}

private class PdfReaderConnection : ServiceConnection {
    val ready = CompletableDeferred<PdfReaderRemote>()

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        ready.complete(PdfReaderRemote.Stub.asInterface(service))
    }

    override fun onServiceDisconnected(name: ComponentName) {
        ready.completeExceptionally(ScannerDocumentException(ScannerDocumentFailure.INVALID))
    }

    override fun onBindingDied(name: ComponentName) {
        ready.completeExceptionally(ScannerDocumentException(ScannerDocumentFailure.INVALID))
    }

    override fun onNullBinding(name: ComponentName) {
        ready.completeExceptionally(ScannerDocumentException(ScannerDocumentFailure.INVALID))
    }
}

private inline fun <T> pdfCall(block: () -> T): T = try {
    block()
} catch (error: IllegalArgumentException) {
    val reason = when (error.message) {
        PdfReaderErrors.TOO_MANY_PAGES -> ScannerDocumentFailure.TOO_MANY_PAGES
        PdfReaderErrors.TOO_LARGE -> ScannerDocumentFailure.TOO_LARGE
        else -> ScannerDocumentFailure.INVALID
    }
    throw ScannerDocumentException(reason).apply { initCause(error) }
} catch (error: RemoteException) {
    throw ScannerDocumentException(ScannerDocumentFailure.INVALID).apply { initCause(error) }
}
