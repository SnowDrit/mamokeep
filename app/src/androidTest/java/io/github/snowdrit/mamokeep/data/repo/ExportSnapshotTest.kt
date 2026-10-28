package io.github.snowdrit.mamokeep.data.repo

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.snowdrit.mamokeep.data.db.CarEntity
import io.github.snowdrit.mamokeep.data.db.FuelEntryEntity
import io.github.snowdrit.mamokeep.data.db.MaintenanceItemEntity
import io.github.snowdrit.mamokeep.data.db.MaintenanceRecordEntity
import io.github.snowdrit.mamokeep.data.db.MamoKeepDatabase
import io.github.snowdrit.mamokeep.data.db.OdometerReadingEntity
import io.github.snowdrit.mamokeep.domain.FuelCalculator
import io.github.snowdrit.mamokeep.domain.ItemKind
import io.github.snowdrit.mamokeep.domain.ItemTitles
import io.github.snowdrit.mamokeep.domain.OdometerReading
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Настоящий Room-запрос; отдельная база в памяти не затрагивает данные приложения. */
@RunWith(AndroidJUnit4::class)
class ExportSnapshotTest {

    @Test
    fun snapshotRetainsDocumentNotesAndManualReadingsOnlyForSelectedCar() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MamoKeepDatabase::class.java).build()
        try {
            val dao = database.garageDao()
            val repository = GarageRepository(database, dao, ItemTitles { key, stored ->
                if (key == "document_test") "Translated document" else stored
            }, currentCurrencyCode = { "RUB" })
            val today = LocalDate.of(2026, 9, 10)
            val car = dao.insertCar(CarEntity(name = "Selected car"))
            val other = dao.insertCar(CarEntity(name = "Other car"))
            dao.insertReading(OdometerReadingEntity(carId = car, date = today.minusDays(10), km = 10_000))
            dao.insertReading(OdometerReadingEntity(carId = other, date = today, km = 90_000))

            suspend fun addFuel(carId: Long, date: LocalDate, km: Int) {
                dao.insertFuelEntry(
                    FuelEntryEntity(
                        carId = carId, date = date, odometerKm = km,
                        amount = 40.0, costKopecks = 280_000, isFull = true,
                    )
                )
            }
            addFuel(car, today.minusDays(5), 10_100)
            addFuel(car, today, 10_600)
            addFuel(other, today, 90_000)

            suspend fun addDocument(carId: Long, note: String): Long {
                val item = dao.insertItem(
                    MaintenanceItemEntity(
                        carId = carId, kind = ItemKind.DOCUMENT, title = "Stored document",
                        titleKey = "document_test", intervalKm = null, intervalMonths = 12,
                        lastDoneKm = null, lastDoneDate = today, warnKm = 0, warnDays = 30,
                    )
                )
                dao.insertRecord(
                    MaintenanceRecordEntity(
                        carId = carId, itemId = item, date = today, km = null, note = note,
                    )
                )
                return item
            }
            val note = "Document renewed\nReceipt kept separately."
            val document = addDocument(car, note)
            addDocument(other, "Must not appear in the selected car export")

            val snapshot = requireNotNull(repository.snapshot(car, today))
            assertEquals("Selected car", snapshot.carName)
            assertEquals(listOf(OdometerReading(today.minusDays(10), 10_000)), snapshot.odometerReadings)
            // Топливные показания участвуют в оценке, но не дублируют ручную ленту.
            assertEquals(10_600, snapshot.mileage?.lastKnownKm)
            assertEquals(listOf(10_100, 10_600), snapshot.fuelEntries.map { it.odometerKm })
            assertEquals(FuelCalculator.stats(snapshot.fuelEntries), snapshot.fuel)
            assertEquals(listOf(document), snapshot.statuses.map { it.item.id })
            val work = snapshot.history.single()
            assertEquals("Translated document", work.title)
            assertEquals(ItemKind.DOCUMENT, work.kind)
            assertEquals(note, work.note)
            assertEquals(today, work.date)
            assertNull(work.km)
            assertNull(repository.snapshot(Long.MAX_VALUE, today))
        } finally {
            database.close()
        }
    }
}
