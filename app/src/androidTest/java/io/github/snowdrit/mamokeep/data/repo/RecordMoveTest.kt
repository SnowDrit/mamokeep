package io.github.snowdrit.mamokeep.data.repo

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.snowdrit.mamokeep.data.db.CarEntity
import io.github.snowdrit.mamokeep.data.db.GarageDao
import io.github.snowdrit.mamokeep.data.db.MaintenanceItemEntity
import io.github.snowdrit.mamokeep.data.db.MamoKeepDatabase
import io.github.snowdrit.mamokeep.domain.ItemKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Перенос отметки «сделано» на другую позицию.
 *
 * Проверять это глазами нельзя: при провале экран выглядит нормально, а у покинутой
 * позиции молча остаётся чужой срок — и следующее напоминание не приходит вовремя.
 * Обнаружилось бы это через несколько месяцев, на пропущенном обслуживании.
 *
 * Тест инструментальный, потому что ему нужна настоящая база (решение 27). Ради этого
 * перенос и делался после тестов миграций, а не вместе с остальными мелкими дырами.
 */
@RunWith(AndroidJUnit4::class)
class RecordMoveTest {

    private lateinit var database: MamoKeepDatabase
    private lateinit var dao: GarageDao
    private lateinit var repository: GarageRepository

    private val march = LocalDate.of(2026, 3, 12)
    private val august = LocalDate.of(2026, 8, 22)

