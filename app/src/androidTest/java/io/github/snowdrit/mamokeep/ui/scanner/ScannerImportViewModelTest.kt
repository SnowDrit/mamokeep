package io.github.snowdrit.mamokeep.ui.scanner

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.snowdrit.mamokeep.MamoKeepApp
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.data.scanner.MAX_SCANNER_TEXT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** User paths through the retained scanner, without a database save or an OCR model. */
@RunWith(AndroidJUnit4::class)
class ScannerImportViewModelTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val manager get() = (context as MamoKeepApp).container.scannerModels
    private val receipt = "22.10.2026\nАИ-95 20.50 л\nПробег 15000 км\nИтого: 1230.00"

    @Test
    fun pastedReceiptRemainsVisibleInBothReviewPathsWithoutAnImportedImage() = runBlocking {
        withContext(Dispatchers.Main) {
            val model = ScannerViewModel(context, SavedStateHandle())
            model.updateText(receipt)
            model.review(ScannerRoute.FUEL)
            assertEquals(ScannerRoute.FUEL, model.route)
            assertEquals(receipt, model.reviewSourceText)
            assertEquals(receipt, model.draft!!.sourceText)
            assertNull(model.image)
            assertFalse(model.recordSaved)

            model.backToSource()
            val correction = "$receipt\nПроверено вручную"
            model.updateText(correction)
            model.review(ScannerRoute.WORK)
            assertEquals(ScannerRoute.WORK, model.route)
            assertEquals(correction, model.reviewSourceText)
            assertEquals(correction, model.workNote)
            assertFalse(model.recordSaved)
            model.reset()
        }
    }

    @Test
    fun correctingAnOversizedPasteRemovesTheErrorAndKeepsTheWholeAcceptedText() = runBlocking {
        withContext(Dispatchers.Main) {
            val model = ScannerViewModel(context, SavedStateHandle())
            model.updateText(receipt)
            model.updateText("x".repeat(MAX_SCANNER_TEXT + 1))
            assertEquals(receipt, model.text) // An oversized paste must not silently truncate or erase earlier input.
            assertEquals(R.string.scanner_import_too_large, model.error)

            val correction = "$receipt\nТопливо оплачено отдельно"
            model.updateText(correction)
            assertNull(model.error)
            model.review(ScannerRoute.FUEL)
            assertEquals(correction, model.draft!!.sourceText)
            assertEquals(correction, model.reviewSourceText)
            model.reset()
        }
    }

    @Test
    fun editingASharedReceiptKeepsTheImportedOriginalAvailableForComparison() = runBlocking {
        val model = withContext(Dispatchers.Main) { ScannerViewModel(context, SavedStateHandle()) }
        val readingJobs = withContext(Dispatchers.Main) {
            model.importInput(ScannerInputSource.Text(receipt), manager)
            model.viewModelScope.coroutineContext[Job]!!.children.toList()
        }
        readingJobs.joinAll()
        withContext(Dispatchers.Main) {
            assertFalse(model.busy)
            assertNull(model.error)
            val corrected = receipt.replace("20.50", "20.55")
            model.updateText(corrected)
            model.review(ScannerRoute.WORK)
            assertEquals(receipt, model.reviewSourceText)
            assertEquals(corrected, model.draft!!.sourceText)
            assertEquals(corrected, model.workNote)
            assertFalse(model.recordSaved)
            model.reset()
        }
    }

    @Test
    fun cancellingAFileReadCannotReplaceNewManualInputAndCleansItsTemporaryCopy() = runBlocking {
        val source = File.createTempFile("scanner-cancel-source-", ".txt", context.cacheDir)
        source.writeText("Old receipt\n".repeat(5_000))
        val before = temporaryInputs()
        val model = withContext(Dispatchers.Main) { ScannerViewModel(context, SavedStateHandle()) }
        try {
            val readingJobs = withContext(Dispatchers.Main) {
                model.importInput(ScannerInputSource.Content(Uri.fromFile(source), "text/plain"), manager)
                val jobs = model.viewModelScope.coroutineContext[Job]!!.children.toList()
                model.cancelRecognition()
                model.updateText(receipt)
                jobs
            }
            // Wait for the cancelled reader and its cleanup, including a read already running on IO.
            readingJobs.joinAll()
            withContext(Dispatchers.Main) {
                assertFalse(model.busy)
                assertNull(model.error)
                model.review(ScannerRoute.FUEL)
                assertEquals(receipt, model.draft!!.sourceText)
                assertEquals(receipt, model.reviewSourceText)
                assertFalse(model.recordSaved)
            }
            assertEquals(before, temporaryInputs())
            assertEquals("Old receipt\n".repeat(5_000), source.readText())
        } finally {
            withContext(Dispatchers.Main) { model.reset() }
            source.delete()
        }
    }

    private fun temporaryInputs() = context.cacheDir.listFiles().orEmpty()
        .filter { it.name.startsWith("ocr-input-") }.toSet()
}
