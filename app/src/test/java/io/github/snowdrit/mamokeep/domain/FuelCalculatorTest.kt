package io.github.snowdrit.mamokeep.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FuelCalculatorTest {

    private var nextId = 1L
    private val start = LocalDate.of(2026, 6, 1)

    private fun entry(
        km: Int,
        amount: Double,
        rubles: Long,
        full: Boolean = true,
        type: FuelType = FuelType.PETROL,
    ) = FuelEntry(
        id = nextId++,
        carId = 1,
        date = start.plusDays(km.toLong() / 100),
        odometerKm = km,
        amount = amount,
        fuelType = type,
        costKopecks = rubles * 100,
        isFull = full,
    )

    /** Расход единственного вида — так экран и выгрузка читают обычную машину. */
    private fun single(stats: FuelStats): FuelUsage? = stats.usage.singleOrNull()

    @Test
    fun `без заправок расход неизвестен`() {
        val stats = FuelCalculator.stats(emptyList())

        assertTrue(stats.usage.isEmpty())
        assertTrue(stats.types.isEmpty())
        assertNull(stats.lastEntry)
    }

    @Test
    fun `по одной заправке расход посчитать нельзя`() {
        val stats = FuelCalculator.stats(listOf(entry(80_000, 42.0, 2_900)))

        assertNull(single(stats))
        assertEquals(80_000, stats.lastEntry!!.odometerKm)
        // Вид известен уже по первой заправке, даже когда расход ещё не считается.
        assertEquals(listOf(FuelType.PETROL), stats.types)
    }

    @Test
    fun `расход между двумя полными баками`() {
        // 40 литров на 500 км = 8 л на 100 км.
        val stats = FuelCalculator.stats(
            listOf(
                entry(80_000, 45.0, 3_000),
                entry(80_500, 40.0, 2_800),
            )
        )

        assertEquals(8.0, single(stats)!!.perHundredKm, 0.001)
        // 2800 ₽ на 500 км = 5,60 ₽ за километр.
        assertEquals(560L, stats.costPerKmKopecks)
    }

    @Test
    fun `неполная заправка внутри отрезка учитывается целиком`() {
        // От полного до полного: 20 + 30 = 50 литров на 500 км = 10 л на 100 км.
        val stats = FuelCalculator.stats(
            listOf(
                entry(80_000, 45.0, 3_000),
                entry(80_200, 20.0, 1_400, full = false),
                entry(80_500, 30.0, 2_100),
            )
        )

        assertEquals(10.0, single(stats)!!.perHundredKm, 0.001)
    }

    @Test
    fun `литры первого полного бака в расход не входят`() {
        // Иначе топливо, сожжённое до начала наблюдений, приписалось бы этому отрезку.
        val stats = FuelCalculator.stats(
            listOf(
                entry(80_000, 100.0, 7_000),
                entry(80_500, 40.0, 2_800),
            )
        )

        assertEquals(8.0, single(stats)!!.perHundredKm, 0.001)
    }

    @Test
    fun `без второго полного бака расход не считается`() {
        val stats = FuelCalculator.stats(
            listOf(
                entry(80_000, 45.0, 3_000),
                entry(80_300, 20.0, 1_400, full = false),
            )
        )

        assertNull(single(stats))
        assertEquals(80_300, stats.lastEntry!!.odometerKm)
    }

    @Test
    fun `повторный одометр не ломает расчёт`() {
        val stats = FuelCalculator.stats(
            listOf(
                entry(80_000, 45.0, 3_000),
                entry(80_000, 5.0, 350),
                entry(80_500, 40.0, 2_800),
            )
        )

        assertEquals(9.0, single(stats)!!.perHundredKm, 0.001)
    }

    @Test
    fun `виды топлива не смешиваются`() {
        // Машина с ГБО: газ и бензин на одном и том же пробеге. Сложить их — получить
        // среднее, которого не существует.
        val stats = FuelCalculator.stats(
            listOf(
                entry(80_000, 45.0, 3_000, type = FuelType.PETROL),
                entry(80_000, 50.0, 1_200, type = FuelType.LPG),
                entry(80_500, 10.0, 700, type = FuelType.PETROL),
                entry(80_500, 40.0, 960, type = FuelType.LPG),
            )
        )

        val petrol = stats.usage.single { it.fuelType == FuelType.PETROL }
        val lpg = stats.usage.single { it.fuelType == FuelType.LPG }

        assertEquals(2.0, petrol.perHundredKm, 0.001)
        assertEquals(8.0, lpg.perHundredKm, 0.001)
        // Километр оплачивается обоими видами сразу: 1,40 ₽ бензином и 1,92 ₽ газом.
        assertEquals(140L, petrol.costPerKmKopecks)
        assertEquals(192L, lpg.costPerKmKopecks)
        assertEquals(332L, stats.costPerKmKopecks)
    }

    @Test
    fun `вид без двух полных баков не мешает остальным`() {
        val stats = FuelCalculator.stats(
            listOf(
                entry(80_000, 45.0, 3_000, type = FuelType.PETROL),
                entry(80_500, 40.0, 2_800, type = FuelType.PETROL),
                entry(80_300, 30.0, 800, type = FuelType.LPG),
            )
        )

        assertEquals(FuelType.PETROL, stats.usage.single().fuelType)
        // Газ встречался — значит человек им заправлялся, и знать об этом надо.
        assertEquals(listOf(FuelType.PETROL, FuelType.LPG), stats.types)
    }

    @Test
    fun `электричество считается так же, между одинаковыми уровнями заряда`() {
        // 36 кВт·ч на 200 км = 18 кВт·ч на 100 км.
        val stats = FuelCalculator.stats(
            listOf(
                entry(10_000, 50.0, 500, type = FuelType.ELECTRIC),
                entry(10_200, 36.0, 360, type = FuelType.ELECTRIC),
            )
        )

        val charge = single(stats)!!
        assertEquals(18.0, charge.perHundredKm, 0.001)
        assertEquals(AmountUnit.KWH, UnitSystem.METRIC.amountUnit(charge.fuelType))
    }

    @Test
    fun `порядок видов не зависит от порядка заправок`() {
        val stats = FuelCalculator.stats(
            listOf(
                entry(10_000, 20.0, 200, type = FuelType.ELECTRIC),
                entry(10_100, 30.0, 2_000, type = FuelType.PETROL),
                entry(10_200, 20.0, 200, type = FuelType.CNG),
            )
        )

        assertEquals(listOf(FuelType.PETROL, FuelType.CNG, FuelType.ELECTRIC), stats.types)
    }
}
