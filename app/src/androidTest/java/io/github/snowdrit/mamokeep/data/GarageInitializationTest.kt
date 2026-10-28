package io.github.snowdrit.mamokeep.data

import io.github.snowdrit.mamokeep.domain.export.BackupCurrency
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.snowdrit.mamokeep.data.db.CarEntity
import io.github.snowdrit.mamokeep.data.db.GarageDao
import io.github.snowdrit.mamokeep.data.db.MaintenanceItemEntity
import io.github.snowdrit.mamokeep.data.db.MamoKeepDatabase
import io.github.snowdrit.mamokeep.data.repo.GarageRepository
import io.github.snowdrit.mamokeep.domain.UnitSystem
import io.github.snowdrit.mamokeep.domain.export.BackupCodec
import io.github.snowdrit.mamokeep.ui.HomeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.UUID

/** Isolated preferences and Room: never clears the installed app's data or flags. */
@RunWith(AndroidJUnit4::class)
class GarageInitializationTest {
    private lateinit var directory: File
    private lateinit var preferencesJob: Job
    private lateinit var settings: SettingsStore
    private lateinit var database: MamoKeepDatabase
    private lateinit var dao: GarageDao
    private lateinit var repository: GarageRepository
    private val models = ViewModelStore()
    private val today = LocalDate.of(2026, 10, 20)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "garage-initialization-${UUID.randomUUID()}").apply { mkdirs() }
        settings = openSettings()
        database = Room.inMemoryDatabaseBuilder(context, MamoKeepDatabase::class.java).build()
        dao = database.garageDao()
        repository = GarageRepository(database, dao, currentCurrencyCode = { "RUB" })
    }

    @After
    fun tearDown(): Unit = runBlocking {
        models.clear()
        preferencesJob.cancelAndJoin()
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun firstLaunchSeedsOnceAndTheFlagSurvivesPreferencesReload() = runBlocking {
        assertFalse(settings.garageInitialized.first())
        initialize()
        val before = repository.exportBackup(today)
        assertEquals(1, dao.allCars().size)
        assertTrue(dao.items(dao.allCars().single().id).isNotEmpty())
        reloadSettings()
        assertTrue(settings.garageInitialized.first())
        initialize()
        assertEquals(before, repository.exportBackup(today))
    }

    @Test
    fun upgradeWithoutFlagPreservesAnExistingGarage() = runBlocking {
        dao.insertCar(CarEntity(name = "Existing car"))
        val before = repository.exportBackup(today)
        initialize()
        assertTrue(settings.garageInitialized.first())
        assertEquals(before, repository.exportBackup(today))
    }

    @Test
    fun failedPopulationDoesNotMarkInitializationCompleteAndCanRetry() = runBlocking {
        val broken = GarageRepository(database, object : GarageDao by dao {
            override suspend fun insertItem(item: MaintenanceItemEntity): Long = throw IOException("seed failed")
        }, currentCurrencyCode = { "RUB" })
        try {
            settings.initializeGarageIfNeeded { broken.seedIfEmpty(null, UnitSystem.METRIC, "First car") }
            fail("Failed seeding must propagate")
        } catch (_: IOException) {
            assertFalse(settings.garageInitialized.first())
            assertTrue(dao.allCars().isEmpty())
        }
        reloadSettings()
        initialize()
        assertTrue(settings.garageInitialized.first())
        assertEquals(1, dao.allCars().size)
    }

    @Test
    fun emptyRestoreMarksAnOlderGarageAndStaysEmptyOnNextInitialization() = runBlocking {
        // An older install has data but no flag. A valid restore may contain zero cars.
        dao.insertCar(CarEntity(name = "Before restore"))
        val viewModel = HomeViewModel(repository, settings)
        models.put("home", viewModel)
        assertEquals(0, viewModel.restoreBackup("""{"format":4,"createdAt":"2026-10-20","cars":[]}""", BackupCurrency("RUB")))
        assertTrue(dao.allCars().isEmpty())

        reloadSettings()
        assertTrue(settings.garageInitialized.first())
        initialize()
        assertNull(repository.observeOverview(0L) { today }.first())
        assertTrue(dao.allCars().isEmpty())
        assertTrue(requireNotNull(BackupCodec.decode(repository.exportBackup(today))).cars.isEmpty())
    }

    @Test
    fun rejectedRestoreDoesNotCompleteInitializationOrChangeData() = runBlocking {
        dao.insertCar(CarEntity(name = "Keep this car"))
        val before = repository.exportBackup(today)
        val viewModel = HomeViewModel(repository, settings)
        models.put("home", viewModel)
        assertNull(viewModel.restoreBackup("{}", BackupCurrency("RUB")))
        assertFalse(settings.garageInitialized.first())
        assertEquals(before, repository.exportBackup(today))
    }

    @Test
    fun automaticNotificationRequestIsClaimedOnceAndSurvivesPreferencesReload(): Unit = runBlocking {
        assertTrue(settings.claimNotificationPermissionRequest())
        assertFalse(settings.claimNotificationPermissionRequest())
        reloadSettings()
        assertFalse(settings.claimNotificationPermissionRequest())
    }

    @Test
    fun concurrentAutomaticNotificationRequestsHaveOnlyOneClaim(): Unit = runBlocking {
        val claims = List(32) {
            async(Dispatchers.Default) { settings.claimNotificationPermissionRequest() }
        }.awaitAll()
        assertEquals(1, claims.count { it })
        reloadSettings()
        assertFalse(settings.claimNotificationPermissionRequest())
    }

    private suspend fun initialize() = settings.initializeGarageIfNeeded {
        repository.seedIfEmpty(null, UnitSystem.METRIC, "First car")
    }

    private fun openSettings(): SettingsStore {
        preferencesJob = SupervisorJob()
        return SettingsStore(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + preferencesJob),
            produceFile = { File(directory, "settings.preferences_pb") },
        ))
    }

    private suspend fun reloadSettings() {
        preferencesJob.cancelAndJoin()
        settings = openSettings()
    }
}
