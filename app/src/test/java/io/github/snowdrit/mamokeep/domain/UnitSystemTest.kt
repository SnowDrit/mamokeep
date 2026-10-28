package io.github.snowdrit.mamokeep.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitSystemTest {

    @Test
    fun `метрическая система ничего не трогает`() {
        val units = UnitSystem.METRIC

        assertEquals(87_340.0, units.distance(87_340), 0.001)
        assertEquals(87_340, units.kmFrom(87_340.0))
        assertEquals(42.0, units.volume(42.0, FuelType.PETROL), 0.001)
        assertEquals(AmountUnit.LITER, units.amountUnit(FuelType.PETROL))
        assertEquals(560L, units.costPerDistance(560L))
    }

    @Test
    fun `мили и километры ходят туда-обратно без дрейфа`() {
        // Круглый мильный интервал обязан вернуться тем же круглым числом: иначе
        // «каждые 5 000 миль» после сохранения превратится в 4 999.
        listOf(5_000, 7_500, 15_000, 30_000, 60_000).forEach { miles ->
            val km = UnitSystem.US.kmFrom(miles.toDouble())
            assertEquals(miles.toDouble(), UnitSystem.US.distance(km), 0.5)
        }
    }

    @Test
    fun `галлоны только у жидкого топлива`() {
        val us = UnitSystem.US

        // 37,85 л — ровно 10 американских галлонов.
        assertEquals(10.0, us.volume(37.85411784, FuelType.PETROL), 0.001)
        assertEquals(AmountUnit.GALLON, us.amountUnit(FuelType.PETROL))

        // Кубометры метана и киловатт-часы заряда не имперские и не метрические.
        assertEquals(20.0, us.volume(20.0, FuelType.CNG), 0.001)
        assertEquals(AmountUnit.CUBIC_METER, us.amountUnit(FuelType.CNG))
        assertEquals(AmountUnit.KWH, us.amountUnit(FuelType.ELECTRIC))
    }

    @Test
    fun `британский галлон больше американского, и mpg тоже`() {
        // 8 л на 100 км — это 29,4 mpg по-американски и 35,3 по-британски.
        val us = UnitSystem.US.consumption(8.0, FuelType.PETROL)
        val uk = UnitSystem.UK.consumption(8.0, FuelType.PETROL)

        // null в единице количества и означает mpg: расстояние там уже в числителе.
        assertEquals(null, us.amountUnit)
        assertEquals(29.4, us.value, 0.05)
        assertEquals(35.3, uk.value, 0.05)
    }

    @Test
    fun `в Британии топливо всё равно продают литрами`() {
        // Тот самый смешанный случай, из-за которого наборов три, а не два.
        assertEquals(AmountUnit.LITER, UnitSystem.UK.amountUnit(FuelType.PETROL))
        assertEquals(DistanceUnit.MILE, UnitSystem.UK.distanceUnit)
    }

    @Test
    fun `электричество считается на 100 миль, а не в mpg`() {
        // У киловатт-часа нет галлона, и переворачивать дробь не во что.
        val charge = UnitSystem.US.consumption(18.0, FuelType.ELECTRIC)

        assertEquals(AmountUnit.KWH, charge.amountUnit)
        // 18 кВт·ч на 100 км — это 29 кВт·ч на 100 миль: миля длиннее.
        assertEquals(28.97, charge.value, 0.05)
    }

    @Test
    fun `метрический расход остаётся литрами на сто километров`() {
        val metric = UnitSystem.METRIC.consumption(8.0, FuelType.PETROL)

        assertEquals(AmountUnit.LITER, metric.amountUnit)
        assertEquals(8.0, metric.value, 0.001)
    }

    @Test
    fun `набор берётся из страны телефона`() {
        assertEquals(UnitSystem.US, UnitSystem.forCountry("US"))
        assertEquals(UnitSystem.UK, UnitSystem.forCountry("GB"))
        assertEquals(UnitSystem.METRIC, UnitSystem.forCountry("RU"))
        assertEquals(UnitSystem.METRIC, UnitSystem.forCountry(null))
    }

    @Test
    fun `имперские интервалы обслуживания — свои числа, а не пересчёт`() {
        val oil = MaintenanceDefaults.ALL.first { it.titleKey == "oil" }

        assertEquals(10_000, oil.intervalFor(UnitSystem.METRIC))
        // 5 000 миль, а не 6 214: американец должен узнать свой регламент.
        assertEquals(5_000.0, UnitSystem.US.distance(oil.intervalFor(UnitSystem.US)!!), 0.5)
    }
}
