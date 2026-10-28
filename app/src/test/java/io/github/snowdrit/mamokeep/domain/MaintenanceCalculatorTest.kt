package io.github.snowdrit.mamokeep.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MaintenanceCalculatorTest {

    private val today = LocalDate.of(2026, 8, 19)

    private fun oil(
        lastDoneKm: Int? = 80_000,
        lastDoneDate: LocalDate? = today.minusDays(100),
    ) = MaintenanceItem(
        id = 1,
        kind = ItemKind.MAINTENANCE,
        title = "Замена масла",
        intervalKm = 10_000,
        intervalMonths = 12,
        lastDoneKm = lastDoneKm,
        lastDoneDate = lastDoneDate,
        warnKm = 500,
        warnDays = 14,
    )

    @Test
    fun `срок ещё далеко`() {
        val status = MaintenanceCalculator.status(oil(), currentKm = 85_000, today = today)

        assertEquals(5_000, status.kmLeft)
        assertEquals(265L, status.daysLeft)
        assertEquals(Urgency.OK, status.urgency)
    }

    @Test
    fun `порог по километрам переводит в SOON`() {
        val status = MaintenanceCalculator.status(oil(), currentKm = 89_600, today = today)

        assertEquals(400, status.kmLeft)
        assertEquals(Urgency.SOON, status.urgency)
    }

    @Test
    fun `просрочка по километрам`() {
        val status = MaintenanceCalculator.status(oil(), currentKm = 91_200, today = today)

        assertEquals(-1_200, status.kmLeft)
        assertEquals(Urgency.OVERDUE, status.urgency)
    }

    @Test
    fun `время срабатывает раньше километров`() {
        // По километрам ещё далеко, но год со дня замены уже прошёл.
        val status = MaintenanceCalculator.status(
            oil(lastDoneDate = today.minusDays(400)),
            currentKm = 82_000,
            today = today,
        )

        assertEquals(8_000, status.kmLeft)
        assertEquals(Urgency.OVERDUE, status.urgency)
    }

    @Test
    fun `износ считается по километрам`() {
        // Проехано 5000 из 10000 — половина ресурса.
        val status = MaintenanceCalculator.status(oil(), currentKm = 85_000, today = today)

        assertEquals(0.5, status.wear!!, 0.001)
    }

    @Test
    fun `износ берётся по той оси, где ресурс израсходован сильнее`() {
        // По километрам пройдена пятая часть, по времени — почти год из года.
        val status = MaintenanceCalculator.status(
            oil(lastDoneDate = today.minusDays(350)),
            currentKm = 82_000,
            today = today,
        )

        assertTrue(status.wear!! > 0.9)
    }

    @Test
    fun `перепробег даёт износ больше единицы`() {
        val status = MaintenanceCalculator.status(oil(), currentKm = 91_200, today = today)

        assertTrue(status.wear!! > 1.0)
    }

    @Test
    fun `без данных износа нет`() {
        val status = MaintenanceCalculator.status(
            oil(lastDoneKm = null, lastDoneDate = null), currentKm = 85_000, today = today,
        )

        assertNull(status.wear)
    }

    @Test
    fun `без отметки о выполнении считать не от чего`() {
        val status = MaintenanceCalculator.status(
            oil(lastDoneKm = null, lastDoneDate = null),
            currentKm = 85_000,
            today = today,
        )

        assertNull(status.kmLeft)
        assertNull(status.daysLeft)
        assertEquals(Urgency.UNKNOWN, status.urgency)
    }

    @Test
    fun `без известного пробега остаётся временная ось`() {
        val status = MaintenanceCalculator.status(oil(), currentKm = null, today = today)

        assertNull(status.kmLeft)
        assertEquals(265L, status.daysLeft)
        assertEquals(Urgency.OK, status.urgency)
    }
}
