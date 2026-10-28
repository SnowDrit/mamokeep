package io.github.snowdrit.mamokeep.text

import android.content.Context
import android.text.format.Formatter
import io.github.snowdrit.mamokeep.domain.Consumption
import io.github.snowdrit.mamokeep.domain.Display
import io.github.snowdrit.mamokeep.domain.DisplayWords
import io.github.snowdrit.mamokeep.domain.FuelType
import io.github.snowdrit.mamokeep.domain.RgbColor
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Числа и склейка фраз — всё в одном месте.
 *
 * Правило появилось дорогой ценой: дважды в проекте всплывали «12 мес..» с двойной точкой,
 * потому что склейка жила по месту вызова. Разделители тоже здесь: по-русски дробная часть
 * отделяется запятой, по-английски точкой, и разъехаться это не должно.
 */

private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/**
 * Дата в том виде, в каком её показывает приложение.
 *
 * Здесь по той же причине, по какой здесь числа: тот же `dd.MM.yyyy` успел разойтись
 * копиями по нескольким экранам, и разъехаться им ничто не мешает.
 */
fun LocalDate.dateText(): String = format(DATE)

/** Размер файлов с единицами и разделителями выбранного языка приложения. */
fun formatFileSize(context: Context, bytes: Long): String =
    Formatter.formatFileSize(context, bytes.coerceAtLeast(0))

/** Разряды разделителем ради читаемости: 87340 → «87 340». */
fun formatNumber(value: Int, words: DisplayWords): String =
    formatNumber(value.toLong(), words)

fun formatNumber(value: Long, words: DisplayWords): String =
    groupDigits(value.toString().removePrefix("-"), words.groupSeparator)
        .let { if (value < 0) "−$it" else it }

/**
 * Разряды в том, что человек **набирает**, а не читает.
 *
 * «100000» глазом не читается: чтобы понять, сто тысяч это или миллион, приходится
 * считать нули пальцем. На экране разряды уже разделены (см. [formatNumber]) — поле ввода
 * не должно быть исключением из этого правила.
 *
 * На вход только цифры, поэтому знака и дробной части здесь нет: это не форматирование
 * числа, а расстановка пробелов в наборе.
 */
fun groupDigits(digits: String, separator: Char): String =
    digits.reversed().chunked(3).joinToString(separator.toString()).reversed()

/**
 * Срок человеческими словами.
 *
 * «365 дн.» формально верно, но так никто не говорит про замену масла. Считать в днях
 * осмысленно, только пока их мало; дальше человек мыслит месяцами и годами.
 */
fun humanDuration(days: Long, words: DisplayWords): String {
    val absolute = abs(days)
    return when {
        absolute < 60 -> "$days ${words.days}"
        absolute < 365 -> "${months(days)} ${words.months}"
        else -> {
            // Годы усекаем, а не округляем: округление вверх делает остаток
            // отрицательным и даёт «2 г. -6 мес.».
            val years = (absolute / 365.25).toLong()
            val restMonths = months(absolute - (years * 365.25).roundToLong())
            when {
                restMonths <= 0L -> "$years ${words.years}"
                restMonths >= 12L -> "${years + 1} ${words.years}"
                else -> "$years ${words.years} $restMonths ${words.months}"
            }
        }
    }
}

private fun months(days: Long): Long = (days / 30.44).roundToLong()

/**
 * Деньги из копеек. Дробная часть отделяется так, как принято в языке, а разряды —
 * пробелом или запятой: так же выглядит ценник на колонке.
 */
fun formatMoney(kopecks: Long, words: DisplayWords): String {
    val whole = formatNumber(kopecks / 100, words)
        .let { if (kopecks in -99L..-1L) "−$it" else it }
    val rest = abs(kopecks % 100).toInt()
    return if (rest == 0) whole else "$whole${words.decimalSeparator}${rest.toString().padStart(2, '0')}"
}

