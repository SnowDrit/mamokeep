package io.github.snowdrit.mamokeep.ui.scanner

import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import androidx.core.content.IntentCompat
import io.github.snowdrit.mamokeep.data.scanner.MAX_SCANNER_TEXT
import java.util.Locale
import java.util.UUID

/** One explicit delivery from another app. This is never a command to save a record. */
data class ExternalScannerInput(val requestId: String, val source: ScannerInputSource)

sealed interface ScannerInputSource {
    data class Content(val uri: Uri, val mimeType: String) : ScannerInputSource
    data class Text(val text: String) : ScannerInputSource
}

internal enum class ScannerImportError { UNSUPPORTED, MULTIPLE, TOO_LARGE, BUSY, INTERRUPTED }

internal sealed interface ScannerIntentResult {
    data class Accepted(val input: ExternalScannerInput) : ScannerIntentResult
    data class Rejected(val error: ScannerImportError) : ScannerIntentResult
    data object Ignored : ScannerIntentResult
}

internal const val MAX_SHARED_SCANNER_TEXT = MAX_SCANNER_TEXT

/** Validates the external Intent boundary; never resolves a URL or reads a provider. */
internal fun parseScannerIntent(intent: Intent, requestId: String = UUID.randomUUID().toString()): ScannerIntentResult {
    if (intent.action == Intent.ACTION_SEND_MULTIPLE) return ScannerIntentResult.Rejected(ScannerImportError.MULTIPLE)
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_VIEW) return ScannerIntentResult.Ignored
    return try {
        parseScannerPayload(intent, requestId)
    } catch (_: BadParcelableException) {
        ScannerIntentResult.Rejected(ScannerImportError.UNSUPPORTED)
    } catch (_: ClassCastException) {
        ScannerIntentResult.Rejected(ScannerImportError.UNSUPPORTED)
    }
}

private fun parseScannerPayload(intent: Intent, requestId: String): ScannerIntentResult {
    val uris = mutableSetOf<Uri>()
    intent.data?.let(uris::add)
    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::add)
    intent.clipData?.let { clip ->
        for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(uris::add)
    }
    if (uris.size > 1) return ScannerIntentResult.Rejected(ScannerImportError.MULTIPLE)
    val mimeType = intent.type?.lowercase(Locale.ROOT)
        ?: return ScannerIntentResult.Rejected(ScannerImportError.UNSUPPORTED)
    val supported = mimeType == "application/pdf" || mimeType == "text/plain" ||
        (mimeType.startsWith("image/") && mimeType.length > "image/".length)
    if (!supported) return ScannerIntentResult.Rejected(ScannerImportError.UNSUPPORTED)

    val uri = uris.singleOrNull()
    if (uri != null) {
        if (uri.scheme != "content") return ScannerIntentResult.Rejected(ScannerImportError.UNSUPPORTED)
        return ScannerIntentResult.Accepted(ExternalScannerInput(requestId, ScannerInputSource.Content(uri, mimeType)))
    }
    if (intent.action != Intent.ACTION_SEND || mimeType != "text/plain") {
        return ScannerIntentResult.Rejected(ScannerImportError.UNSUPPORTED)
    }
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        ?: return ScannerIntentResult.Rejected(ScannerImportError.UNSUPPORTED)
    if (text.length > MAX_SHARED_SCANNER_TEXT) return ScannerIntentResult.Rejected(ScannerImportError.TOO_LARGE)
    if (text.isBlank()) return ScannerIntentResult.Rejected(ScannerImportError.UNSUPPORTED)
    return ScannerIntentResult.Accepted(ExternalScannerInput(requestId, ScannerInputSource.Text(text.toString())))
}
