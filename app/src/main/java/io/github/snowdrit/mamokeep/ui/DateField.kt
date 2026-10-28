package io.github.snowdrit.mamokeep.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.text.dateText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val MILLIS_IN_DAY = 86_400_000L

/**
 * Поле даты с календарём.
 *
 * Вынесено из отметки «сделано», когда дата понадобилась и заправке: третье место с той
 * же раскладкой — это уже не совпадение, а общий узел. Копия развела бы поведение
 * незаметно, как это уже случалось в проекте с форматированием чисел.
 *
 * Поле только читается: дату набирают в календаре, а не буквами — «31.02» руками ввести
 * можно, календарём нельзя. Нажатие на само поле открывает календарь; отдельной кнопки
 * «Выбрать дату» нет — поле, которое выглядит нажимаемым, но требует кнопку рядом,
 * это схема из тех времён, когда полей ввода было мало.
 *
 * Слой поверх нужен потому, что `readOnly`-поле забирает нажатие себе, и `clickable`
 * на его собственном модификаторе не срабатывает. Подпись действия оставлена для
 * TalkBack: экранный диктор скажет «Выбрать дату».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    date: LocalDate,
    onDate: (LocalDate) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    /**
     * Дальше этого дня календарь не пускает. `null` — не ограничивать, и это не лень:
     * полис можно продлить с первого числа следующего месяца, и запрет заставил бы
     * человека соврать на неделю.
     */
    maxDate: LocalDate? = null,
    enabled: Boolean = true,
) {
    var picking by remember { mutableStateOf(false) }
    val dateValue = date.dateText()

    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = dateValue,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier
                .matchParentSize()
                .semantics { contentDescription = "$label, $dateValue" }
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.record_date_pick),
                    onClick = { picking = true },
                )
        )
    }

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.toEpochDay() * MILLIS_IN_DAY,
            selectableDates = remember(maxDate) { UpTo(maxDate) },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let {
                            // Календарь отдаёт полночь UTC: переводим в дату без часового
                            // пояса, иначе на востоке от Гринвича день уезжает на вчера.
                            onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                        picking = false
                    },
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

/**
 * Дни после [last] в календаре недоступны.
 *
 * Годы гасим тоже: без этого человек проваливается в выбор года, тычет в 2030-й и
 * упирается в сетку, где нельзя нажать ни один день, — а почему, экран не объясняет.
 */
@OptIn(ExperimentalMaterial3Api::class)
private class UpTo(private val last: LocalDate?) : SelectableDates {

    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        last == null || utcTimeMillis <= last.toEpochDay() * MILLIS_IN_DAY

    override fun isSelectableYear(year: Int): Boolean =
        last == null || year <= last.year
}
