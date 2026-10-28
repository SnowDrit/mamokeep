package io.github.snowdrit.mamokeep.data.repo

import io.github.snowdrit.mamokeep.domain.export.BackupCurrency
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.snowdrit.mamokeep.data.ReminderSettingsStore
import io.github.snowdrit.mamokeep.data.db.CarEntity
import io.github.snowdrit.mamokeep.data.db.MamoKeepDatabase
import io.github.snowdrit.mamokeep.domain.ItemKind
import io.github.snowdrit.mamokeep.domain.MaintenanceItem
import io.github.snowdrit.mamokeep.domain.MaintenanceCalculator
import io.github.snowdrit.mamokeep.domain.ReminderRule
import io.github.snowdrit.mamokeep.domain.ReminderSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** Запускать только на эмуляторе: тест временно использует хранилище напоминаний приложения. */
@RunWith(AndroidJUnit4::class)
class BackupReminderRestoreTest {

    @Test
    fun unchangedSavePreservesLimitAndServiceStartsANewCycle() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MamoKeepDatabase::class.java).build()
        val store = ReminderSettingsStore(context)
        val before = store.exportSettings()
        try {
            store.replaceSettings(ReminderSettings())
            val dao = database.garageDao()
            val repository = GarageRepository(database, dao, reminderSettings = store, currentCurrencyCode = { "RUB" })
            val carId = dao.insertCar(CarEntity(name = "Test car"))
            val rule = ReminderRule(advancedEnabled = true, redMaxOccurrences = 1)
            val oilId = repository.saveItemWithReminder(carId, item("Oil"), rule)
            val saved = item("Oil").copy(id = oilId)
            val today = LocalDate.of(2026, 10, 7)
            val now = today.atTime(10, 0).atZone(ZoneId.of("Europe/Moscow"))
            val status = MaintenanceCalculator.status(saved, 30_001, today)
            assertNotNull(store.claimNotification(status, now))

            repository.saveItemWithReminder(carId, saved, rule)
            assertNull(store.claimNotification(status, now.plusDays(1)))
            repository.saveItemWithReminder(carId, saved, rule.copy(minuteOfDay = 8 * 60))
            assertNull(store.claimNotification(status, now.plusHours(1)))
            assertNull(store.claimNotification(status, now.plusDays(1)))

            // Две реальные замены иногда отмечают тем же числом и пробегом: даже
            // одинаковые значения новой записи должны начать новый цикл напоминаний.
            repository.markDone(oilId, saved.lastDoneKm, requireNotNull(saved.lastDoneDate))
            assertNotNull(store.claimNotification(status, now.plusDays(1)))

            repository.deleteItem(oilId)
            store.setItemRule(oilId, rule)
            assertNotNull(store.claimNotification(status, now.plusDays(2)))
        } finally {
            store.replaceSettings(before)
            database.close()
        }
    }

    @Test
    fun backupRemapsRulesAndDeletionRemovesOrphans() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MamoKeepDatabase::class.java).build()
        val store = ReminderSettingsStore(context)
        val before = store.exportSettings()
        try {
            store.replaceSettings(ReminderSettings())
            val dao = database.garageDao()
            val repository = GarageRepository(database, dao, reminderSettings = store, currentCurrencyCode = { "RUB" })
            val global = ReminderRule(minuteOfDay = 21 * 60, weekdays = setOf(3))
            val custom = ReminderRule(minuteOfDay = 8 * 60, weekdays = setOf(1, 4), advancedEnabled = true, redAdvanceKm = 300)
            store.setDefaultRule(global)
            val carId = dao.insertCar(CarEntity(name = "Test car"))
            val oilId = repository.saveItemWithReminder(carId, item("Oil"), custom)
            val serviceId = repository.saveItemWithReminder(carId, item("Service"), null)

            val exported = repository.exportBackup(LocalDate.of(2026, 10, 7))
            assertEquals(1, repository.importBackup(exported, BackupCurrency("RUB")))
            val restoredCarId = dao.allCars().single().id
            val restoredItems = dao.items(restoredCarId)
            val restoredOilId = restoredItems.single { it.title == "Oil" }.id
            val restoredServiceId = restoredItems.single { it.title == "Service" }.id
            assertFalse(oilId == restoredOilId)
            assertFalse(serviceId == restoredServiceId)
            assertEquals(ReminderSettings(global, mapOf(restoredOilId to custom)), store.exportSettings())
            assertEquals(exported, repository.exportBackup(LocalDate.of(2026, 10, 7)))

            repository.deleteItem(restoredOilId)
            assertTrue(store.exportSettings().itemOverrides.isEmpty())
            repository.saveItemWithReminder(restoredCarId, item("Service").copy(id = restoredServiceId), custom)
            repository.deleteCar(restoredCarId)
            assertTrue(store.exportSettings().itemOverrides.isEmpty())
            assertEquals(global, store.exportSettings().defaultRule)

            store.setDefaultRule(custom)
            store.setItemRule(999L, custom)
            assertEquals(1, repository.importBackup("""{"format":3,"createdAt":"2026-01-01","cars":[{"name":"Legacy"}]}""", BackupCurrency("RUB")))
            assertEquals(ReminderSettings(), store.exportSettings())
        } finally {
            store.replaceSettings(before)
            database.close()
        }
    }

    private fun item(title: String) = MaintenanceItem(
        id = 0,
        kind = ItemKind.MAINTENANCE,
        title = title,
        intervalKm = 10_000,
        intervalMonths = 12,
        lastDoneKm = 20_000,
        lastDoneDate = LocalDate.of(2026, 1, 1),
        warnKm = 500,
        warnDays = 14,
    )
}
