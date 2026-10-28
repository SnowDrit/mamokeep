package io.github.snowdrit.mamokeep.data.repo

import io.github.snowdrit.mamokeep.domain.export.BackupCurrency
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.snowdrit.mamokeep.data.db.CarEntity
import io.github.snowdrit.mamokeep.data.db.FuelEntryEntity
import io.github.snowdrit.mamokeep.data.db.GarageDao
import io.github.snowdrit.mamokeep.data.db.MaintenanceItemEntity
import io.github.snowdrit.mamokeep.data.db.MamoKeepDatabase
import io.github.snowdrit.mamokeep.domain.ItemKind
import io.github.snowdrit.mamokeep.domain.MaintenanceItem
import io.github.snowdrit.mamokeep.domain.UnitSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.LocalDate

/** A real Room rollback is required: an interrupted write must not move service deadlines. */
@RunWith(AndroidJUnit4::class)
class RepositoryAtomicityTest {
    private lateinit var database: MamoKeepDatabase
    private lateinit var dao: GarageDao
    private lateinit var repository: GarageRepository
    private lateinit var failing: GarageRepository
    private var cancelInstead = false
    private var car = 0L
    private var oil = 0L
    private var filter = 0L
    private val date = LocalDate.of(2026, 10, 20)

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, MamoKeepDatabase::class.java).build()
        dao = database.garageDao()
        repository = GarageRepository(database, dao, currentCurrencyCode = { "RUB" })
        failing = GarageRepository(database, object : GarageDao by dao {
            override suspend fun updateItem(item: MaintenanceItemEntity) {
                if (cancelInstead) throw CancellationException("interrupted after history write")
                throw IOException("failed after history write")
            }
        }, currentCurrencyCode = { "RUB" })
        car = dao.insertCar(CarEntity(name = "Atomic garage"))
        oil = addItem("Oil")
        filter = addItem("Filter")
        repository.markDone(oil, 10_000, date.minusDays(10))
        repository.markDone(oil, 11_000, date)
        dao.insertFuelEntry(FuelEntryEntity(carId = car, date = date, odometerKm = 11_000,
            amount = 20.0, costKopecks = 100_000, isFull = true))
    }

    @After
    fun tearDown() { database.close() }

    @Test
    fun markingDoneRollsBackIfUpdatingDeadlineFails() = runBlocking {
        unchangedAfterFailure { failing.markDone(oil, 12_000, date.plusDays(1)) }
    }

    @Test
    fun movingRecordRollsBackIfUpdatingDeadlineFails() = runBlocking {
        val record = dao.recordsOf(oil).last()
        unchangedAfterFailure { failing.updateRecord(record.id, filter, date, 11_000) }
    }

    @Test
    fun deletingRecordRollsBackIfUpdatingDeadlineFails() = runBlocking {
        val record = dao.recordsOf(oil).last()
        unchangedAfterFailure { failing.deleteRecord(record.id) }
    }

    @Test
    fun deletingMixedJournalSelectionRollsBackAllRowsIfUpdatingDeadlineFails() = runBlocking {
        unchangedAfterFailure {
            failing.deleteJournalEntries(dao.fuelEntries(car).map { it.id }, dao.recordsOf(oil).map { it.id })
        }
    }

    @Test
    fun cancellationRollsBackHistoryAndPropagates() = runBlocking {
        cancelInstead = true
        val before = repository.exportBackup(date)
        try {
            failing.markDone(oil, 12_000, date.plusDays(1))
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(before, repository.exportBackup(date))
        }
    }

    @Test
    fun incompleteOrInvalidBackupDoesNotReplaceTheGarage() = runBlocking {
        val before = repository.exportBackup(date)
        for (content in listOf(
            """{"createdAt":"2026-10-20"}""",
            """{"format":4,"createdAt":"2026-10-20","cars":[{"name":"Broken","readings":[{"epochDay":9223372036854775807,"km":1}]}]}""",
        )) {
            assertNull(repository.importBackup(content, BackupCurrency("RUB")))
            assertEquals(before, repository.exportBackup(date))
        }
    }

    @Test
    fun firstLaunchFailureLeavesNoPartialCarAndCanBeRetried() = runBlocking {
        dao.deleteAllCars()
        val brokenSeed = GarageRepository(database, object : GarageDao by dao {
            override suspend fun insertItem(item: MaintenanceItemEntity): Long = throw IOException("seed write failed")
        }, currentCurrencyCode = { "RUB" })
        try {
            brokenSeed.seedIfEmpty(null, UnitSystem.METRIC, "First car")
            fail("Failed seeding must propagate")
        } catch (_: IOException) {
            assertEquals(emptyList<CarEntity>(), dao.allCars())
        }
        repository.seedIfEmpty(null, UnitSystem.METRIC, "First car")
        val created = dao.allCars().single()
        val defaults = dao.items(created.id)
        org.junit.Assert.assertTrue(defaults.isNotEmpty())
        repository.seedIfEmpty(null, UnitSystem.METRIC, "Must not replace")
        assertEquals(listOf(created), dao.allCars())
        assertEquals(defaults, dao.items(created.id))
    }

    @Test
    fun savingStaleItemEditorDoesNotUndoAServiceRecordedWhileItWasOpen() = runBlocking {
        val draft = MaintenanceItem(id = oil, kind = ItemKind.MAINTENANCE, title = "Changed title",
            intervalKm = 15_000, intervalMonths = 12, lastDoneKm = 11_000,
            lastDoneDate = date, warnKm = 500, warnDays = 14)
        repository.markDone(oil, 12_000, date.plusDays(1))
        repository.saveItemWithReminder(car, draft, null)
        val saved = requireNotNull(dao.item(oil))
        assertEquals("Changed title", saved.title)
        assertEquals(15_000, saved.intervalKm)
        assertEquals(12_000, saved.lastDoneKm)
        assertEquals(date.plusDays(1), saved.lastDoneDate)
        assertEquals(saved.lastDoneDate, dao.latestRecord(oil)?.date)
    }

    private suspend fun unchangedAfterFailure(action: suspend () -> Unit) {
        val before = repository.exportBackup(date)
        try {
            action()
            fail("Injected persistence failure must propagate")
        } catch (_: IOException) {
            assertEquals(before, repository.exportBackup(date))
        }
    }

    private suspend fun addItem(title: String): Long = dao.insertItem(MaintenanceItemEntity(
        carId = car, title = title, intervalKm = 10_000, intervalMonths = 12,
        lastDoneKm = null, lastDoneDate = null, warnKm = 500, warnDays = 14,
    ))
}
