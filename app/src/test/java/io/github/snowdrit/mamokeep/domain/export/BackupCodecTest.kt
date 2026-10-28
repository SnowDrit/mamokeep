package io.github.snowdrit.mamokeep.domain.export

import io.github.snowdrit.mamokeep.domain.ItemKind
import io.github.snowdrit.mamokeep.domain.ReminderRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BackupCodecTest {

    private val backup = BackupFile(
        createdAt = "2026-08-19",
        currency = BackupCurrency("RUB"),
        cars = listOf(
            BackupCar(
                name = "Моя машина",
                make = "Lada",
                model = "Vesta",
                year = 2019,
                vin = "XTA219050K0123456",
                bodyType = "suv",
                readings = listOf(BackupReading(20_684, 87_340)),
                items = listOf(
                    BackupItem(
                        kind = ItemKind.MAINTENANCE,
                        title = "Масло",
                        iconKey = "oil",
                        intervalKm = 10_000,
                        intervalMonths = 12,
                        lastDoneKm = 80_000,
                        lastDoneEpochDay = 20_600,
                        warnKm = 500,
                        warnDays = 14,
                        records = listOf(BackupRecord(20_600, 80_000, "у дилера")),
                    ),
                    BackupItem(kind = ItemKind.DOCUMENT, title = "ОСАГО", intervalMonths = 12, warnDays = 30),
                ),
                fuelGrade = "ai_95",
                fuel = listOf(
                    BackupFuel(
                        epochDay = 20_684,
                        odometerKm = 87_340,
                        amount = 42.3,
                        fuelType = "LPG",
                        costKopecks = 290_000,
                        isFull = true,
                    )
                ),
            )
        ),
    )

    @Test
    fun `копия переживает круг туда-обратно`() {
        val restored = BackupCodec.decode(BackupCodec.encode(backup))

        assertEquals(backup, restored)
    }

    @Test
    fun `v5 retains a known ISO code or an explicitly unknown monetary unit`() {
        for (currency in listOf(BackupCurrency("RUB"), BackupCurrency("USD"), BackupCurrency(null))) {
            val copy = backup.copy(currency = currency)
            assertEquals(copy, BackupCodec.decode(BackupCodec.encode(copy)))
        }
    }

    @Test
    fun `v5 missing or malformed currency metadata is rejected`() {
        val missingOrInvalid = listOf(
            "", ",\"currency\":null", ",\"currency\":{}",
            ",\"currency\":{\"code\":\"rub\"}", ",\"currency\":{\"code\":\"RU\"}",
            ",\"currency\":{\"code\":\"RUBX\"}", ",\"currency\":{\"code\":\"R1B\"}",
            ",\"currency\":{\"code\":\"РУБ\"}", ",\"currency\":{\"code\":123}",
        )
        for (metadata in missingOrInvalid) {
            assertNull(metadata, BackupCodec.decode("""{"format":5,"createdAt":"2026-10-22","cars":[]$metadata}"""))
        }
    }

    @Test
    fun `legacy files have no invented currency and lexical validation does not depend on Android ISO lists`() {
        for (version in 1..4) {
            val decoded = requireNotNull(BackupCodec.decode("""{"format":$version,"createdAt":"2026-10-22","cars":[]}"""))
            assertNull(decoded.currency)
        }
        val independent = backup.copy(currency = BackupCurrency("XYZ"))
        assertEquals(independent, BackupCodec.decode(BackupCodec.encode(independent)))
    }

    @Test
    fun `история и заправки не теряются`() {
        val restored = BackupCodec.decode(BackupCodec.encode(backup))!!
        val car = restored.cars.single()

        assertEquals(1, car.readings.size)
        assertEquals(2, car.items.size)
        assertEquals("у дилера", car.items.first().records.single().note)
        assertEquals(42.3, car.fuel.single().amount, 0.0001)
        assertEquals(ItemKind.DOCUMENT, car.items[1].kind)
    }

    @Test
    fun `значок и тип кузова переживают копию`() {
        // Терялись молча: поля не было в формате, и после восстановления все значки
        // сбрасывались на запасной.
        val restored = BackupCodec.decode(BackupCodec.encode(backup))!!

        assertEquals("suv", restored.cars.single().bodyType)
        assertEquals("oil", restored.cars.single().items.first().iconKey)
    }

    @Test
    fun `копия без значков читается как прежде`() {
        val old = """{"format":1,"createdAt":"2026-01-01","cars":[{"name":"A","items":[{"title":"Масло"}]}]}"""

        val restored = BackupCodec.decode(old)!!

        assertNull(restored.cars.single().items.single().iconKey)
    }

    @Test
    fun `вид топлива и заметка о марке переживают копию`() {
        val restored = BackupCodec.decode(BackupCodec.encode(backup))!!

        assertEquals("LPG", restored.cars.single().fuel.single().fuelType)
        assertEquals("ai_95", restored.cars.single().fuelGrade)
    }

    @Test
    fun `копия без видов топлива читается как бензин`() {
        // Формат 1: количество называлось liters, вида не было вовсе. Такие файлы уже
        // лежат у владельца, и восстановление обязано их принимать.
        val old = """{"format":1,"createdAt":"2026-01-01","cars":[{"name":"A","fuel":[
            {"epochDay":20684,"odometerKm":87340,"liters":42.3,"costKopecks":290000,"isFull":true}]}]}"""

        val fuel = BackupCodec.decode(old)!!.cars.single().fuel.single()

        assertEquals(42.3, fuel.amount, 0.0001)
        assertEquals("PETROL", fuel.fuelType)
    }

    @Test
    fun `чужой или испорченный файл не принимается`() {
        assertNull(BackupCodec.decode("это не json"))
        assertNull(BackupCodec.decode("""{"unrelated": true}"""))
        // Defaults must not turn an unrelated JSON object into a destructive empty restore.
        assertNull(BackupCodec.decode("""{"createdAt":"2026-10-20"}"""))
        assertNull(BackupCodec.decode("""{"createdAt":"2026-10-20","format":4}"""))
        assertNull(BackupCodec.decode("""{"createdAt":"2026-10-20","cars":[]}"""))
    }

    @Test
    fun `invalid dates and numbers are rejected before replacing the garage`() {
        val car = backup.cars.single()
        val item = car.items.first()
        val fuel = car.fuel.single()
        val invalidCars = listOf(
            car.copy(readings = listOf(BackupReading(Long.MAX_VALUE, 1))),
            car.copy(readings = listOf(BackupReading(20_600, -1))),
            car.copy(fuel = listOf(fuel.copy(epochDay = Long.MIN_VALUE))),
            car.copy(fuel = listOf(fuel.copy(odometerKm = -1))),
            car.copy(fuel = listOf(fuel.copy(amount = 0.0))),
            car.copy(fuel = listOf(fuel.copy(costKopecks = -1))),
            car.copy(items = listOf(item.copy(lastDoneEpochDay = Long.MAX_VALUE))),
            car.copy(items = listOf(item.copy(lastDoneEpochDay = LocalDate.MAX.toEpochDay()))),
            car.copy(items = listOf(item.copy(intervalKm = -1))),
            car.copy(items = listOf(item.copy(intervalMonths = -1))),
            car.copy(items = listOf(item.copy(lastDoneKm = -1))),
            car.copy(items = listOf(item.copy(warnKm = -1))),
            car.copy(items = listOf(item.copy(warnDays = -1))),
            car.copy(items = listOf(item.copy(records = listOf(BackupRecord(Long.MAX_VALUE))))),
            car.copy(items = listOf(item.copy(records = listOf(BackupRecord(20_600, -1))))),
        )
        invalidCars.forEach { invalid ->
            assertNull(invalid.toString(), BackupCodec.decode(BackupCodec.encode(backup.copy(cars = listOf(invalid)))))
        }
    }

    @Test
    fun `zero thresholds intervals costs and unknown historical fuel keys retain compatibility`() {
        val car = backup.cars.single()
        val compatible = backup.copy(cars = listOf(car.copy(
            items = listOf(car.items.first().copy(intervalKm = 0, intervalMonths = 0, warnKm = 0, warnDays = 0)),
            fuel = listOf(car.fuel.single().copy(costKopecks = 0, fuelType = "legacy_unknown")),
        )))
        assertEquals(compatible, BackupCodec.decode(BackupCodec.encode(compatible)))
    }

    @Test
    fun `копия из будущей версии формата отклоняется`() {
        // Принять её значило бы потерять поля, о которых мы не знаем.
        val future = BackupCodec.encode(backup).replace("\"format\": ${BackupFile.FORMAT_VERSION}", "\"format\": 99")

        assertNull(BackupCodec.decode(future))
    }

    @Test
    fun `пустая копия допустима`() {
        assertNotNull(BackupCodec.decode("""{"format": 1, "createdAt": "2026-08-19", "cars": []}"""))
    }

    @Test
    fun `общее и отдельное расписания переживают копию`() {
        val global = ReminderRule(minuteOfDay = 20 * 60 + 30, weekdays = setOf(3))
        val custom = ReminderRule(
            minuteOfDay = 8 * 60,
            weekdays = setOf(1, 2, 3, 4, 5, 6),
            advancedEnabled = true,
            yellowAdvanceKm = 750,
            redAdvanceKm = 250,
            yellowAdvanceDays = 7,
            redAdvanceDays = 3,
            yellowRepeatDays = 4,
            redRepeatDays = 2,
            yellowMaxOccurrences = 3,
            redMaxOccurrences = 0,
        )
        val expected = backup.copy(
            defaultRule = global,
            cars = backup.cars.map { car -> car.copy(
                items = car.items.mapIndexed { index, item ->
                    if (index == 0) item.copy(reminderRule = custom) else item
                },
            ) },
        )

        assertEquals(expected, BackupCodec.decode(BackupCodec.encode(expected)))
        assertNull(BackupCodec.decode(BackupCodec.encode(expected))!!.cars.single().items[1].reminderRule)
    }

    @Test
    fun `старые копии не выдумывают настройки напоминаний`() {
        for (format in 1..3) {
            val content = """{"format":$format,"createdAt":"2026-01-01","cars":[{"name":"A","items":[{"title":"Масло"}]}]}"""
            val restored = requireNotNull(BackupCodec.decode(content))

            assertNull(restored.defaultRule)
            assertNull(restored.cars.single().items.single().reminderRule)
        }
    }

    @Test
    fun `испорченное расписание отклоняется до восстановления`() {
        val invalidRules = listOf(
            ReminderRule(minuteOfDay = -1),
            ReminderRule(minuteOfDay = 24 * 60),
            ReminderRule(weekdays = emptySet()),
            ReminderRule(weekdays = setOf(0)),
            ReminderRule(weekdays = setOf(8)),
            ReminderRule(yellowAdvanceKm = -1),
            ReminderRule(redAdvanceKm = -1),
            ReminderRule(yellowAdvanceDays = -1),
            ReminderRule(redAdvanceDays = -1),
            ReminderRule(yellowRepeatDays = 0),
            ReminderRule(redRepeatDays = 0),
            ReminderRule(yellowMaxOccurrences = -1),
            ReminderRule(redMaxOccurrences = -1),
        )
        invalidRules.forEach { rule ->
            assertNull(BackupCodec.decode(BackupCodec.encode(backup.copy(defaultRule = rule))))
            val invalidItem = backup.cars.single().items.first().copy(reminderRule = rule)
            val invalidCar = backup.cars.single().copy(items = listOf(invalidItem))
            assertNull(BackupCodec.decode(BackupCodec.encode(backup.copy(cars = listOf(invalidCar)))))
        }
    }
}
