package io.github.snowdrit.mamokeep.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MileageEstimatorTest {

    private val today = LocalDate.of(2026, 8, 19)

    @Test
    fun `без показаний оценки нет`() {
        assertNull(MileageEstimator.estimate(emptyList(), today))
    }

    @Test
    fun `по одному показанию ничего не достраиваем`() {
        val estimate = MileageEstimator.estimate(
            listOf(OdometerReading(today.minusDays(10), 87_340)),
            today,
        )!!

        assertEquals(87_340, estimate.km)
        assertFalse(estimate.isExtrapolated)
        assertNull(estimate.kmPerDay)
    }

    @Test
    fun `достраивает пробег по среднему за период между вводами`() {
        // 1500 км за 30 дней = 50 км в день; с последнего ввода прошло 10 дней.
        val estimate = MileageEstimator.estimate(
            listOf(
                OdometerReading(today.minusDays(40), 85_000),
                OdometerReading(today.minusDays(10), 86_500),
            ),
            today,
        )!!

        assertEquals(50.0, estimate.kmPerDay!!, 0.001)
        assertEquals(86_500 + 500, estimate.km)
        assertTrue(estimate.isExtrapolated)
        assertEquals(10L, estimate.daysSinceLastReading)
    }

    @Test
    fun `в день ввода ничего не добавляет`() {
        val estimate = MileageEstimator.estimate(
            listOf(
                OdometerReading(today.minusDays(30), 85_000),
                OdometerReading(today, 86_500),
            ),
            today,
        )!!

        assertEquals(86_500, estimate.km)
        assertFalse(estimate.isExtrapolated)
    }

    @Test
    fun `не экстраполирует, если одометр не вырос`() {
        // Скрученный или заменённый одометр: выдумывать средний пробег тут нельзя.
        val estimate = MileageEstimator.estimate(
            listOf(
                OdometerReading(today.minusDays(30), 86_500),
                OdometerReading(today.minusDays(5), 12_000),
            ),
            today,
        )!!

        assertNull(estimate.kmPerDay)
        assertEquals(12_000, estimate.km)
    }

    @Test
    fun `игнорирует показания старше окна усреднения`() {
        // Год назад ездил по 100 км в день, последние два месяца — по 10.
        val estimate = MileageEstimator.estimate(
            listOf(
                OdometerReading(today.minusDays(400), 20_000),
                OdometerReading(today.minusDays(60), 54_000),
                OdometerReading(today.minusDays(10), 54_500),
            ),
            today,
        )!!

        assertEquals(10.0, estimate.kmPerDay!!, 0.001)
    }
}
