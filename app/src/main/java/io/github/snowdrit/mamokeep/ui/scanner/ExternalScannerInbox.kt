package io.github.snowdrit.mamokeep.ui.scanner

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/**
 * Retains a delivery across rotation. Only a pending marker enters saved state: after
 * process death we report interruption instead of reopening a provider or replaying text.
 * The receiving Activity/task owns the temporary URI grant until the reader consumes it.
 */
internal class ExternalScannerInbox(private val savedState: SavedStateHandle) : ViewModel() {
    var input by mutableStateOf<ExternalScannerInput?>(null)
        private set
    var dispatched by mutableStateOf(false)
        private set
    var error by mutableStateOf(
        if (savedState.get<Boolean>("pending") == true) ScannerImportError.INTERRUPTED
        else savedState.get<String>("error")?.let(ScannerImportError::valueOf),
    )
        private set

    init {
        savedState["pending"] = false
        savedState["error"] = error?.name
    }

    fun receive(intent: Intent) {
        when (val result = parseScannerIntent(intent)) {
            is ScannerIntentResult.Accepted -> {
                if (input != null) {
                    showError(ScannerImportError.BUSY)
                } else {
                    input = result.input
                    dispatched = false
                    savedState["pending"] = true
                    dismissError()
                }
            }
            is ScannerIntentResult.Rejected -> showError(result.error)
            ScannerIntentResult.Ignored -> Unit
        }
    }

    fun dispatch(requestId: String) {
        if (input?.requestId == requestId) dispatched = true
    }

    fun consume(requestId: String) {
        if (input?.requestId != requestId) return
        input = null
        dispatched = false
        savedState["pending"] = false
    }

    fun reject(requestId: String) {
        if (input?.requestId != requestId) return
        consume(requestId)
        showError(ScannerImportError.BUSY)
    }

    fun dismissError() {
        error = null
        savedState["error"] = null
    }

    private fun showError(value: ScannerImportError) {
        error = value
        savedState["error"] = value.name
    }
}
