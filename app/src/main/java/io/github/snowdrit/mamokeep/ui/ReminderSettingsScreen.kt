package io.github.snowdrit.mamokeep.ui

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.ui.help.FeatureLesson
import io.github.snowdrit.mamokeep.domain.Display
import io.github.snowdrit.mamokeep.domain.ReminderRule
import io.github.snowdrit.mamokeep.domain.UnitSystem
import io.github.snowdrit.mamokeep.notifications.Notifications
import io.github.snowdrit.mamokeep.text.formatNumber
import io.github.snowdrit.mamokeep.ui.help.LessonButton
import io.github.snowdrit.mamokeep.ui.help.LessonHost
import io.github.snowdrit.mamokeep.ui.help.LessonSurface
import io.github.snowdrit.mamokeep.ui.help.LessonTarget
import io.github.snowdrit.mamokeep.ui.help.rememberLessonState
import io.github.snowdrit.mamokeep.ui.help.tourTarget
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Text fields stay as text until Save: deleting a number must not quietly save zero.
 * The original kilometre values also survive a save in miles without rounding drift.
 */
@Stable
class ReminderRuleDraft internal constructor(
    private val initial: ReminderRule,
    val units: UnitSystem,
) {
    var enabled by mutableStateOf(initial.enabled)
    var minuteOfDay by mutableIntStateOf(initial.minuteOfDay)
    var weekdays by mutableStateOf(initial.weekdays)
    var advancedEnabled by mutableStateOf(initial.advancedEnabled)
    var advancedExpanded by mutableStateOf(false)
    var yellowAdvance by mutableStateOf(displayDistance(initial.yellowAdvanceKm))
    var redAdvance by mutableStateOf(displayDistance(initial.redAdvanceKm))
    var yellowAdvanceDays by mutableStateOf(initial.yellowAdvanceDays.toString())
    var redAdvanceDays by mutableStateOf(initial.redAdvanceDays.toString())
    var yellowRepeatDays by mutableStateOf(initial.yellowRepeatDays.toString())
    var redRepeatDays by mutableStateOf(initial.redRepeatDays.toString())
    var yellowMaxOccurrences by mutableStateOf(initial.yellowMaxOccurrences.toString())
    var redMaxOccurrences by mutableStateOf(initial.redMaxOccurrences.toString())
    var timeDialogOpen by mutableStateOf(false)
    var timeHours by mutableStateOf((initial.minuteOfDay / 60).toString())
    var timeMinutes by mutableStateOf((initial.minuteOfDay % 60).toString())

    val maxDisplayDistance: Int get() = floor(units.distance(ReminderRule.MAX_ADVANCE_KM)).toInt()
    val isValid: Boolean get() = toRuleOrNull() != null

    fun toRuleOrNull(): ReminderRule? {
        if (weekdays.isEmpty() || weekdays.any { it !in 1..7 } || minuteOfDay !in 0..1439) return null
        // Switching custom rules off is an explicit return to the simple schedule.
        // Keep valid custom drafts for later, but don't let a hidden blank field block that action.
        fun number(text: String, min: Int, max: Int, original: Int): Int? =
            text.validNumber(min, max) ?: original.takeIf { !advancedEnabled }
        return ReminderRule(
            enabled = enabled,
            minuteOfDay = minuteOfDay,
            weekdays = weekdays,
            advancedEnabled = advancedEnabled,
            yellowAdvanceKm = distanceKm(yellowAdvance, initial.yellowAdvanceKm)
                ?: initial.yellowAdvanceKm.takeIf { !advancedEnabled } ?: return null,
            redAdvanceKm = distanceKm(redAdvance, initial.redAdvanceKm)
                ?: initial.redAdvanceKm.takeIf { !advancedEnabled } ?: return null,
            yellowAdvanceDays = number(yellowAdvanceDays, 0, ReminderRule.MAX_ADVANCE_DAYS, initial.yellowAdvanceDays) ?: return null,
            redAdvanceDays = number(redAdvanceDays, 0, ReminderRule.MAX_ADVANCE_DAYS, initial.redAdvanceDays) ?: return null,
            yellowRepeatDays = number(yellowRepeatDays, 1, ReminderRule.MAX_REPEAT_DAYS, initial.yellowRepeatDays) ?: return null,
            redRepeatDays = number(redRepeatDays, 1, ReminderRule.MAX_REPEAT_DAYS, initial.redRepeatDays) ?: return null,
            yellowMaxOccurrences = number(yellowMaxOccurrences, 0, ReminderRule.MAX_OCCURRENCES, initial.yellowMaxOccurrences) ?: return null,
            redMaxOccurrences = number(redMaxOccurrences, 0, ReminderRule.MAX_OCCURRENCES, initial.redMaxOccurrences) ?: return null,
        )
    }

    private fun displayDistance(km: Int): String = units.distance(km).roundToInt().toString()

    private fun distanceKm(text: String, original: Int): Int? {
        // Check unchanged text first: the maximum kilometre value can round up in miles.
        if (text == displayDistance(original)) return original
        return text.validNumber(0, maxDisplayDistance)?.let { units.kmFrom(it.toDouble()) }
    }

    internal fun save(): List<Any> = listOf(
        enabled, minuteOfDay, weekdays.sorted().joinToString(","), advancedEnabled,
        advancedExpanded, yellowAdvance, redAdvance, yellowAdvanceDays, redAdvanceDays,
        yellowRepeatDays, redRepeatDays, yellowMaxOccurrences, redMaxOccurrences,
        timeDialogOpen, timeHours, timeMinutes,
    )

    internal fun restore(saved: List<Any>) {
        enabled = saved[0] as Boolean
        minuteOfDay = saved[1] as Int
        weekdays = (saved[2] as String).split(",").mapNotNull(String::toIntOrNull).toSet()
        advancedEnabled = saved[3] as Boolean
        advancedExpanded = saved[4] as Boolean
        yellowAdvance = saved[5] as String
        redAdvance = saved[6] as String
        yellowAdvanceDays = saved[7] as String
        redAdvanceDays = saved[8] as String
        yellowRepeatDays = saved[9] as String
        redRepeatDays = saved[10] as String
        yellowMaxOccurrences = saved[11] as String
        redMaxOccurrences = saved[12] as String
        timeDialogOpen = saved[13] as Boolean
        timeHours = saved[14] as String
        timeMinutes = saved[15] as String
    }
}

