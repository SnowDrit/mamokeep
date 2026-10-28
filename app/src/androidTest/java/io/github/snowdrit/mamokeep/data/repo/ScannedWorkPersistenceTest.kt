package io.github.snowdrit.mamokeep.data.repo

import io.github.snowdrit.mamokeep.domain.export.BackupCurrency
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.snowdrit.mamokeep.data.db.CarEntity
import io.github.snowdrit.mamokeep.data.db.MaintenanceItemEntity
import io.github.snowdrit.mamokeep.data.db.MamoKeepDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ScannedWorkPersistenceTest {
    @Test fun reviewedWorkKeepsItsTextInJournalExportBackupAndRestore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, MamoKeepDatabase::class.java).build()
        val restored = Room.inMemoryDatabaseBuilder(context, MamoKeepDatabase::class.java).build()
        try {
            val dao = db.garageDao()
            val repo = GarageRepository(db, dao, currentCurrencyCode = { "RUB" })
            val car = dao.insertCar(CarEntity(name = "Scanner test"))
            val item = dao.insertItem(MaintenanceItemEntity(carId = car, title = "Oil", intervalKm = 10_000,
                intervalMonths = null, lastDoneKm = null, lastDoneDate = null, warnKm = 500, warnDays = 14))
            val date = LocalDate.of(2026, 10, 22)
            val text = "Verified service order\nOil replaced\nFilter replaced"
            assertTrue(repo.observeJournal(car).first().isEmpty())
            repo.markDone(item, 15_000, date, text)
            val entry = repo.observeJournal(car).first().single()
            assertEquals(text, entry.note)
            assertEquals(text, repo.snapshot(car, date)!!.history.single().note)
            val copy = repo.exportBackup(date)
            val target = GarageRepository(restored, restored.garageDao(), currentCurrencyCode = { "RUB" })
            assertEquals(1, target.importBackup(copy, BackupCurrency("RUB")))
            val newCar = restored.garageDao().allCars().single().id
            assertEquals(text, target.observeJournal(newCar).first().single().note)
            repo.updateRecord(entry.recordId!!, item, date, 15_010, "Corrected note")
            assertEquals("Corrected note", repo.observeJournal(car).first().single().note)
            repo.updateRecord(entry.recordId, item, date, 15_020)
            assertEquals("Corrected note", repo.observeJournal(car).first().single().note)
            repo.updateRecord(entry.recordId, item, date, 15_020, "")
            assertEquals("", repo.observeJournal(car).first().single().note)
        } finally { restored.close(); db.close() }
    }
}
