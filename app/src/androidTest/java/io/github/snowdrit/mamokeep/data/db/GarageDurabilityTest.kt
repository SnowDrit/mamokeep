package io.github.snowdrit.mamokeep.data.db

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.snowdrit.mamokeep.data.repo.GarageRepository
import io.github.snowdrit.mamokeep.domain.UnitSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** Real files and writer connections; never opens or clears the installed app's garage. */
@RunWith(AndroidJUnit4::class)
class GarageDurabilityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun writerUsesFullAndGarageSurvivesDatabaseReopen() = runBlocking {
        verifyReopen(MamoKeepDatabase.DURABLE_WRITES)
    }

    @Test
    fun legacyConfigurationAlsoSetsTheActualWriterAndSurvivesReopen() = runBlocking {
        // Exercise the API 26–29 path even on a newer test device. This does not
        // replace a runtime check on those Android versions.
        verifyReopen(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                MamoKeepDatabase.DURABLE_WRITES.onOpen(object : SupportSQLiteDatabase by db {
                    override val isExecPerConnectionSQLSupported: Boolean = false
                })
            }
        })
    }

    private suspend fun verifyReopen(callback: RoomDatabase.Callback) {
        val name = "garage-durability-${UUID.randomUUID()}.db"
        val today = LocalDate.of(2026, 10, 26)
        var database = openDatabase(name, callback)
        try {
            assertWriterFull(database)
            val dao = database.garageDao()
            val repository = GarageRepository(database, dao, currentCurrencyCode = { "RUB" })
            repository.seedIfEmpty(null, UnitSystem.METRIC, "Durability test car")
            val car = dao.allCars().single()
            repository.addOdometerReading(car.id, 120000, today)
            assertTrue(dao.items(car.id).isNotEmpty())
            val before = repository.exportBackup(today)

            database.close()
            database = openDatabase(name, callback)
            assertWriterFull(database)
            val reopened = GarageRepository(
                database,
                database.garageDao(),
                currentCurrencyCode = { "RUB" },
            )
            assertEquals(before, reopened.exportBackup(today))
            reopened.seedIfEmpty(null, UnitSystem.METRIC, "Must not replace")
            assertEquals(before, reopened.exportBackup(today))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun openDatabase(name: String, callback: RoomDatabase.Callback): MamoKeepDatabase =
        Room.databaseBuilder(context, MamoKeepDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(callback)
            .build()

    private fun assertWriterFull(database: MamoKeepDatabase) {
        val sqlite = database.openHelper.writableDatabase
        // Opening a normal read first prevents an accidental reader-only assertion.
        sqlite.query("SELECT count(*) FROM cars").use { assertTrue(it.moveToFirst()) }
        sqlite.beginTransactionNonExclusive()
        try {
            // The transaction pins this thread to the primary connection used by writes.
            sqlite.query("PRAGMA synchronous").use {
                assertTrue(it.moveToFirst())
                assertEquals(2, it.getInt(0))
            }
            sqlite.query("PRAGMA journal_mode").use {
                assertTrue(it.moveToFirst())
                assertEquals("wal", it.getString(0))
            }
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }
    }
}
