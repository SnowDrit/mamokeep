package io.github.snowdrit.mamokeep.data.repo

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.snowdrit.mamokeep.data.db.CarEntity
import io.github.snowdrit.mamokeep.data.db.FuelEntryEntity
import io.github.snowdrit.mamokeep.data.db.GarageDao
import io.github.snowdrit.mamokeep.data.db.MaintenanceItemEntity
import io.github.snowdrit.mamokeep.data.db.MamoKeepDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class OdometerHistoryTest {
    private lateinit var database: MamoKeepDatabase
    private lateinit var dao: GarageDao
    private lateinit var repository: GarageRepository
    private var car = 0L
    private val day = LocalDate.of(2026, 10, 22)

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, MamoKeepDatabase::class.java).build()
        dao = database.garageDao()
        repository = GarageRepository(database, dao, currentCurrencyCode = { "RUB" })
        car = dao.insertCar(CarEntity(name = "Odometer test"))
        dao.insertFuelEntry(FuelEntryEntity(carId = car, date = day.minusDays(1), odometerKm = 14_900,
            amount = 20.0, costKopecks = 100_000, isFull = true))
    }

    @After
    fun tearDown() { database.close() }

    @Test
    fun fixingAnExtraZeroRecalculatesMileageAndLeavesFuelAndServiceFactsIntact() = runBlocking {
        val item = dao.insertItem(MaintenanceItemEntity(carId = car, title = "Oil", intervalKm = 10_000,
            intervalMonths = null, lastDoneKm = null, lastDoneDate = null, warnKm = 500, warnDays = 14))
        repository.markDone(item, 14_000, day.minusDays(5))
        repository.addOdometerReading(car, 150_000, day)
        val reading = repository.observeStandaloneOdometerReadings(car).first().single()
        val fuel = dao.fuelEntries(car)
        val records = dao.recordsOf(item)
        assertEquals(150_000, repository.mileageNow(car, day)?.km)
        assertEquals(-126_000, repository.observeOverview(car) { day }.first()?.statuses?.single()?.kmLeft)

        repository.updateOdometerReading(car, reading.id, 15_000, day)

        assertEquals(15_000, repository.mileageNow(car, day)?.km)
        val overview = withTimeout(5_000) { repository.observeOverview(car) { day }.first() }
        assertEquals(15_000, overview?.mileage?.km)
        assertEquals(9_000, overview?.statuses?.single()?.kmLeft)
        assertEquals(15_000, repository.observeStandaloneOdometerReadings(car).first().single().km)
        assertEquals(fuel, dao.fuelEntries(car))
        assertEquals(records, dao.recordsOf(item))
    }

    @Test
    fun deletionTargetsOnlyTheSelectedIdAndRecalculatesFromRemainingSources() = runBlocking {
        repository.addOdometerReading(car, 15_000, day)
        repository.addOdometerReading(car, 150_000, day)
        val rows = repository.observeStandaloneOdometerReadings(car).first()
        val correct = rows.single { it.km == 15_000 }
        val wrong = rows.single { it.km == 150_000 }
        val fuel = dao.fuelEntries(car)

        repository.deleteOdometerReading(car, wrong.id)
        assertEquals(listOf(correct), repository.observeStandaloneOdometerReadings(car).first())
        assertEquals(15_000, repository.mileageNow(car, day)?.km)
        assertEquals(fuel, dao.fuelEntries(car))

        repository.deleteOdometerReading(car, correct.id)
        assertTrue(repository.observeStandaloneOdometerReadings(car).first().isEmpty())
        assertEquals(14_900, repository.mileageNow(car, day.minusDays(1))?.km)
        assertEquals(fuel, dao.fuelEntries(car))
    }

    @Test
    fun foreignCarAndDeletedIdsFailWithoutChangingAnyReading() = runBlocking {
        repository.addOdometerReading(car, 15_000, day)
        val row = repository.observeStandaloneOdometerReadings(car).first().single()
        val other = dao.insertCar(CarEntity(name = "Other car"))
        val before = repository.exportBackup(day)
        expectMissing { repository.updateOdometerReading(other, row.id, 1, day) }
        expectMissing { repository.deleteOdometerReading(other, row.id) }
        assertEquals(before, repository.exportBackup(day))
        repository.deleteOdometerReading(car, row.id)
        expectMissing { repository.updateOdometerReading(car, row.id, 1, day) }
        expectMissing { repository.deleteOdometerReading(car, row.id) }
    }

    @Test
    fun invalidValueAndPersistenceFailureRemainVisibleAndPreserveData() = runBlocking {
        repository.addOdometerReading(car, 15_000, day)
        val row = repository.observeStandaloneOdometerReadings(car).first().single()
        val before = repository.exportBackup(day)
        try {
            repository.updateOdometerReading(car, row.id, -1, day)
            fail("Negative mileage must fail")
        } catch (_: IllegalArgumentException) { }
        val broken = GarageRepository(database, object : GarageDao by dao {
            override suspend fun updateReading(carId: Long, id: Long, km: Int, date: LocalDate): Int =
                throw IOException("injected database failure")
        }, currentCurrencyCode = { "RUB" })
        try {
            broken.updateOdometerReading(car, row.id, 20_000, day)
            fail("A database failure must reach the caller")
        } catch (_: IOException) { }
        assertEquals(before, repository.exportBackup(day))
    }

    private suspend fun expectMissing(block: suspend () -> Unit) {
        try {
            block()
            fail("A foreign or missing reading must not report success")
        } catch (_: IllegalStateException) { }
    }
}
