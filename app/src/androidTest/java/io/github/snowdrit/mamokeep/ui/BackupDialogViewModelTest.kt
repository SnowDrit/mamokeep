package io.github.snowdrit.mamokeep.ui

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.domain.export.BackupCurrency
import io.github.snowdrit.mamokeep.domain.export.BackupCurrencyMismatchException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class BackupDialogViewModelTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun saveKeepsPreparedContentUntilPickerResultAndPreventsConcurrentOperations() = runBlocking {
        val file = File.createTempFile("backup-dialog-", ".json", context.cacheDir)
        try {
            withContext(Dispatchers.Main) {
                val state = BackupDialogViewModel()
                val content = "{\"garage\":\"" + "данные".repeat(200_000) + "\"}"
                state.prepare { content }
                yield()
                assertTrue(state.savePickerRequested)
                assertTrue(state.busy)
                assertFalse(state.beginRestoreSelection())
                state.prepare { error("A second preparation must not start") }
                state.pickerLaunched()
                assertFalse(state.savePickerRequested)
                state.saveFile(Uri.fromFile(file), context.contentResolver)
                awaitIdle(state)
                assertEquals(R.string.backup_saved, state.status)
                assertEquals(content, file.readText())
                state.reset()
                assertNull(state.status)
            }
        } finally { file.delete() }
    }

    @Test
    fun preparationFailureAndLostSnapshotNeverReportSuccess() = runBlocking {
        withContext(Dispatchers.Main) {
            val state = BackupDialogViewModel()
            state.prepare { throw IOException("database read failure") }
            awaitIdle(state)
            assertEquals(R.string.backup_prepare_failed, state.status)
            assertFalse(state.savePickerRequested)
            state.reset()
            state.saveFile(Uri.parse("content://unused/file"), context.contentResolver)
            assertEquals(R.string.backup_write_failed, state.status)
            assertFalse(state.busy)
        }
    }

    @Test
    fun restoreFailureIsVisibleAndPickerCancellationDoesNotMutateTheGarage() = runBlocking {
        val file = File.createTempFile("backup-dialog-", ".json", context.cacheDir)
        file.writeText(knownCurrencyCopy)
        try {
            withContext(Dispatchers.Main) {
                val state = BackupDialogViewModel()
                assertTrue(state.beginRestoreSelection())
                state.restoreFile(null, context.contentResolver)
                assertFalse(state.busy)
                assertNull(state.status)
                assertNull(state.restorePreview)
                assertTrue(state.beginRestoreSelection())
                state.restoreFile(Uri.fromFile(file), context.contentResolver)
                awaitIdle(state)
                assertNull(state.restoredCars)
                assertTrue(state.canRestore(BackupCurrency("RUB")))
                state.confirmRestore(BackupCurrency("RUB")) { content, currency ->
                    assertEquals(knownCurrencyCopy, content)
                    assertEquals(BackupCurrency("RUB"), currency)
                    throw IOException("database write failure")
                }
                awaitIdle(state)
                assertEquals(R.string.backup_restore_failed, state.status)
                assertNull(state.restoredCars)
            }
        } finally { file.delete() }
    }

    @Test
    fun choosingAFileDoesNotRestoreAndMismatchOrCancellationCannotCallTheWriter() = runBlocking {
        val file = File.createTempFile("backup-dialog-", ".json", context.cacheDir)
        file.writeText(knownCurrencyCopy)
        try {
            withContext(Dispatchers.Main) {
                val state = BackupDialogViewModel()
                state.beginRestoreSelection()
                state.restoreFile(Uri.fromFile(file), context.contentResolver)
                awaitIdle(state)
                assertEquals(1, state.restorePreview!!.carCount)
                assertNull(state.restoredCars)
                assertFalse(state.canRestore(BackupCurrency("USD")))
                state.confirmRestore(BackupCurrency("USD")) { _, _ -> error("Currency mismatch must not write") }
                state.cancelRestore()
                assertNull(state.restorePreview)
                state.confirmRestore(BackupCurrency("RUB")) { _, _ -> error("Cancelled preview must not write") }
            }
        } finally { file.delete() }
    }

    @Test
    fun unknownCurrencyRequiresExplicitConfirmationForTheCurrentCurrencyAndEachFile() = runBlocking {
        val file = File.createTempFile("backup-dialog-", ".json", context.cacheDir)
        try {
            for (copy in listOf(
                """{"format":4,"createdAt":"2026-10-22","cars":[{"name":"Legacy"}]}""",
                knownCurrencyCopy.replace("\"RUB\"", "null"),
            )) {
                file.writeText(copy)
                withContext(Dispatchers.Main) {
                    val state = BackupDialogViewModel()
                    state.beginRestoreSelection()
                    state.restoreFile(Uri.fromFile(file), context.contentResolver)
                    awaitIdle(state)
                    val rub = BackupCurrency("RUB")
                    assertFalse(state.canRestore(rub))
                    state.confirmRestore(rub) { _, _ -> error("Unknown currency must be confirmed first") }
                    state.confirmUnknownCurrency(rub)
                    assertTrue(state.canRestore(rub))
                    assertFalse(state.canRestore(BackupCurrency("USD")))
                    state.confirmUnknownCurrency(null)
                    assertFalse(state.canRestore(rub))
                    state.confirmUnknownCurrency(rub)
                    state.confirmRestore(rub) { content, currency ->
                        assertEquals(copy, content)
                        assertEquals(rub, currency)
                        1
                    }
                    awaitIdle(state)
                    assertEquals(1, state.restoredCars)
                    assertNull(state.restorePreview)
                    state.beginRestoreSelection()
                    state.restoreFile(Uri.fromFile(file), context.contentResolver)
                    awaitIdle(state)
                    assertFalse(state.canRestore(rub))
                }
            }
        } finally { file.delete() }
    }

    @Test
    fun currencyChangeAtCommitIsVisibleAndMalformedMetadataNeverReachesConfirmation() = runBlocking {
        val file = File.createTempFile("backup-dialog-", ".json", context.cacheDir)
        try {
            file.writeText(knownCurrencyCopy)
            withContext(Dispatchers.Main) {
                val state = BackupDialogViewModel()
                state.beginRestoreSelection()
                state.restoreFile(Uri.fromFile(file), context.contentResolver)
                awaitIdle(state)
                state.confirmRestore(BackupCurrency("RUB")) { _, _ -> throw BackupCurrencyMismatchException() }
                awaitIdle(state)
                assertEquals(R.string.backup_currency_changed, state.status)
                assertNull(state.restoredCars)
                state.reset()
                assertNull(state.restorePreview)
                file.writeText(knownCurrencyCopy.replace("\"RUB\"", "\"rub\""))
                state.beginRestoreSelection()
                state.restoreFile(Uri.fromFile(file), context.contentResolver)
                awaitIdle(state)
                assertEquals(R.string.backup_not_ours, state.status)
                assertNull(state.restorePreview)
            }
        } finally { file.delete() }
    }

    private val knownCurrencyCopy =
        """{"format":5,"createdAt":"2026-10-22","currency":{"code":"RUB"},"cars":[{"name":"Source"}]}"""

    private suspend fun awaitIdle(state: BackupDialogViewModel) = withTimeout(5_000) {
        while (state.busy) delay(10)
    }
}