private fun String.validNumber(min: Int, max: Int): Int? =
    toIntOrNull()?.takeIf { it in min..max }

@Composable
fun rememberReminderRuleDraft(initial: ReminderRule, display: Display): ReminderRuleDraft =
    rememberSaveable(
        initial, display.units,
        saver = listSaver(
            save = { it.save() },
            restore = { ReminderRuleDraft(initial, display.units).apply { restore(it) } },
        ),
    ) { ReminderRuleDraft(initial, display.units) }

@Composable
fun ReminderSettingsScreen(
    initial: ReminderRule,
    display: Display,
    onSave: (ReminderRule) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    saving: Boolean = false,
    error: String? = null,
    lessonDefinition: FeatureLesson,
    startLesson: Boolean = false,
    onLessonStartConsumed: () -> Unit,
) {
    val draft = rememberReminderRuleDraft(initial, display)
    val lesson = rememberLessonState(lessonDefinition, startLesson, onLessonStartConsumed)
    val revealAdvanced = lesson.active && lesson.step?.reveal == "advanced"
    LessonHost(lesson, LessonSurface.REMINDERS, modifier) {
        EditorScaffold(
            title = stringResource(R.string.reminders_title),
            onClose = { if (!saving) onDismiss() },
            onSave = if (saving || lesson.active || !draft.isValid) null else {
                { draft.toRuleOrNull()?.let(onSave) }
            },
            saveModifier = Modifier.tourTarget(LessonTarget.REMINDER_SAVE),
        ) {
            ReminderHint(stringResource(R.string.reminders_intro))
            LessonButton(lesson)
            ReminderPermissionNotice()
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            ReminderRuleFields(draft, display, enabled = !saving, revealAdvanced = revealAdvanced)
        }
    }
}

