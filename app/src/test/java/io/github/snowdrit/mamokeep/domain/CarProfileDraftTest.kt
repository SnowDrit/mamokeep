package io.github.snowdrit.mamokeep.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarProfileDraftTest {
    private val car = Car(
        id = 42L,
        name = "Моя машина",
        make = "Lada",
        model = "Vesta",
        year = 2021,
        vin = "TESTVIN123",
        bodyType = "sedan",
        fuelGrade = "ai95",
        silhouetteColor = 0xFF123456.toInt(),
    )

    @Test
    fun `исходный черновик содержит все редактируемые поля и не считается изменением`() {
        val draft = CarProfileDraft.fromCar(car)
        assertEquals(
            CarProfileDraft("Моя машина", "Lada", "Vesta", "2021", "TESTVIN123", "sedan", "ai95", 0xFF123456.toInt()),
            draft,
        )
        assertFalse(draft.hasChanges(car))
        assertEquals(car, draft.toCar(car, "Автомобиль"))
    }

    @Test
    fun `пустые данные машины становятся пустыми текстовыми полями`() {
        val emptyCar = Car(id = 3L, name = "Автомобиль")
        val draft = CarProfileDraft.fromCar(emptyCar)
        assertEquals(CarProfileDraft("Автомобиль", "", "", "", "", null, null, null), draft)
        assertFalse(draft.hasChanges(emptyCar))
        assertEquals(emptyCar, draft.toCar(emptyCar, "Название"))
    }

    @Test
    fun `изменение каждого поля требует сохранения`() {
        val draft = CarProfileDraft.fromCar(car)
        listOf(
            draft.copy(name = "Новая машина"),
            draft.copy(make = "Другая марка"),
            draft.copy(model = "Другая модель"),
            draft.copy(year = "2022"),
            draft.copy(vin = "OTHER123"),
            draft.copy(bodyType = "hatchback"),
            draft.copy(fuelGrade = "ai98"),
            draft.copy(silhouetteColor = 0xFFABCDEF.toInt()),
        ).forEach { assertTrue(it.toString(), it.hasChanges(car)) }
    }

    @Test
    fun `очистка каждого необязательного поля считается изменением`() {
        val draft = CarProfileDraft.fromCar(car)
        listOf(
            draft.copy(make = ""),
            draft.copy(model = ""),
            draft.copy(year = ""),
            draft.copy(vin = ""),
            draft.copy(bodyType = null),
            draft.copy(fuelGrade = null),
            draft.copy(silhouetteColor = null),
        ).forEach { assertTrue(it.toString(), it.hasChanges(car)) }
    }

    @Test
    fun `возврат исходного значения снимает признак изменения`() {
        val draft = CarProfileDraft.fromCar(car)
        listOf(
            draft.copy(name = "Новое").copy(name = draft.name),
            draft.copy(make = "Новое").copy(make = draft.make),
            draft.copy(model = "Новое").copy(model = draft.model),
            draft.copy(year = "").copy(year = draft.year),
            draft.copy(vin = "").copy(vin = draft.vin),
            draft.copy(bodyType = null).copy(bodyType = draft.bodyType),
            draft.copy(fuelGrade = null).copy(fuelGrade = draft.fuelGrade),
            draft.copy(silhouetteColor = null).copy(silhouetteColor = draft.silhouetteColor),
        ).forEach { assertFalse(it.toString(), it.hasChanges(car)) }
    }

    @Test
    fun `идентификатор машины не влияет на изменения полей`() {
        assertFalse(CarProfileDraft.fromCar(car).hasChanges(car.copy(id = 99L)))
    }

    @Test
    fun `сравнивается введенный текст до нормализации`() {
        val original = car.copy(name = " Машина ", make = " Марка ", vin = " VIN ")
        val draft = CarProfileDraft.fromCar(original)
        assertFalse(draft.hasChanges(original))
        assertTrue(draft.copy(name = "Машина").hasChanges(original))
        assertTrue(CarProfileDraft.fromCar(car).copy(year = "02021").hasChanges(car))
    }

    @Test
    fun `сохранение нормализует поля и сохраняет идентификатор переданной машины`() {
        val original = car.copy(id = 987L)
        val draft = CarProfileDraft(
            name = "  Новая машина  ",
            make = "  Марка  ",
            model = "  Модель  ",
            year = "2025",
            vin = "  NEWVIN  ",
            bodyType = "  coupe  ",
            fuelGrade = "  diesel  ",
            silhouetteColor = 0xFFABCDEF.toInt(),
        )
        assertEquals(
            Car(987L, "Новая машина", "Марка", "Модель", 2025, "NEWVIN", "coupe", 0xFFABCDEF.toInt(), "diesel"),
            draft.toCar(original, "Автомобиль"),
        )
        // Сохранение не изменяет исходный объект или несохранённый текст в черновике.
        assertEquals("Моя машина", original.name)
        assertEquals("  Новая машина  ", draft.name)
    }

    @Test
    fun `пустое название использует переданный перевод а пустые поля становятся null`() {
        val draft = CarProfileDraft(" \t ", " ", "\t", "", "\n", " ", "\t", null)
        assertEquals(Car(id = car.id, name = "Default car"), draft.toCar(car, "Default car"))
    }

    @Test
    fun `год сохраняет прежнее поведение toIntOrNull без новых ограничений`() {
        val draft = CarProfileDraft.fromCar(car)
        listOf("", "неизвестно", "2025 год", "2147483648", " 2025 ").forEach { text ->
            assertNull(text, draft.copy(year = text).toCar(car, "Автомобиль").year)
        }
        mapOf("0001" to 1, "0" to 0, "-1" to -1, "9999" to 9999, "2147483647" to Int.MAX_VALUE).forEach { (text, expected) ->
            assertEquals(text, expected, draft.copy(year = text).toCar(car, "Автомобиль").year)
        }
    }

    @Test
    fun `сохраненный черновик больше не содержит изменений`() {
        val draft = CarProfileDraft.fromCar(car).copy(name = "Новое название", silhouetteColor = null)
        val saved = draft.toCar(car, "Автомобиль")
        assertFalse(draft.hasChanges(saved))
        assertEquals(car.id, saved.id)
    }
}
