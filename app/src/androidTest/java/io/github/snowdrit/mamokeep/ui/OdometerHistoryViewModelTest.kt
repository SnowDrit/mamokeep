package io.github.snowdrit.mamokeep.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.snowdrit.mamokeep.data.repo.StandaloneOdometerReading
import io.github.snowdrit.mamokeep.domain.UnitSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class OdometerHistoryViewModelTest {
    private val reading = StandaloneOdometerReading(7, 2, LocalDate.of(2026, 10, 22), 150_001)

    @Test
    fun pendingWriteKeepsEditorOpenAndPreventsDuplicateSaving() = runBlocking {
        withContext(Dispatchers.Main) {
            val state = OdometerHistoryViewModel()
            state.edit(reading, UnitSystem.METRIC)
            state.changeDistance("15000")
            val complete = CompletableDeferred<Unit>()
            var calls = 0
            state.save(15_000) { id, km, date ->
                calls++
                assertEquals(reading.id, id)
                assertEquals(15_000, km)
                assertEquals(reading.date, date)
                complete.await()
            }
            assertTrue(state.busy)
            assertNotNull(state.editing)
            state.save(15_000) { _, _, _ -> calls++ }
            state.changeDistance("99")
            assertEquals("15000", state.distance)
            complete.complete(Unit)
            awaitIdle(state)
            assertEquals(1, calls)
            assertNull(state.editing)
            assertFalse(state.writeFailed)
        }
    }

    @Test
    fun failedSaveOrDeleteKeepsTheDraftAndShowsFailure() = runBlocking {
        withContext(Dispatchers.Main) {
            val state = OdometerHistoryViewModel()
            state.edit(reading, UnitSystem.METRIC)
            state.changeDistance("15000")
            state.save(15_000) { _, _, _ -> throw IOException("write failed") }
            awaitIdle(state)
            assertTrue(state.writeFailed)
            assertEquals(reading, state.editing)
            assertEquals("15000", state.distance)
            state.confirmDelete()
            state.delete { throw IOException("delete failed") }
            awaitIdle(state)
            assertTrue(state.writeFailed)
            assertTrue(state.confirmingDelete)
            assertEquals(reading, state.editing)
            state.cancelDelete()
            assertFalse(state.confirmingDelete)
            assertEquals("15000", state.distance)
        }
    }

    @Test
    fun unchangedMilesRetainsExactKilometresAndCancelDoesNotCallPersistence() = runBlocking {
        withContext(Dispatchers.Main) {
            val state = OdometerHistoryViewModel()
            state.edit(reading, UnitSystem.US)
            assertEquals(reading.km, state.enteredKm(UnitSystem.US))
            state.confirmDelete()
            state.cancelDelete()
            state.reset()
            assertNull(state.editing)
            assertFalse(state.busy)
        }
    }

    private suspend fun awaitIdle(state: OdometerHistoryViewModel) = withTimeout(5_000) {
        while (state.busy) delay(10)
    }
}