/** Дробное число: «42,3» по-русски, «42.3» по-английски. */
fun formatDecimal(value: Double, words: DisplayWords, digits: Int = 1): String =
    String.format(java.util.Locale.ROOT, "%.${digits}f", value).replace('.', words.decimalSeparator)

/** Editable quantities stay readable; the editor retains the original value until changed. */
fun formatInputDecimal(value: Double, words: DisplayWords): String =
    formatDecimal(value, words, digits = 3).trimEnd('0').trimEnd(words.decimalSeparator)

/** Colour codes use a language-independent notation that can be pasted back into the editor. */
fun RgbColor.hexText(): String = String.format(java.util.Locale.ROOT, "#%02X%02X%02X", red, green, blue)

fun RgbColor.rgbText(): String = "$red, $green, $blue"

/**
 * Завершает предложение, сохраняя знаки препинания перевода. Сокращения вроде «мес.»
 * не получают вторую точку, а японское «。» не превращается в «。.».
 */
fun sentence(text: String): String {
    val trimmed = text.trimEnd('.', ' ')
    return when (trimmed.lastOrNull()) {
        '!', '?', '。', '！', '？', '…' -> trimmed
        else -> "$trimmed."
    }
}

/**
 * Склеивает куски в предложения, не плодя лишних точек. Куски могут заканчиваться
 * сокращением с точкой — она не должна удваиваться на стыке.
 */
fun joinSentences(parts: List<String>): String =
    parts.filter { it.isNotBlank() }.joinToString(" ") { sentence(it) }

// --- Всё, что зависит от выбранных единиц. Отдельными расширениями, чтобы на местах
// --- вызова не приходилось каждый раз вспоминать, что во что переводится.

/** Километры из базы → «87 340 км» или «54 270 миль». */
fun Display.distanceText(km: Int): String =
    "${formatNumber(units.distance(km).roundToInt(), words)} $distanceUnit"

/** Уже пересчитанное расстояние — например средний дневной пробег. */
fun Display.distanceText(value: Double): String =
    "${formatDecimal(value, words)} $distanceUnit"

/** Литры из базы → «42 л», «11,1 гал», «18 кВт·ч». */
fun Display.amountText(liters: Double, type: FuelType): String =
    "${formatDecimal(units.volume(liters, type), words)} ${amountUnit(type)}"

fun Display.moneyText(kopecks: Long): String = "${formatMoney(kopecks, words)} $currency"

fun Display.durationText(days: Long): String = humanDuration(days, words)

/**
 * Расход: «8,0 л на 100 км» или «29,4 mpg».
 *
 * Две формы, потому что это две разные величины: метрический расход — объём на
 * расстояние, mpg — расстояние на объём. Складывать их в одну строку с общей единицей
 * значило бы соврать в знаменателе.
 *
 * @param ofTotal у машины с несколькими видами топлива добавляется «пробега»: приложение
 * не знает, какую часть пути машина прошла именно на этом виде. У mpg приписывать некуда —
 * там оговорка живёт отдельной строкой под списком видов.
 */
fun Display.consumptionText(consumption: Consumption, ofTotal: Boolean = false): String {
    val number = formatDecimal(consumption.value, words)
    if (consumption.amountUnit == null) return "$number ${words.mpg}"

    val tail = if (ofTotal) " ${words.ofTotal}" else ""
    return "$number ${consumptionUnit(consumption)} ${words.perHundred} 100 $distanceUnit$tail"
}

/** Свободный текст остаётся одной строкой данных и не создаёт блоки Markdown. */
private val MARKDOWN_WHITESPACE = Regex("\\s+")

fun String.markdownInline(): String = trim().replace(MARKDOWN_WHITESPACE, " ").buildMarkdownInline()

private fun String.buildMarkdownInline(): String = buildString {
    for (char in this@buildMarkdownInline) {
        if (char in "\\`*_[]<>") append('\\')
        if (!char.isISOControl()) append(char)
    }
}
