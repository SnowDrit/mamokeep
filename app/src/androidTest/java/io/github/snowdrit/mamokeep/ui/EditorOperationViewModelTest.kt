package io.github.snowdrit.mamokeep.ui

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.snowdrit.mamokeep.ui.scanner.EditorOperationViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class EditorOperationViewModelTest {
    @Test
    fun completionWaitsForTheDatabaseAndDuplicateSaveDoesNotStartAnotherWrite() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val handle = SavedStateHandle()
        val model = withContext(Dispatchers.Main) { EditorOperationViewModel(handle).also { it.attach("fuel") } }
        var writes = 0
        withContext(Dispatchers.Main) {
            model.run {
                writes++
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        withContext(Dispatchers.Main) {
            assertTrue(model.busy)
            assertFalse(model.completed)
            assertEquals(true, handle.get<Boolean>("in_flight"))
            model.attach("fuel") // A recreated editor attaches to the same retained operation.
            model.run { writes++ }
        }
        release.complete(Unit)
        awaitIdle(model)
        withContext(Dispatchers.Main) {
            assertEquals(1, writes)
            assertTrue(model.completed)
            assertFalse(model.failed)
            assertEquals(false, handle.get<Boolean>("in_flight"))
        }
    }

    @Test
    fun aWriteFailureKeepsTheEditorOpenAndAnExplicitRetryCanComplete() = runBlocking {
        val model = withContext(Dispatchers.Main) { EditorOperationViewModel(SavedStateHandle()).also { it.attach("fuel") } }
        withContext(Dispatchers.Main) { model.run { throw IOException("disk full") } }
        awaitIdle(model)
        withContext(Dispatchers.Main) {
            assertTrue(model.failed)
            assertFalse(model.completed)
            model.run { }
        }
        awaitIdle(model)
        withContext(Dispatchers.Main) {
            assertTrue(model.completed)
            assertFalse(model.failed)
        }
    }

    @Test
    fun processRecreationReportsAnInterruptedSaveWithoutReplayingIt() = runBlocking {
        withContext(Dispatchers.Main) {
            val model = EditorOperationViewModel(SavedStateHandle(mapOf("session" to "fuel", "in_flight" to true)))
            model.attach("fuel")
            assertTrue(model.interrupted)
            assertFalse(model.busy)
            assertFalse(model.completed)
            model.reset()
            model.attach("another-entry")
            assertFalse(model.interrupted)
        }
    }

    private suspend fun awaitIdle(model: EditorOperationViewModel) = withTimeout(5_000) {
        while (withContext(Dispatchers.Main) { model.busy }) delay(10)
    }
}
