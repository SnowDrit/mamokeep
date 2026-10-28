package io.github.snowdrit.mamokeep.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Границы одометра вокруг даты — основание предупреждения в редакторе заправки.
 *
 * Заправка хранит показание одометра внутри себя, а лента показаний сортируется по дате.
 * Значит заправка, передвинутая на чужую дату, делает «последним известным» меньшее
 * число, и пробег машины едет назад — тихо, без единого признака ошибки на экране.
 */
class OdometerBoundsTest {

    private val day = LocalDate.of(2026, 8, 22)

    @Test
    fun `без показаний границ нет`() {
        val bounds = emptyList<OdometerReading>().boundsAt(day)

        assertNull(bounds.maxBefore)
        assertNull(bounds.minAfter)
    }

    @Test
    fun `берём наибольшее до даты и наименьшее после`() {
        val readings = listOf(
            OdometerReading(day.minusDays(30), 78_000),
            OdometerReading(day.minusDays(10), 79_000),
            OdometerReading(day.plusDays(10), 81_000),
            OdometerReading(day.plusDays(30), 82_000),
        )

        val bounds = readings.boundsAt(day)

        assertEquals(79_000, bounds.maxBefore)
        assertEquals(81_000, bounds.minAfter)
    }

    @Test
    fun `показания того же дня не считаются противоречием`() {
        // За сутки одометр не убывает: 80 400 и 80 000 в один день — это одна поездка,
        // а не ошибка. Тот же порядок держит OdometerReading.merge.
        val readings = listOf(
            OdometerReading(day, 80_000),
            OdometerReading(day, 80_400),
        )

        val bounds = readings.boundsAt(day)

        assertNull(bounds.maxBefore)
        assertNull(bounds.minAfter)
    }

    @Test
    fun `заправка, уехавшая в прошлое, видна по границе после даты`() {
        // Заправку на 80 000 км переносят на 15 июля, а на 1 августа уже записано 79 000.
        // После сохранения «последним известным» станет 79 000 — пробег уедет назад.
        val readings = listOf(OdometerReading(LocalDate.of(2026, 8, 1), 79_000))

        val bounds = readings.boundsAt(LocalDate.of(2026, 7, 15))

        assertNull(bounds.maxBefore)
        assertEquals(79_000, bounds.minAfter)
    }

    @Test
    fun `заправка, уехавшая в будущее, видна по границе до даты`() {
        // Обратный случай: заправку на 79 000 переносят на дату позже показания 80 000.
        val readings = listOf(OdometerReading(LocalDate.of(2026, 9, 1), 80_000))

        val bounds = readings.boundsAt(LocalDate.of(2026, 9, 20))

        assertEquals(80_000, bounds.maxBefore)
        assertNull(bounds.minAfter)
    }
}
