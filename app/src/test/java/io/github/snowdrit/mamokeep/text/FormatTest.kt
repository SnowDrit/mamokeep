package io.github.snowdrit.mamokeep.text

import io.github.snowdrit.mamokeep.support.EN_WORDS
import io.github.snowdrit.mamokeep.support.RU_WORDS
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `разряды пробега разделяются пробелом`() {
        assertEquals("87 340", formatNumber(87340, RU_WORDS))
        assertEquals("999", formatNumber(999, RU_WORDS))
        assertEquals("1 000", formatNumber(1000, RU_WORDS))
        assertEquals("1 234 567", formatNumber(1234567, RU_WORDS))
    }

    @Test
    fun `крупная сумма не сужается до целого числа рублей`() {
        assertEquals("2 147 483 648,99", formatMoney(214_748_364_899L, RU_WORDS))
        assertEquals("2,147,483,648.99", formatMoney(214_748_364_899L, EN_WORDS))
        assertEquals("92 233 720 368 547 758,07", formatMoney(Long.MAX_VALUE, RU_WORDS))
    }

    @Test
    fun `форматирование сохраняет знак и крайние значения`() {
        assertEquals("−0,01", formatMoney(-1L, RU_WORDS))
        assertEquals("−1,01", formatMoney(-101L, RU_WORDS))
        assertEquals("−92 233 720 368 547 758,08", formatMoney(Long.MIN_VALUE, RU_WORDS))
        assertEquals("−2 147 483 648", formatNumber(Int.MIN_VALUE, RU_WORDS))
    }

    @Test
    fun `короткие сроки остаются в днях`() {
        assertEquals("0 дн.", humanDuration(0, RU_WORDS))
        assertEquals("14 дн.", humanDuration(14, RU_WORDS))
        assertEquals("59 дн.", humanDuration(59, RU_WORDS))
    }

    @Test
    fun `сроки от двух месяцев показываются в месяцах`() {
        assertEquals("2 мес.", humanDuration(60, RU_WORDS))
        assertEquals("6 мес.", humanDuration(183, RU_WORDS))
    }

    @Test
    fun `длинные сроки показываются в годах`() {
        assertEquals("1 г.", humanDuration(365, RU_WORDS))
        assertEquals("2 г.", humanDuration(731, RU_WORDS))
        assertEquals("5 г.", humanDuration(1826, RU_WORDS))
    }

    @Test
    fun `точка на стыке кусков не удваивается`() {
        // Именно этот дефект дважды всплывал в разных местах: «12 мес..».
        assertEquals(
            "В порядке, осталось 1 г. Интервал: 12 мес.",
            joinSentences(listOf("В порядке, осталось 1 г.", "Интервал: 12 мес.")),
        )
    }

    @Test
    fun `пустые куски выбрасываются`() {
        assertEquals("Только это.", joinSentences(listOf("", "Только это", "  ")))
    }

    @Test
    fun `переведённые предложения сохраняют свои конечные знаки`() {
        assertEquals("交換が必要です。 次の点検です。", joinSentences(listOf("交換が必要です。", "次の点検です。")))
        assertEquals("已保存！ 继续？", joinSentences(listOf("已保存！", "继续？")))
        assertEquals("Готово! Продолжить?", joinSentences(listOf("Готово!", "Продолжить?")))
    }

    @Test
    fun `год с остатком показывает и месяцы`() {
        assertEquals("1 г. 6 мес.", humanDuration(548, RU_WORDS))
    }

    @Test
    fun `в английском разделители другие`() {
        // «7,8 mpg» в английском интерфейсе выглядит как опечатка, а «1 234» как две цифры.
        assertEquals("1,234,567", formatNumber(1234567, EN_WORDS))
        assertEquals("7.8", formatDecimal(7.84, EN_WORDS))
        assertEquals("1 y 6 mo", humanDuration(548, EN_WORDS))
        assertEquals("7,8", formatDecimal(7.84, RU_WORDS))
    }

    @Test
    fun `начальное количество округляется без машинного хвоста`() {
        assertEquals("5.416", formatInputDecimal(5.415527073342043, EN_WORDS))
        assertEquals("5,416", formatInputDecimal(5.415527073342043, RU_WORDS))
        assertEquals("20.5", formatInputDecimal(20.5, EN_WORDS))
        assertEquals("20,5", formatInputDecimal(20.5, RU_WORDS))
        assertEquals("20", formatInputDecimal(20.0, EN_WORDS))
        assertEquals("0", formatInputDecimal(0.0004, RU_WORDS))
        assertEquals("1000", formatInputDecimal(999.9996, EN_WORDS))
    }

    @Test
    fun `разряды в наборе — сто тысяч читаются глазом`() {
        assertEquals("100 000", groupDigits("100000", ' '))
    }

    @Test
    fun `короткие числа не трогаем`() {
        assertEquals("999", groupDigits("999", ' '))
        assertEquals("", groupDigits("", ' '))
    }

    @Test
    fun `ровно три цифры остаются без разделителя`() {
        assertEquals("100", groupDigits("100", ' '))
        assertEquals("1 000", groupDigits("1000", ' '))
    }

    @Test
    fun `разделитель берётся из языка, а не зашит`() {
        assertEquals("1,234,567", groupDigits("1234567", ','))
    }
}