@Composable
private fun ReminderPermissionNotice() {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(NotificationManager::class.java) }
    fun appAllowed() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun channelAllowed() = manager?.getNotificationChannel(Notifications.CHANNEL_REMINDERS)
        ?.importance != NotificationManager.IMPORTANCE_NONE
    var appEnabled by remember { mutableStateOf(appAllowed()) }
    var channelEnabled by remember { mutableStateOf(channelAllowed()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        appEnabled = appAllowed()
        channelEnabled = channelAllowed()
    }
    if (!appEnabled || !channelEnabled) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(dimensionResource(R.dimen.reminder_card_padding)),
                verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.reminder_action_gap)),
            ) {
                Text(stringResource(R.string.reminders_permission_hint), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    val intent = if (appEnabled && !channelEnabled) {
                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.CHANNEL_REMINDERS)
                    } else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    context.startActivity(intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text(stringResource(R.string.reminders_permission_settings)) }
            }
        }
    }
}

/** Reused by an individual service/document editor when it overrides the default schedule. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReminderRuleFields(
    draft: ReminderRuleDraft,
    display: Display,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showDistance: Boolean = true,
    revealAdvanced: Boolean = false,
) {
    val sectionGap = dimensionResource(R.dimen.reminder_section_gap)
    val fieldGap = dimensionResource(R.dimen.reminder_field_gap)
    val actionGap = dimensionResource(R.dimen.reminder_action_gap)
    val minimumTouch = dimensionResource(R.dimen.reminder_min_touch_size)
    val active = enabled && draft.enabled
    val locale = LocalConfiguration.current.locales[0]
    val time = LocalTime.of(draft.minuteOfDay / 60, draft.minuteOfDay % 60)
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sectionGap)) {
        ReminderSwitch(
            label = stringResource(R.string.reminders_enabled),
            checked = draft.enabled,
            enabled = enabled,
            onCheckedChange = { draft.enabled = it },
        )
        if (!draft.enabled) ReminderHint(stringResource(R.string.reminders_disabled_hint))

        Column(Modifier.tourTarget(LessonTarget.REMINDER_TIME), verticalArrangement = Arrangement.spacedBy(actionGap)) {
            Text(stringResource(R.string.reminders_time), style = MaterialTheme.typography.titleSmall)
            OutlinedButton(
                onClick = {
                    draft.timeHours = (draft.minuteOfDay / 60).toString()
                    draft.timeMinutes = (draft.minuteOfDay % 60).toString().padStart(2, '0')
                    draft.timeDialogOpen = true
                },
                enabled = active,
                modifier = Modifier.fillMaxWidth().heightIn(min = minimumTouch),
            ) { Text(time, style = MaterialTheme.typography.titleLarge) }
            ReminderHint(stringResource(R.string.reminders_time_hint))
        }

        Column(Modifier.tourTarget(LessonTarget.REMINDER_DAYS), verticalArrangement = Arrangement.spacedBy(actionGap)) {
            Text(stringResource(R.string.reminders_weekdays), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(actionGap), verticalArrangement = Arrangement.spacedBy(actionGap)) {
                listOf(
                    R.string.reminders_every_day to (1..7).toSet(),
                    R.string.reminders_six_days to (1..6).toSet(),
                    R.string.reminders_workdays to (1..5).toSet(),
                ).forEach { (label, days) ->
                    FilterChip(
                        selected = draft.weekdays == days,
                        onClick = { draft.weekdays = days },
                        label = { Text(stringResource(label)) },
                        enabled = active,
                        modifier = Modifier.heightIn(min = minimumTouch),
                    )
                }
            }
            val shortDays = stringArrayResource(R.array.reminders_weekdays_short)
            val fullDays = stringArrayResource(R.array.reminders_weekdays_full)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(actionGap), verticalArrangement = Arrangement.spacedBy(actionGap)) {
                shortDays.forEachIndexed { index, day ->
                    val isoDay = index + 1
                    FilterChip(
                        selected = isoDay in draft.weekdays,
                        onClick = {
                            draft.weekdays = if (isoDay in draft.weekdays) draft.weekdays - isoDay else draft.weekdays + isoDay
                        },
                        label = { Text(day) },
                        enabled = active,
                        modifier = Modifier.heightIn(min = minimumTouch).widthIn(min = minimumTouch)
                            .semantics { contentDescription = fullDays[index] },
                    )
                }
            }
            if (draft.weekdays.isEmpty()) {
                Text(stringResource(R.string.reminders_weekdays_error), color = MaterialTheme.colorScheme.error)
            } else {
                Text(
                    pluralStringResource(R.plurals.reminders_days_selected, draft.weekdays.size, draft.weekdays.size),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            ReminderHint(stringResource(R.string.reminders_weekdays_hint))
        }

        if (!draft.advancedEnabled) ReminderHint(stringResource(R.string.reminders_basic_hint))
        HorizontalDivider()
        val advancedVisible = draft.advancedExpanded || revealAdvanced
        Column(Modifier.tourTarget(LessonTarget.REMINDER_ADVANCED), verticalArrangement = Arrangement.spacedBy(actionGap)) {
            TextButton(
                onClick = { draft.advancedExpanded = !draft.advancedExpanded },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = minimumTouch),
            ) {
                Text(stringResource(if (advancedVisible) R.string.reminders_advanced_hide else R.string.reminders_advanced_show))
            }
            if (advancedVisible) {
                ReminderSwitch(
                    label = stringResource(R.string.reminders_advanced_enabled),
                    checked = draft.advancedEnabled,
                    enabled = active,
                    onCheckedChange = { draft.advancedEnabled = it },
                )
                ReminderHint(stringResource(R.string.reminders_advanced_hint))
            }
        }
        if (advancedVisible && (draft.advancedEnabled || revealAdvanced)) {
            ReminderHint(stringResource(R.string.reminders_advance_hint))
            ReminderLevelFields(
                title = stringResource(R.string.reminders_yellow),
                distance = draft.yellowAdvance,
                onDistance = { draft.yellowAdvance = it },
                days = draft.yellowAdvanceDays,
                onDays = { draft.yellowAdvanceDays = it },
                repeat = draft.yellowRepeatDays,
                onRepeat = { draft.yellowRepeatDays = it },
                max = draft.yellowMaxOccurrences,
                onMax = { draft.yellowMaxOccurrences = it },
                maxDistance = draft.maxDisplayDistance,
                display = display,
                enabled = active && draft.advancedEnabled,
                showDistance = showDistance,
                thresholdTarget = LessonTarget.REMINDER_THRESHOLDS,
                repeatTarget = LessonTarget.REMINDER_REPEATS,
            )
            ReminderLevelFields(
                title = stringResource(R.string.reminders_red),
                distance = draft.redAdvance,
                onDistance = { draft.redAdvance = it },
                days = draft.redAdvanceDays,
                onDays = { draft.redAdvanceDays = it },
                repeat = draft.redRepeatDays,
                onRepeat = { draft.redRepeatDays = it },
                max = draft.redMaxOccurrences,
                onMax = { draft.redMaxOccurrences = it },
                maxDistance = draft.maxDisplayDistance,
                display = display,
                enabled = active && draft.advancedEnabled,
                showDistance = showDistance,
            )
            Column(verticalArrangement = Arrangement.spacedBy(fieldGap)) {
                ReminderHint(stringResource(R.string.reminders_repeat_hint))
                ReminderHint(stringResource(R.string.reminders_limit_hint))
            }
        }
    }

    if (draft.timeDialogOpen) {
        val hours = draft.timeHours.validNumber(0, 23)
        val minutes = draft.timeMinutes.validNumber(0, 59)
        AlertDialog(
            onDismissRequest = { draft.timeDialogOpen = false },
            title = { Text(stringResource(R.string.reminders_time_dialog)) },
            text = {
                // Wrapping fields and scrolling keep the dialog usable in landscape and large font sizes.
                FlowRow(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(actionGap),
                    verticalArrangement = Arrangement.spacedBy(fieldGap),
                ) {
                    ReminderNumberField(
                        value = draft.timeHours,
                        onValueChange = { draft.timeHours = it },
                        label = stringResource(R.string.reminders_hours),
                        max = 23,
                        display = display,
                        modifier = Modifier.weight(1f).widthIn(min = dimensionResource(R.dimen.reminder_time_field_min_width)),
                    )
                    ReminderNumberField(
                        value = draft.timeMinutes,
                        onValueChange = { draft.timeMinutes = it },
                        label = stringResource(R.string.reminders_minutes),
                        max = 59,
                        display = display,
                        modifier = Modifier.weight(1f).widthIn(min = dimensionResource(R.dimen.reminder_time_field_min_width)),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = hours != null && minutes != null,
                    onClick = {
                        if (hours != null && minutes != null) {
                            draft.minuteOfDay = hours * 60 + minutes
                            draft.timeDialogOpen = false
                        }
                    },
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { draft.timeDialogOpen = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun ReminderLevelFields(
    title: String,
    distance: String,
    onDistance: (String) -> Unit,
    days: String,
    onDays: (String) -> Unit,
    repeat: String,
    onRepeat: (String) -> Unit,
    max: String,
    onMax: (String) -> Unit,
    maxDistance: Int,
    display: Display,
    enabled: Boolean,
    showDistance: Boolean,
    thresholdTarget: String? = null,
    repeatTarget: String? = null,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(dimensionResource(R.dimen.reminder_card_padding)),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.reminder_field_gap)),
        ) {
            Column(
                modifier = thresholdTarget?.let { Modifier.tourTarget(it) } ?: Modifier,
                verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.reminder_field_gap)),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (showDistance) {
                    ReminderNumberField(distance, onDistance, stringResource(R.string.reminders_advance_distance, display.distanceUnit), maxDistance, display, enabled = enabled)
                }
                ReminderNumberField(days, onDays, stringResource(R.string.reminders_advance_days), ReminderRule.MAX_ADVANCE_DAYS, display, enabled = enabled)
            }
            Column(
                modifier = repeatTarget?.let { Modifier.tourTarget(it) } ?: Modifier,
                verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.reminder_field_gap)),
            ) {
                ReminderNumberField(repeat, onRepeat, stringResource(R.string.reminders_repeat_days), ReminderRule.MAX_REPEAT_DAYS, display, min = 1, enabled = enabled)
                ReminderNumberField(max, onMax, stringResource(R.string.reminders_max_occurrences), ReminderRule.MAX_OCCURRENCES, display, enabled = enabled)
            }
        }
    }
}

@Composable
private fun ReminderNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    max: Int,
    display: Display,
    modifier: Modifier = Modifier,
    min: Int = 0,
    enabled: Boolean = true,
) {
    val invalid = value.validNumber(min, max) == null
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.reminder_action_gap))) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    OutlinedTextField(
        value = value,
        onValueChange = { input -> if (input.all { it in '0'..'9' }) onValueChange(input) },
        singleLine = true,
        enabled = enabled,
        isError = invalid,
        supportingText = if (invalid) {
            { Text(stringResource(R.string.reminders_number_error, formatNumber(min, display.words), formatNumber(max, display.words))) }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        visualTransformation = GroupedDigitsTransformation(display.words.groupSeparator),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
    )
    }
}

@Composable
private fun ReminderSwitch(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = dimensionResource(R.dimen.reminder_min_touch_size))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.reminder_action_gap)),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

@Composable
private fun ReminderHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
