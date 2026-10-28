package io.github.snowdrit.mamokeep.ui.scanner

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalScannerIntentTest {
    private val receipt = Uri.parse("content://receipts/document/one")

    @Test
    fun imageShareWithTheSameGrantInStreamAndClipIsOneDelivery() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, receipt)
            clipData = ClipData.newRawUri("receipt", receipt)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val result = parseScannerIntent(intent, "share-1") as ScannerIntentResult.Accepted
        assertEquals("share-1", result.input.requestId)
        assertEquals(ScannerInputSource.Content(receipt, "image/jpeg"), result.input.source)
        // Parsing does not alter the originating grant or open a provider.
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertEquals(receipt, intent.clipData!!.getItemAt(0).uri)
    }

    @Test
    fun viewAcceptsPdfAndPlainTextContentOnly() {
        for (mime in listOf("application/pdf", "text/plain")) {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(receipt, mime)
            val input = (parseScannerIntent(intent) as ScannerIntentResult.Accepted).input
            assertEquals(ScannerInputSource.Content(receipt, mime), input.source)
        }
        for (uri in listOf("https://example.com/receipt.pdf", "file:///receipt.pdf")) {
            assertRejected(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "application/pdf"), ScannerImportError.UNSUPPORTED)
        }
    }

    @Test
    fun multipleFilesAreRejectedWithoutSelectingTheFirst() {
        assertRejected(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg"), ScannerImportError.MULTIPLE)
        val clip = ClipData.newRawUri("receipts", receipt).apply {
            addItem(ClipData.Item(Uri.parse("content://receipts/document/two")))
        }
        assertRejected(Intent(Intent.ACTION_SEND).setType("image/jpeg").apply { clipData = clip }, ScannerImportError.MULTIPLE)
    }

    @Test
    fun sharedTextIsBoundedWithoutSilentTruncation() {
        val exactLimit = "x".repeat(MAX_SHARED_SCANNER_TEXT)
        assertEquals(
            ScannerInputSource.Text(exactLimit),
            (parseScannerIntent(textIntent(exactLimit)) as ScannerIntentResult.Accepted).input.source,
        )
        assertRejected(textIntent(exactLimit + "x"), ScannerImportError.TOO_LARGE)
        assertRejected(textIntent("  \n "), ScannerImportError.UNSUPPORTED)
    }

    @Test
    fun linksRemainLiteralTextAndEachNewShareHasItsOwnIdentity() {
        val text = "Receipt: https://example.com/receipt"
        val first = (parseScannerIntent(textIntent(text)) as ScannerIntentResult.Accepted).input
        val second = (parseScannerIntent(textIntent(text)) as ScannerIntentResult.Accepted).input
        assertEquals(ScannerInputSource.Text(text), first.source)
        assertNotEquals(first.requestId, second.requestId)
    }

    @Test
    fun unsupportedPayloadIsVisibleWhileOrdinaryLauncherIntentsAreIgnored() {
        assertRejected(Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, receipt), ScannerImportError.UNSUPPORTED)
        assertRejected(Intent(Intent.ACTION_SEND).setType("image/jpeg"), ScannerImportError.UNSUPPORTED)
        assertRejected(Intent(Intent.ACTION_VIEW).setDataAndType(receipt, null), ScannerImportError.UNSUPPORTED)
        assertTrue(parseScannerIntent(Intent(Intent.ACTION_MAIN)) is ScannerIntentResult.Ignored)
    }

    private fun textIntent(text: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

    private fun assertRejected(intent: Intent, error: ScannerImportError) {
        assertEquals(ScannerIntentResult.Rejected(error), parseScannerIntent(intent))
    }
}
