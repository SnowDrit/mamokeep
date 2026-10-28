package io.github.snowdrit.mamokeep.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Пробег на прошедшую дату — основание отметки «сделано» задним числом.
 *
 * Человек ставит дату месячной давности; если оставить в поле сегодняшний одометр,
 * следующий срок обслуживания уедет вперёд на месяц езды.
 */
class MileageAtDateTest {

    private val today = LocalDate.of(2026, 8, 22)

    @Test
    fun `без показаний считать нечем`() {
        assertNull(MileageEstimator.kmAt(emptyList(), today.minusDays(30)))
    }

    @Test
    fun `в день ввода берём само показание, ничего не считая`() {
        val readings = listOf(
            OdometerReading(today.minusDays(30), 78_000),
            OdometerReading(today, 80_400),
        )

        assertEquals(78_000, MileageEstimator.kmAt(readings, today.minusDays(30)))
    }

    @Test
    fun `между двумя вводами считаем по прямой между ними`() {
        // 3000 км за 30 дней = 100 км в день; середина отрезка — ровно половина роста.
        val readings = listOf(
            OdometerReading(today.minusDays(30), 78_000),
            OdometerReading(today, 81_000),
        )

        assertEquals(79_500, MileageEstimator.kmAt(readings, today.minusDays(15)))
    }

    @Test
    fun `между вводами берётся ближайшая пара, а не весь ряд`() {
        // Первый отрезок пологий (10 км в день), второй крутой (200 км в день).
        // Дата попадает в пологий — крутизна второго её касаться не должна.
        val readings = listOf(
            OdometerReading(today.minusDays(40), 70_000),
            OdometerReading(today.minusDays(20), 70_200),
            OdometerReading(today, 74_200),
        )

        assertEquals(70_100, MileageEstimator.kmAt(readings, today.minusDays(30)))
    }

    @Test
    fun `за днём последнего ввода достраиваем средним дневным`() {
        // 1500 км за 30 дней = 50 км в день, сверху ещё 10 дней.
        val readings = listOf(
            OdometerReading(today.minusDays(40), 85_000),
            OdometerReading(today.minusDays(10), 86_500),
        )

        assertEquals(87_000, MileageEstimator.kmAt(readings, today))
    }

    @Test
    fun `раньше всей ленты отматываем назад тем же средним`() {
        // 50 км в день; на 10 дней раньше первого ввода — на 500 км меньше.
        val readings = listOf(
            OdometerReading(today.minusDays(30), 85_000),
            OdometerReading(today, 86_500),
        )

        assertEquals(84_500, MileageEstimator.kmAt(readings, today.minusDays(40)))
    }

    @Test
    fun `по одному показанию назад не отматываем — считать нечем`() {
        val readings = listOf(OdometerReading(today, 80_000))

        assertNull(MileageEstimator.kmAt(readings, today.minusDays(30)))
    }

    @Test
    fun `по одному показанию вперёд отдаём его же, а не выдуманное число`() {
        val readings = listOf(OdometerReading(today.minusDays(30), 80_000))

        assertEquals(80_000, MileageEstimator.kmAt(readings, today))
    }

    @Test
    fun `отрицательного пробега не бывает — упираемся в ноль`() {
        // 100 км в день; отмотка на 900 дней ушла бы глубоко в минус.
        val readings = listOf(
            OdometerReading(today.minusDays(10), 1_000),
            OdometerReading(today, 2_000),
        )

        assertEquals(0, MileageEstimator.kmAt(readings, today.minusDays(910)))
    }

    @Test
    fun `за день с двумя показаниями берём большее`() {
        // Заправка и ручной ввод в один день: «последним известным» должно быть большее.
        val readings = listOf(
            OdometerReading(today.minusDays(5), 80_000),
            OdometerReading(today.minusDays(5), 80_120),
            OdometerReading(today, 80_500),
        )

        assertEquals(80_120, MileageEstimator.kmAt(readings, today.minusDays(5)))
    }
}
