package io.github.snowdrit.mamokeep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.domain.Display
import io.github.snowdrit.mamokeep.domain.ItemKind
import io.github.snowdrit.mamokeep.domain.MaintenanceDefaults
import io.github.snowdrit.mamokeep.domain.MaintenanceItem
import io.github.snowdrit.mamokeep.text.distanceText
import io.github.snowdrit.mamokeep.domain.MaintenanceRecord
import io.github.snowdrit.mamokeep.domain.UnitSystem
import io.github.snowdrit.mamokeep.domain.ReminderRule
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** Порог по умолчанию у новой позиции: 500 км или 300 миль — круглое число в обоих случаях. */
private fun defaultWarnKm(units: UnitSystem): Int =
    if (units.milesBased) units.kmFrom(300.0) else 500

/**
 * Редактор позиции обслуживания.
 *
 * Пустое поле интервала означает «по этой оси не считать» — так выражается, например,
 * «колодки только по пробегу» или «страховка только по времени». Это осознанное состояние,
 * а не незаполненная форма, поэтому пустоту не подсвечиваем ошибкой.
 */
@Composable
fun ItemEditor(
    item: MaintenanceItem?,
    kind: ItemKind,
    records: List<MaintenanceRecord>,
    display: Display,
    onDismiss: () -> Unit,
    onSave: (MaintenanceItem, ReminderRule?) -> Unit,
    onDelete: (() -> Unit)?,
    defaultReminderRule: ReminderRule = ReminderRule(),
    reminderOverride: ReminderRule? = null,
    saving: Boolean = false,
    saveError: String? = null,
    /**
     * Пояснение к документу из пакета региона: почему срок именно такой. Знание про
     * страну, а не про приложение, поэтому приезжает данными (решение 8).
     */
    note: String? = null,
    modifier: Modifier = Modifier,
) {
    val units = display.units

    // Интервалы хранятся в километрах, а показываются и вводятся в единицах человека:
    // 8 047 км — это ровно 5 000 миль, и обратно тоже 5 000.
    fun toDisplay(km: Int?): String = km?.let { units.distance(it).roundToInt().toString() }.orEmpty()

    var title by rememberSaveable { mutableStateOf(item?.title.orEmpty()) }
    var km by rememberSaveable { mutableStateOf(toDisplay(item?.intervalKm)) }
    var months by rememberSaveable { mutableStateOf(item?.intervalMonths?.toString().orEmpty()) }
    var warnKm by rememberSaveable { mutableStateOf(toDisplay(item?.warnKm ?: defaultWarnKm(units))) }
    var warnDays by rememberSaveable { mutableStateOf((item?.warnDays ?: 14).toString()) }
    var iconKey by rememberSaveable { mutableStateOf(item?.iconKey) }
    var useCommonReminders by rememberSaveable { mutableStateOf(reminderOverride == null) }
    var thresholdsOpen by rememberSaveable { mutableStateOf(false) }
    val reminderDraft = rememberReminderRuleDraft(reminderOverride ?: defaultReminderRule, display)

    val isDocument = kind == ItemKind.DOCUMENT
    val canSave = !saving && (useCommonReminders || reminderDraft.isValid) && title.isNotBlank() &&
        (isDocument && months.toIntOrNull() != null ||
            !isDocument && (km.toIntOrNull() != null || months.toIntOrNull() != null))

    EditorScaffold(
        title = when {
            item != null && isDocument -> stringResource(R.string.item_edit_document)
            item != null -> stringResource(R.string.item_edit)
            isDocument -> stringResource(R.string.item_new_document)
            else -> stringResource(R.string.item_new)
        },
        onClose = { if (!saving) onDismiss() },
        onSave = if (!canSave) null else {
            {
                onSave(
                    MaintenanceItem(
                        id = item?.id ?: 0L,
                        kind = kind,
                        title = title.trim(),
                        // Ключ держится, пока человек не тронул название. Тронул —
                        // значит назвал позицию по-своему, и подставлять ей слово из
                        // ресурсов больше нельзя. Раньше ключ терялся от одного
                        // нажатия «Сохранить», даже когда в поле ничего не меняли,
                        // и позиция навсегда застывала на текущем языке.
                        titleKey = item?.titleKey?.takeIf { title.trim() == item.title },
                        iconKey = iconKey,
                        intervalKm = if (isDocument) null else km.toIntOrNull()?.let {
                            units.kmFrom(it.toDouble())
                        },
                        intervalMonths = months.toIntOrNull(),
                        lastDoneKm = item?.lastDoneKm,
                        lastDoneDate = item?.lastDoneDate,
                        warnKm = if (isDocument) {
                            0
                        } else {
                            warnKm.toIntOrNull()?.let { units.kmFrom(it.toDouble()) } ?: 500
                        },
                        warnDays = warnDays.toIntOrNull() ?: 14,
                    ),
                    if (useCommonReminders) null else reminderDraft.toRuleOrNull(),
                )
            }
        },
        modifier = modifier,
    ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(if (isDocument) stringResource(R.string.item_what_document) else stringResource(R.string.item_what)) },
                modifier = Modifier.fillMaxWidth(),
            )

            if (item == null && !isDocument) {
                val fullServiceTitle = stringResource(R.string.reminder_full_service_title)
                TextButton(onClick = { title = fullServiceTitle }) {
                    Text(stringResource(R.string.reminder_full_service_use))
                }
                Text(stringResource(R.string.reminder_full_service_hint), style = MaterialTheme.typography.bodySmall)
            }
            if (saveError != null) Text(saveError, color = MaterialTheme.colorScheme.error)

            if (isDocument) {
                IconPicker(selected = iconKey, kind = kind, onSelect = { iconKey = it })

                // Километровой оси у документа нет: страховка не зависит от пробега.
                NumberField(months, { months = it }, stringResource(R.string.item_valid_months))
                Text(
                    stringResource(R.string.item_document_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

            } else {
                IconPicker(
                selected = iconKey,
                kind = kind,
                onSelect = { iconKey = it },
            )

            Text(stringResource(R.string.item_when_earlier), style = MaterialTheme.typography.labelLarge)

                NumberField(
                    km,
                    { km = it },
                    stringResource(R.string.item_every_distance, display.distanceUnit),
                    GroupedDigitsTransformation(display.words.groupSeparator),
                )
                NumberField(months, { months = it }, stringResource(R.string.item_every_months))

                Text(
                    stringResource(R.string.maint_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

            }

            TextButton(onClick = { thresholdsOpen = !thresholdsOpen }) {
                Text(stringResource(if (thresholdsOpen) R.string.reminder_threshold_hide else R.string.reminder_threshold_edit))
            }
            if (thresholdsOpen) {
                Text(stringResource(R.string.reminder_threshold_hint), style = MaterialTheme.typography.bodySmall)
                if (!isDocument) {
                NumberField(
                    warnKm,
                    { warnKm = it },
                    stringResource(R.string.item_warn_distance, display.distanceUnit),
                    GroupedDigitsTransformation(display.words.groupSeparator),
                )
                }
                NumberField(warnDays, { warnDays = it }, stringResource(R.string.item_warn_days_and))
            }

            HorizontalDivider()
            Text(stringResource(R.string.reminder_item_heading), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().toggleable(
                value = useCommonReminders, enabled = !saving, role = Role.Switch,
                onValueChange = { useCommonReminders = it },
            ), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.reminder_use_common), Modifier.weight(1f))
                Switch(checked = useCommonReminders, onCheckedChange = null, enabled = !saving)
            }
            if (useCommonReminders) {
                Text(stringResource(R.string.reminder_common_hint), style = MaterialTheme.typography.bodySmall)
            } else {
                ReminderRuleFields(reminderDraft, display, enabled = !saving, showDistance = !isDocument)
            }

            if (records.isNotEmpty()) {
                HorizontalDivider()
                Text(if (isDocument) stringResource(R.string.item_issued_when) else stringResource(R.string.item_done_when), style = MaterialTheme.typography.labelLarge)
                records.forEach { record ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(record.date.format(DATE), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            record.km?.let { display.distanceText(it) }
                                ?: stringResource(R.string.item_km_unknown),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (onDelete != null) {
                HorizontalDivider()
                TextButton(onClick = onDelete, enabled = !saving) {
                    Text(if (isDocument) stringResource(R.string.item_delete_document) else stringResource(R.string.item_delete))
                }
            }
    }
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    /**
     * Разряды разделяются у пробега и не разделяются у месяцев и дней: «10 000 км»
     * читается лучше, чем «10000», а «60 мес.» дробить не на что.
     */
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { new -> onValueChange(new.filter(Char::isDigit).take(7)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        visualTransformation = visualTransformation,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Выбор значка.
 *
 * Список длинный намеренно: узнавание по картинке — единственное, что делает список
 * из полутора десятков позиций читаемым с одного взгляда.
 */
@Composable
private fun IconPicker(
    selected: String?,
    kind: io.github.snowdrit.mamokeep.domain.ItemKind,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.item_icon), style = MaterialTheme.typography.labelLarge)

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ItemIcons.ALL.forEach { icon ->
                val isSelected = icon.key == selected
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        .border(
                            width = if (isSelected) 2.dp else 0.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelect(icon.key) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(icon.res),
                        contentDescription = stringResource(icon.label),
                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            stringResource(
                ItemIcons.ALL.firstOrNull { it.key == selected }?.label ?: R.string.item_icon_none
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
