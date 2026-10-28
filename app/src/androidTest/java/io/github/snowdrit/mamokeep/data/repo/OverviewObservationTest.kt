package io.github.snowdrit.mamokeep.data.repo

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.snowdrit.mamokeep.data.db.CarEntity
import io.github.snowdrit.mamokeep.data.db.MamoKeepDatabase
import io.github.snowdrit.mamokeep.domain.Car
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class OverviewObservationTest {
    @Test
    fun anotherCarEditDoesNotRestartOverviewButSelectedEditAndDeletionStillReachIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val carQueries = Channel<Unit>(Channel.UNLIMITED)
        val database = Room.inMemoryDatabaseBuilder(context, MamoKeepDatabase::class.java)
            .setQueryCallback({ sql, _ ->
                if (sql.startsWith("SELECT * FROM cars WHERE id = COALESCE")) carQueries.trySend(Unit)
            }, Executor { it.run() })
            .build()
        val events = Channel<CarOverview?>(Channel.UNLIMITED)
        try {
            val dao = database.garageDao()
            val repository = GarageRepository(database, dao, currentCurrencyCode = { "RUB" })
            val other = dao.insertCar(CarEntity(name = "Other car"))
            val selected = dao.insertCar(CarEntity(name = "Selected car"))
            val collector = launch(Dispatchers.Default) {
                repository.observeOverview(selected) { LocalDate.of(2026, 10, 18) }.collect { events.send(it) }
            }
            try {
                withTimeout(5_000) { carQueries.receive() }
                assertEquals("Selected car", withTimeout(5_000) { events.receive() }?.carName)

                repository.saveCar(Car(id = other, name = "Other renamed"))
                // Wait until Room has actually reacted to the table invalidation.
                withTimeout(5_000) { carQueries.receive() }
                assertNull("An unchanged selected car must not restart its overview", withTimeoutOrNull(300) {
                    events.receive()
                    true
                })
                assertEquals("Selected car", dao.car(selected)?.name)

                repository.saveCar(Car(id = selected, name = "Selected renamed"))
                assertEquals("Selected renamed", withTimeout(5_000) { events.receive() }?.carName)
                assertEquals("Other renamed", dao.car(other)?.name)
                assertEquals(2, dao.allCars().size)

                repository.deleteCar(selected)
                val fallback = withTimeout(5_000) { events.receive() }
                assertEquals(other, fallback?.carId)
                assertEquals("Other renamed", fallback?.carName)
            } finally {
                collector.cancelAndJoin()
            }
        } finally {
            database.close()
        }
    }
}
