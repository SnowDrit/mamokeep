package io.github.snowdrit.mamokeep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.domain.Display
import io.github.snowdrit.mamokeep.domain.ItemKind
import io.github.snowdrit.mamokeep.domain.MaintenanceItem
import io.github.snowdrit.mamokeep.domain.JournalEntry
import io.github.snowdrit.mamokeep.domain.MileageEstimator
import io.github.snowdrit.mamokeep.domain.OdometerReading
import io.github.snowdrit.mamokeep.domain.UnitSystem
import io.github.snowdrit.mamokeep.text.dateText
import java.time.LocalDate
import kotlin.math.roundToInt
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.snowdrit.mamokeep.ui.scanner.EditorOperationViewModel
import java.util.UUID

/**
 * Отметка «сделано» — и когда её заводят, и когда правят.
 *
 * Отметить можно одним касанием, в том числе из шторки уведомления, значит и промахнуться
 * можно одним касанием. Без этого экрана человеку оставалось бы жить с неверной датой
 * обслуживания, а от неё считается срок следующего.
 *
 * Пробег необязателен: масло могли менять «где-то в мае», не посмотрев на одометр. Пустое
 * поле честнее выдуманного числа — тогда срок считается только по дате.
 */
@Composable
fun RecordEditor(
    /** Чем сбрасывать состояние полей: `recordId` при правке, `id` позиции при заведении. */
    resetKey: Any,
    title: String,
    document: Boolean,
    initialDate: LocalDate,
    initialKm: Int?,
    display: Display,
    onDismiss: () -> Unit,
    /** `itemId` не `null` только там, где отметку можно перенести, — то есть в правке. */
    onSave: suspend (itemId: Long?, date: LocalDate, km: Int?, note: String?) -> Unit,
    /** `null` — отметку только заводят, удалять ещё нечего. */
    onDelete: (suspend () -> Unit)? = null,
    initialNote: String? = null,
    /**
     * Позиции той же машины: отметку можно перенести на любую из них. Пусто — переносить
     * некуда, и тогда вместо списка стоит просто название.
     *
     * Чужие машины сюда не попадают сознательно: отметка несёт пробег своей машины.
     */
    items: List<MaintenanceItem> = emptyList(),
    initialItemId: Long? = null,
    /** Что останется у покинутой позиции. `null` — ещё не посчитали. */
    remaining: Remaining? = null,
    /**
     * Показания одометра машины. Если они есть, пробег едет вслед за датой: поставил
     * «20 июля» — увидел пробег на 20 июля, а не сегодняшний.
     */
    readings: List<OdometerReading> = emptyList(),
    /**
     * Начальное число в поле посчитано нами, а не набрано человеком. У правки записи это
     * не так: там число человек либо вписал сам, либо уже принял.
     */
    kmIsEstimated: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val units = display.units
    var date by rememberSaveable(resetKey) { mutableStateOf(initialDate) }
    var km by rememberSaveable(resetKey) {
        mutableStateOf(initialKm?.let { units.distance(it).roundToInt().toString() }.orEmpty())
    }
    var confirmDelete by rememberSaveable(resetKey) { mutableStateOf(false) }
    var itemId by rememberSaveable(resetKey) { mutableStateOf(initialItemId) }
    var note by rememberSaveable(resetKey) { mutableStateOf(initialNote.orEmpty()) }
    val session = rememberSaveable(resetKey) { UUID.randomUUID().toString() }
    val operation: EditorOperationViewModel = viewModel(key = "record-editor-operation")
    LaunchedEffect(session) { operation.attach(session) }
    LaunchedEffect(operation.completed) {
        if (operation.completed) { operation.reset(); onDismiss() }
    }
    val close = { if (!operation.busy) { operation.reset(); onDismiss() } }

    val source = items.firstOrNull { it.id == initialItemId }
    val selected = items.firstOrNull { it.id == itemId }
    // Заголовок идёт за выбранной позицией: перенесли отметку на документ, и экран обязан
    // называться «Продление», иначе он врёт о том, что человек только что сделал.
    val isDocument = selected?.let { it.kind == ItemKind.DOCUMENT } ?: document

    /**
     * Число в поле посчитано нами, а не набрано человеком.
     *
     * Пока это так, оно едет за датой. Как только человек тронул поле — число его, и
     * молча переписывать чужую цифру мы не станем ни при какой смене даты.
     */
    var kmIsOurs by rememberSaveable(resetKey) { mutableStateOf(kmIsEstimated) }

    EditorScaffold(
        title = stringResource(
            if (isDocument) R.string.record_title_document else R.string.record_title
        ),
        onClose = close,
        onSave = if (operation.busy) null else ({
            val savedItem = itemId
            val savedDate = date
            val savedKm = recordOdometerForSave(km, initialKm, units, kmIsEstimated)
            val savedNote = if (initialNote != null) note else null
            operation.run { onSave(savedItem, savedDate, savedKm, savedNote) }
        }),
        modifier = modifier,
    ) {
                if (operation.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (operation.failed || operation.interrupted) Text(
                    stringResource(if (operation.failed) R.string.ocr_save_failed else R.string.ocr_save_interrupted),
                    color = MaterialTheme.colorScheme.error,
                )
                if (initialNote != null) OutlinedTextField(
                    value = note,
                    onValueChange = { if (!operation.busy) note = it },
                    label = { Text(stringResource(R.string.ocr_source_text)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !operation.busy,
                )
                if (items.isEmpty()) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                } else {
                    ItemPicker(items = items, selected = itemId, onSelect = { if (!operation.busy) itemId = it })

                    // Последствие для покинутой позиции — словами и до сохранения. Иначе
                    // человек переносит отметку и не видит, что у прежней позиции только
                    // что уехал срок следующего обслуживания.
                    if (itemId != initialItemId && source != null && remaining != null) {
                        Text(
                            remaining.date?.let {
                                stringResource(R.string.record_move_leaves, source.title, it.dateText())
                            } ?: stringResource(R.string.record_move_empties, source.title),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                DateField(
                    date = date,
                    onDate = { picked ->
                        date = picked

                        // Пробег на выбранный день. Оставить сегодняшний одометр у даты
                        // месячной давности значит записать неправду, от которой уедет
                        // срок следующего обслуживания.
                        if (kmIsOurs && readings.isNotEmpty()) {
                            km = MileageEstimator.kmAt(readings, picked)
                                ?.let { units.distance(it).roundToInt().toString() }
                                .orEmpty()
                        }
                    },
                    label = stringResource(R.string.record_date),
                    enabled = !operation.busy,
                    // Границы нет сознательно: полис можно продлить с первого числа
                    // следующего месяца, и запрет заставил бы соврать на неделю.
                    // Записи истории в оценку пробега не входят, испортить им нечего.
                )

                OutlinedTextField(
                    value = km,
                    onValueChange = {
                        km = it.filter(Char::isDigit).take(7)
                        kmIsOurs = false
                    },
                    label = { Text(stringResource(R.string.record_km, display.distanceUnit)) },
                    singleLine = true,
                    enabled = !operation.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    visualTransformation = GroupedDigitsTransformation(display.words.groupSeparator),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(
                        if (kmIsOurs && km.isNotEmpty()) R.string.record_km_estimated
                        else R.string.record_km_hint
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

        // Удаление внизу, а не рядом с «Сохранить»: промахнуться по соседней кнопке
        // здесь стоило бы записи в истории обслуживания.
        if (onDelete != null) {
            HorizontalDivider()
            TextButton(
                onClick = { confirmDelete = true },
                enabled = !operation.busy,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(stringResource(R.string.record_delete)) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.record_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        operation.run { onDelete?.invoke() }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/**
 * Что останется у покинутой позиции после переноса отметки.
 *
 * Обёртка, а не голая дата: `null` внутри значит «не останется ничего», а сам `null`
 * снаружи — «ещё не посчитали». Различать эти два случая обязательно, иначе экран на
 * долю секунды соврёт, что позиция станет неотмеченной.
 */
@JvmInline
value class Remaining(val date: LocalDate?)

/**
 * Выбор позиции списком, с теми же двумя заголовками, что на главном экране.
 *
 * Род не ограничиваем: промах случается и в шторке уведомлений, где просроченные масло и
 * ОСАГО лежат рядом и «Сделано» жмётся одним касанием у любого. Ограничить перенос родом
 * значило бы оставить в дыре щель того же размера.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemPicker(items: List<MaintenanceItem>, selected: Long?, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = items.firstOrNull { it.id == selected }
    val (documents, maintenance) = items.partition { it.kind == ItemKind.DOCUMENT }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = current?.title.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.record_item)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Section(stringResource(R.string.home_maintenance))
            maintenance.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.title) },
                    onClick = {
                        onSelect(item.id)
                        expanded = false
                    },
                )
            }

            if (documents.isNotEmpty()) {
                HorizontalDivider()
                Section(stringResource(R.string.home_documents))
                documents.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.title) },
                        onClick = {
                            onSelect(item.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/** Existing facts keep exact kilometres; a new estimated reading still follows its current date/field. */
internal fun recordOdometerForSave(
    text: String,
    initialKm: Int?,
    units: UnitSystem,
    initialWasEstimated: Boolean,
): Int? {
    val initialText = initialKm?.let { units.distance(it).roundToInt().toString() }
    return if (!initialWasEstimated && initialKm != null && text == initialText) initialKm
        else text.toIntOrNull()?.let { units.kmFrom(it.toDouble()) }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Правка записи журнала.
 *
 * Показания одометра сюда не передаются сознательно: число в такой записи человек либо
 * набрал сам, либо уже принял, и смена даты не повод его переписывать.
 */
@Composable
fun RecordEditor(
    entry: JournalEntry,
    display: Display,
    onDismiss: () -> Unit,
    onSave: suspend (itemId: Long?, date: LocalDate, km: Int?, note: String?) -> Unit,
    onDelete: suspend () -> Unit,
    /** Позиции той же машины: сюда отметку можно перенести. */
    items: List<MaintenanceItem> = emptyList(),
    remaining: Remaining? = null,
    modifier: Modifier = Modifier,
) = RecordEditor(
    resetKey = entry.recordId ?: entry,
    title = entry.title.orEmpty(),
    document = entry.kind == JournalEntry.Kind.DOCUMENT,
    initialDate = entry.date,
    initialKm = entry.km,
    initialNote = entry.note,
    display = display,
    onDismiss = onDismiss,
    onSave = onSave,
    onDelete = onDelete,
    items = items,
    initialItemId = entry.itemId,
    remaining = remaining,
    modifier = modifier,
)