    @Before
    fun открыть_базу() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, MamoKeepDatabase::class.java).build()
        dao = database.garageDao()
        repository = GarageRepository(database, dao, currentCurrencyCode = { "RUB" })
    }

    @After
    fun закрыть_базу() {
        database.close()
    }

    @Test
    fun перенос_пересчитывает_последний_раз_у_обеих_позиций() = runBlocking {
        val car = car("Моя машина")
        val oil = item(car, "Масло в двигателе")
        val filter = item(car, "Воздушный фильтр")

        // У масла две отметки: мартовская и августовская. Августовскую человек поставил
        // по ошибке — менял он фильтр.
        repository.markDone(oil, atKm = 20_000, date = march)
        repository.markDone(oil, atKm = 30_000, date = august)

        val wrong = dao.recordsOf(oil).last()
        repository.updateRecord(wrong.id, itemId = filter, date = august, km = 30_000)

        // У фильтра появился август — это очевидная половина.
        assertEquals(august, dao.item(filter)!!.lastDoneDate)
        assertEquals(30_000, dao.item(filter)!!.lastDoneKm)

        // А вот ради этой половины тест и написан: у масла «последний раз» обязан
        // откатиться на март. Если он останется августовским, срок замены уедет на пять
        // месяцев вперёд, и на экране это будет выглядеть совершенно нормально.
        assertEquals(march, dao.item(oil)!!.lastDoneDate)
        assertEquals(20_000, dao.item(oil)!!.lastDoneKm)
    }

    @Test
    fun единственная_отметка_уходит_и_позиция_снова_неотмечена() = runBlocking {
        val car = car("Моя машина")
        val oil = item(car, "Масло в двигателе")
        val filter = item(car, "Воздушный фильтр")

        repository.markDone(oil, atKm = 30_000, date = august)
        val only = dao.recordsOf(oil).single()
        repository.updateRecord(only.id, itemId = filter, date = august, km = 30_000)

        // Считать не от чего — и это честно: пустое поле лучше выдуманной даты.
        assertNull(dao.item(oil)!!.lastDoneDate)
        assertNull(dao.item(oil)!!.lastDoneKm)
        assertEquals(august, dao.item(filter)!!.lastDoneDate)
    }

    @Test
    fun позиция_чужой_машины_переносом_не_принимается() = runBlocking {
        val mine = car("Моя машина")
        val other = car("Мотоцикл")
        val oil = item(mine, "Масло в двигателе")
        val alienOil = item(other, "Масло в двигателе")

        repository.markDone(oil, atKm = 30_000, date = august)
        val record = dao.recordsOf(oil).single()
        repository.updateRecord(record.id, itemId = alienOil, date = august, km = 30_000)

        // Отметка несёт пробег своей машины: уехав к чужой, она соврала бы про её пробег.
        // Такой перенос — отдельная работа с отдельным разговором про одометр.
        assertEquals(august, dao.item(oil)!!.lastDoneDate)
        assertNull(dao.item(alienOil)!!.lastDoneDate)
        assertEquals(oil, dao.recordsOf(oil).single().itemId)
    }

    @Test
    fun правка_без_переноса_ничего_не_ломает() = runBlocking {
        val car = car("Моя машина")
        val oil = item(car, "Масло в двигателе")

        repository.markDone(oil, atKm = 30_000, date = august)
        val record = dao.recordsOf(oil).single()
        repository.updateRecord(record.id, itemId = oil, date = march, km = 20_000)

        assertEquals(march, dao.item(oil)!!.lastDoneDate)
        assertEquals(20_000, dao.item(oil)!!.lastDoneKm)
    }

    @Test
    fun последняя_отметка_выбирается_по_дате_затем_id_внутри_своей_позиции() = runBlocking {
        val car = car("Моя машина")
        val oil = item(car, "Масло в двигателе")
        val filter = item(car, "Воздушный фильтр")

        repository.markDone(oil, atKm = 30_000, date = august)
        val firstAugust = dao.recordsOf(oil).single()
        // Более поздний id того же дня побеждает даже с меньшим пробегом.
        repository.markDone(oil, atKm = 29_000, date = august)
        val lastAugust = dao.recordsOf(oil).last()
        // Ввод старой отметки задним числом не делает её последней.
        repository.markDone(oil, atKm = 20_000, date = march)
        repository.markDone(filter, atKm = 40_000, date = august.plusDays(1))

        assertEquals(lastAugust, dao.latestRecord(oil))
        assertEquals(firstAugust, dao.latestRecordExcluding(oil, lastAugust.id))
        assertEquals(august, dao.item(oil)!!.lastDoneDate)
        assertEquals(29_000, dao.item(oil)!!.lastDoneKm)
        assertEquals(august, repository.lastDoneWithout(lastAugust.id))
        assertNull(repository.lastDoneWithout(Long.MAX_VALUE))
    }

    @Test
    fun удаление_последней_отметки_откатывает_на_тот_же_день_затем_прошлый_и_пустоту() = runBlocking {
        val car = car("Моя машина")
        val oil = item(car, "Масло в двигателе")
        repository.markDone(oil, atKm = 20_000, date = march)
        repository.markDone(oil, atKm = 30_000, date = august)
        repository.markDone(oil, atKm = 31_000, date = august)
        val records = dao.recordsOf(oil)

        assertEquals(august, repository.lastDoneWithout(records[2].id))
        repository.deleteRecord(records[2].id)
        assertEquals(august, dao.item(oil)!!.lastDoneDate)
        assertEquals(30_000, dao.item(oil)!!.lastDoneKm)

        assertEquals(march, repository.lastDoneWithout(records[1].id))
        repository.deleteRecord(records[1].id)
        assertEquals(march, dao.item(oil)!!.lastDoneDate)
        assertEquals(20_000, dao.item(oil)!!.lastDoneKm)

        assertNull(repository.lastDoneWithout(records[0].id))
        repository.deleteRecord(records[0].id)
        assertNull(dao.latestRecord(oil))
        assertNull(dao.item(oil)!!.lastDoneDate)
        assertNull(dao.item(oil)!!.lastDoneKm)
    }

    private suspend fun car(name: String): Long = dao.insertCar(CarEntity(name = name))

    private suspend fun item(carId: Long, title: String): Long = dao.insertItem(
        MaintenanceItemEntity(
            carId = carId,
            kind = ItemKind.MAINTENANCE,
            title = title,
            intervalKm = 10_000,
            intervalMonths = 12,
            lastDoneKm = null,
            lastDoneDate = null,
            warnKm = 500,
            warnDays = 14,
        )
    )
}
