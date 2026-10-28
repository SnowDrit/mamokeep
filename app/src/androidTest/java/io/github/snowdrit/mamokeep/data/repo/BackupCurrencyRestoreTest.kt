package io.github.snowdrit.mamokeep.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.snowdrit.mamokeep.data.SettingsStore
import io.github.snowdrit.mamokeep.data.db.CarEntity
import io.github.snowdrit.mamokeep.data.db.FuelEntryEntity
import io.github.snowdrit.mamokeep.data.db.MamoKeepDatabase
import io.github.snowdrit.mamokeep.domain.Country
import io.github.snowdrit.mamokeep.domain.RegionalCatalog
import io.github.snowdrit.mamokeep.domain.UnitSystem
import io.github.snowdrit.mamokeep.domain.export.BackupCodec
import io.github.snowdrit.mamokeep.domain.export.BackupCurrency
import io.github.snowdrit.mamokeep.domain.export.BackupCurrencyMismatchException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.UUID

/** Two isolated garages/settings stores model restore onto a different regional installation. */
@RunWith(AndroidJUnit4::class)
class BackupCurrencyRestoreTest {
    private lateinit var sourceDb: MamoKeepDatabase
    private lateinit var targetDb: MamoKeepDatabase
    private lateinit var sourceSettings: SettingsStore
    private lateinit var targetSettings: SettingsStore
    private lateinit var source: GarageRepository
    private lateinit var target: GarageRepository
    private val scopes = mutableListOf<CoroutineScope>()
    private val files = mutableListOf<File>()
    private val catalog = RegionalCatalog(listOf(Country("ru", "RUB"), Country("us", "USD")))
    private val date = LocalDate.of(2026, 10, 22)

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        sourceDb = Room.inMemoryDatabaseBuilder(context, MamoKeepDatabase::class.java).build()
        targetDb = Room.inMemoryDatabaseBuilder(context, MamoKeepDatabase::class.java).build()
        sourceSettings = isolatedSettings()
        targetSettings = isolatedSettings()
        sourceSettings.setRegion("ru")
        sourceSettings.setUnitSystem(UnitSystem.METRIC)
        targetSettings.setRegion("us")
        targetSettings.setUnitSystem(UnitSystem.US)
        source = GarageRepository(sourceDb, sourceDb.garageDao(), currentCurrencyCode = {
            catalog.currencyCode(sourceSettings.regionalPreferences.first())
        })
        target = GarageRepository(targetDb, targetDb.garageDao(), currentCurrencyCode = {
            catalog.currencyCode(targetSettings.regionalPreferences.first())
        })
        val sourceCar = sourceDb.garageDao().insertCar(CarEntity(name = "Source RUB garage"))
        sourceDb.garageDao().insertFuelEntry(FuelEntryEntity(
            carId = sourceCar, date = date, odometerKm = 15_000, amount = 20.5,
            costKopecks = 123_000, isFull = true,
        ))
        targetDb.garageDao().insertCar(CarEntity(name = "Preserve this US garage"))
        Unit
    }

    @After
    fun tearDown() = runBlocking {
        sourceDb.close()
        targetDb.close()
        scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
        files.forEach { it.delete() }
    }

    private fun isolatedSettings(): SettingsStore {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "backup-currency-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        files.add(file)
        scopes.add(scope)
        return SettingsStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
    }

    @Test
    fun rubBackupIsRejectedOnUsdInstallationBeforeAnyDataOrSettingsChange() = runBlocking {
        val before = target.exportBackup(date)
        val preferences = targetSettings.regionalPreferences.first()
        val copy = source.exportBackup(date)
        assertEquals(BackupCurrency("RUB"), requireNotNull(BackupCodec.decode(copy)).currency)
        expectMismatch { target.importBackup(copy, BackupCurrency("USD")) }
        assertEquals(before, target.exportBackup(date))
        assertEquals(preferences, targetSettings.regionalPreferences.first())
        assertEquals(UnitSystem.US, targetSettings.unitSystem.first())
    }

    @Test
    fun matchingExplicitRubRestoresExactNumbersButKeepsDestinationCountryAndUnits() = runBlocking {
        targetSettings.setCurrency("RUB") // The owner changes this explicitly before restore.
        val preferences = targetSettings.regionalPreferences.first()
        assertEquals(1, target.importBackup(source.exportBackup(date), BackupCurrency("RUB")))
        val car = targetDb.garageDao().allCars().single()
        val fuel = targetDb.garageDao().fuelEntries(car.id).single()
        assertEquals(123_000L, fuel.costKopecks)
        assertEquals(20.5.toBits(), fuel.amount.toBits())
        assertEquals(15_000, fuel.odometerKm)
        assertEquals(preferences, targetSettings.regionalPreferences.first())
        assertEquals("us", preferences.countryCode)
        assertEquals(UnitSystem.US, targetSettings.unitSystem.first())
    }

    @Test
    fun directRepositoryExportUsesManualUsdEvenWhenSourceCountryIsRussia() = runBlocking {
        sourceSettings.setCurrency("USD")
        // HomeViewModel and BackupWorker both call this same repository export path.
        val copy = source.exportBackup(date)
        assertEquals("ru", sourceSettings.regionalPreferences.first().countryCode)
        assertEquals(BackupCurrency("USD"), requireNotNull(BackupCodec.decode(copy)).currency)
        assertEquals(1, target.importBackup(copy, BackupCurrency("USD")))
    }

    @Test
    fun legacyConfirmationMustMatchTheActualCurrentCurrency() = runBlocking {
        val legacy = requireNotNull(BackupCodec.decode(source.exportBackup(date))).copy(format = 4, currency = null)
        val content = BackupCodec.encode(legacy)
        val before = target.exportBackup(date)
        expectMismatch { target.importBackup(content, BackupCurrency("RUB")) }
        assertEquals(before, target.exportBackup(date))
        assertEquals(1, target.importBackup(content, BackupCurrency("USD")))
    }

    @Test
    fun changingCurrencyAfterPreviewInvalidatesConfirmationWithoutReplacingGarage() = runBlocking {
        sourceSettings.setCurrency("USD")
        val copy = source.exportBackup(date)
        targetSettings.setCurrency("RUB")
        val before = target.exportBackup(date)
        expectMismatch { target.importBackup(copy, BackupCurrency("USD")) }
        assertEquals(before, target.exportBackup(date))
    }

    @Test
    fun explicitUnknownCurrencyCanBeAssignedByConfirmedOwnerChoice() = runBlocking {
        sourceSettings.setRegion("zz")
        val copy = source.exportBackup(date)
        assertEquals(BackupCurrency(null), requireNotNull(BackupCodec.decode(copy)).currency)
        assertEquals(1, target.importBackup(copy, BackupCurrency("USD")))
        targetSettings.setRegion("zz")
        assertEquals(1, target.importBackup(copy, BackupCurrency(null)))
    }

    @Test
    fun malformedMetadataAndCurrencyReadFailureDoNotMutateGarage() = runBlocking {
        val before = target.exportBackup(date)
        for (metadata in listOf("", ",\"currency\":null", ",\"currency\":{}", ",\"currency\":{\"code\":\"rub\"}")) {
            assertNull(target.importBackup("""{"format":5,"createdAt":"2026-10-22","cars":[]$metadata}""", BackupCurrency("USD")))
            assertEquals(before, target.exportBackup(date))
        }
        val unavailable = GarageRepository(targetDb, targetDb.garageDao(), currentCurrencyCode = {
            throw IOException("injected settings read failure")
        })
        try {
            unavailable.importBackup(source.exportBackup(date), BackupCurrency("USD"))
            fail("Settings failure must reach the caller")
        } catch (_: IOException) { }
        assertEquals(before, target.exportBackup(date))
    }

    private suspend fun expectMismatch(block: suspend () -> Unit) {
        try {
            block()
            fail("Currency mismatch must reject the restore")
        } catch (_: BackupCurrencyMismatchException) { }
    }
}
