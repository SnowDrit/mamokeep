package io.github.snowdrit.mamokeep.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import io.github.snowdrit.mamokeep.text.groupDigits

/**
 * Показывает набранное число с разделёнными разрядами: «100000» → «100 000».
 *
 * Разделитель **только показывается**. В состоянии поля по-прежнему лежат одни цифры, и
 * ни одна `toIntOrNull` по дороге не ломается — иначе пробел уехал бы в разбор числа.
 *
 * Отображение позиций строится по готовой строке, а не арифметикой по остатку от деления:
 * курсор в поле ввода — это то, что человек замечает мгновенно, и ошибка на единицу здесь
 * заметнее любой другой.
 */
class GroupedDigitsTransformation(private val separator: Char) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val grouped = groupDigits(digits, separator)

        // Где в показанной строке стоит каждая цифра исходной.
        val digitAt = IntArray(digits.length + 1)
        var seen = 0
        grouped.forEachIndexed { index, char ->
            if (char != separator) {
                digitAt[seen] = index
                seen++
            }
        }
        digitAt[digits.length] = grouped.length

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                digitAt[offset.coerceIn(0, digits.length)]

            override fun transformedToOriginal(offset: Int): Int {
                val cut = offset.coerceIn(0, grouped.length)
                return grouped.take(cut).count { it != separator }
            }
        }

        return TransformedText(AnnotatedString(grouped), mapping)
    }
}
