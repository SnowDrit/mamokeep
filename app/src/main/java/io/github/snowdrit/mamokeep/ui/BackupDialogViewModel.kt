package io.github.snowdrit.mamokeep.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.data.readDocumentText
import io.github.snowdrit.mamokeep.data.writeDocumentText
import io.github.snowdrit.mamokeep.domain.export.BackupCodec
import io.github.snowdrit.mamokeep.domain.export.BackupCurrency
import io.github.snowdrit.mamokeep.domain.export.BackupCurrencyMismatchException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class BackupRestorePreview(val carCount: Int, val currency: BackupCurrency?)

/** Keeps the potentially large JSON out of saved state while surviving activity recreation. */
internal class BackupDialogViewModel : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var savePickerRequested by mutableStateOf(false)
        private set
    var status by mutableStateOf<Int?>(null)
        private set
    var restoredCars by mutableStateOf<Int?>(null)
        private set
    var restorePreview by mutableStateOf<BackupRestorePreview?>(null)
        private set
    var confirmedUnknownCurrency by mutableStateOf<BackupCurrency?>(null)
        private set
    private var pending: String? = null
    private var pendingRestore: String? = null

    fun prepare(buildBackup: suspend () -> String) {
        if (busy || restorePreview != null) return
        busy = true
        clearStatus()
        viewModelScope.launch {
            try {
                pending = buildBackup()
                savePickerRequested = true
            } catch (cancelled: CancellationException) {
                busy = false
                throw cancelled
            } catch (_: Exception) {
                status = R.string.backup_prepare_failed
                busy = false
            }
        }
    }

    fun pickerLaunched() { savePickerRequested = false }

    fun saveFile(uri: Uri?, resolver: ContentResolver) {
        val content = pending
        pending = null
        if (uri == null) {
            busy = false
            return
        }
        // Process death may retain the picker result but cannot retain an in-memory snapshot.
        if (content == null) {
            status = R.string.backup_write_failed
            busy = false
            return
        }
        busy = true
        viewModelScope.launch {
            try {
                resolver.writeDocumentText(uri, content)
                status = R.string.backup_saved
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = R.string.backup_write_failed
            } finally {
                busy = false
            }
        }
    }

    fun beginRestoreSelection(): Boolean {
        if (busy || restorePreview != null) return false
        clearStatus()
        busy = true
        return true
    }

    fun restoreFile(uri: Uri?, resolver: ContentResolver) {
        if (uri == null) {
            busy = false
            return
        }
        busy = true
        viewModelScope.launch {
            try {
                val content = try {
                    resolver.readDocumentText(uri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    status = R.string.backup_read_failed
                    return@launch
                }
                val preview = withContext(Dispatchers.Default) {
                    BackupCodec.decode(content)?.let { BackupRestorePreview(it.cars.size, it.currency) }
                }
                if (preview == null) {
                    status = R.string.backup_not_ours
                } else {
                    pendingRestore = content
                    restorePreview = preview
                    confirmedUnknownCurrency = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = R.string.backup_restore_failed
            } finally {
                busy = false
            }
        }
    }

    fun confirmUnknownCurrency(currency: BackupCurrency?) {
        if (!busy) confirmedUnknownCurrency = currency
    }

    fun canRestore(currency: BackupCurrency): Boolean {
        val preview = restorePreview ?: return false
        return if (preview.currency?.code == null) confirmedUnknownCurrency == currency
        else preview.currency == currency
    }

    fun confirmRestore(currency: BackupCurrency, restoreBackup: suspend (String, BackupCurrency) -> Int?) {
        if (busy || !canRestore(currency)) return
        val content = checkNotNull(pendingRestore)
        busy = true
        clearStatus()
        viewModelScope.launch {
            try {
                val cars = restoreBackup(content, currency)
                if (cars == null) status = R.string.backup_not_ours else restoredCars = cars
                clearRestore()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: BackupCurrencyMismatchException) {
                confirmedUnknownCurrency = null
                status = R.string.backup_currency_changed
            } catch (_: Exception) {
                status = R.string.backup_restore_failed
            } finally {
                busy = false
            }
        }
    }

    fun cancelRestore() {
        if (busy) return
        clearRestore()
        clearStatus()
    }

    private fun clearRestore() {
        pendingRestore = null
        restorePreview = null
        confirmedUnknownCurrency = null
    }

    fun selectionFailed(saving: Boolean) {
        pending = null
        clearRestore()
        savePickerRequested = false
        busy = false
        status = if (saving) R.string.backup_write_failed else R.string.backup_read_failed
    }

    fun reset() {
        check(!busy)
        pending = null
        clearRestore()
        savePickerRequested = false
        clearStatus()
    }

    private fun clearStatus() {
        status = null
        restoredCars = null
    }
}
