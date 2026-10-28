package io.github.snowdrit.mamokeep.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Показания одометра приходят из двух мест: их вводят руками и их вводят, отмечая
 * заправку. Заправка хранит свой одометр сама, поэтому свести источники должен домен.
 */
class OdometerReadingMergeTest {

    private fun day(n: Int) = LocalDate.of(2026, 1, n)

    @Test
    fun `оба источника попадают в ленту и выстраиваются по дате`() {
        val manual = listOf(OdometerReading(day(1), 1_000), OdometerReading(day(10), 3_000))
        val fromFuel = listOf(OdometerReading(day(5), 2_000))

        val merged = OdometerReading.merge(manual, fromFuel)

        assertEquals(listOf(1_000, 2_000, 3_000), merged.map { it.km })
    }

    @Test
    fun `совпадающие показания схлопываются`() {
        // Прежние сборки на каждую заправку писали ещё и строку показания: в старой
        // базе одна заправка лежит дважды.
        val manual = listOf(OdometerReading(day(3), 5_000))
        val fromFuel = listOf(OdometerReading(day(3), 5_000))

        assertEquals(1, OdometerReading.merge(manual, fromFuel).size)
    }

    @Test
    fun `за один день последним оказывается большее показание`() {
        // Заправился утром на 10 000, вечером записал одометр 10 400 — «последнее
        // известное» это 10 400, а не то, что легло в список позже.
        val manual = listOf(OdometerReading(day(7), 10_400))
        val fromFuel = listOf(OdometerReading(day(7), 10_000))

        assertEquals(10_400, OdometerReading.merge(manual, fromFuel).last().km)
        assertEquals(10_400, MileageEstimator.estimate(OdometerReading.merge(manual, fromFuel), day(7))?.km)
    }

    @Test
    fun `пустые источники не мешают`() {
        assertEquals(emptyList<OdometerReading>(), OdometerReading.merge(emptyList(), emptyList()))
    }
}
