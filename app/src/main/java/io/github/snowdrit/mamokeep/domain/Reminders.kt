package io.github.snowdrit.mamokeep.domain

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Local wall-clock schedule; weekday numbers follow ISO (Monday = 1). */
@Serializable
data class ReminderRule(
    val enabled: Boolean = true,
    val minuteOfDay: Int = 9 * 60,
    val weekdays: Set<Int> = (1..7).toSet(),
    val advancedEnabled: Boolean = false,
    val yellowAdvanceKm: Int = 0,
    val redAdvanceKm: Int = 0,
    val yellowAdvanceDays: Int = 0,
    val redAdvanceDays: Int = 0,
    val yellowRepeatDays: Int = 1,
    val redRepeatDays: Int = 1,
    /** Zero means no limit; one means one notice at each stage per service cycle. */
    val yellowMaxOccurrences: Int = 0,
    val redMaxOccurrences: Int = 0,
) {
    val isValid: Boolean
        get() = minuteOfDay in 0 until 24 * 60 && weekdays.isNotEmpty() &&
            weekdays.all { it in 1..7 } &&
            yellowAdvanceKm in 0..MAX_ADVANCE_KM && redAdvanceKm in 0..MAX_ADVANCE_KM &&
            yellowAdvanceDays in 0..MAX_ADVANCE_DAYS && redAdvanceDays in 0..MAX_ADVANCE_DAYS &&
            yellowRepeatDays in 1..MAX_REPEAT_DAYS && redRepeatDays in 1..MAX_REPEAT_DAYS &&
            yellowMaxOccurrences in 0..MAX_OCCURRENCES && redMaxOccurrences in 0..MAX_OCCURRENCES

    companion object {
        const val MAX_ADVANCE_KM = 1_000_000
        const val MAX_ADVANCE_DAYS = 3_650
        const val MAX_REPEAT_DAYS = 3_650
        const val MAX_OCCURRENCES = 3_650
    }
}

@Serializable
data class ReminderSettings(
    val defaultRule: ReminderRule = ReminderRule(),
    val itemOverrides: Map<Long, ReminderRule> = emptyMap(),
) {
    fun ruleFor(itemId: Long): ReminderRule = itemOverrides[itemId] ?: defaultRule

    val isValid: Boolean
        get() = defaultRule.isValid && itemOverrides.all { (id, rule) -> id > 0 && rule.isValid }
}

/** A pre-warning cannot exhaust the limit for the warning at the actual threshold. */
@Serializable
enum class ReminderStage { BEFORE_YELLOW, YELLOW, BEFORE_RED, RED }

@Serializable
data class ReminderStageHistory(val dateEpochDay: Long, val occurrences: Int)

@Serializable
data class ReminderHistory(
    val cycle: String,
    val lastDateEpochDay: Long,
    val lastInstantMillis: Long,
    val stages: Map<ReminderStage, ReminderStageHistory>,
)

object ReminderPolicy {
    /** Both the distance and calendar axes can independently make a reminder due. */
    fun stage(status: MaintenanceStatus, rule: ReminderRule): ReminderStage? {
        if (!rule.enabled || !rule.isValid || status.urgency == Urgency.UNKNOWN) return null
        if (status.urgency == Urgency.OVERDUE) return ReminderStage.RED
        if (rule.advancedEnabled && (
                (rule.redAdvanceKm > 0 && status.kmLeft?.let { it <= rule.redAdvanceKm } == true) ||
                    (rule.redAdvanceDays > 0 && status.daysLeft?.let { it <= rule.redAdvanceDays } == true)
                )) return ReminderStage.BEFORE_RED
        if (status.urgency == Urgency.SOON) return ReminderStage.YELLOW
        if (rule.advancedEnabled && (
                (rule.yellowAdvanceKm > 0 && status.kmLeft?.let {
                    it.toLong() <= status.item.warnKm.toLong() + rule.yellowAdvanceKm
                } == true) ||
                    (rule.yellowAdvanceDays > 0 && status.daysLeft?.let {
                        it <= status.item.warnDays.toLong() + rule.yellowAdvanceDays
                    } == true)
                )) return ReminderStage.BEFORE_YELLOW
        return null
    }

    /**
     * WorkManager may run late. Never catch up on an unselected day, or before today's
     * chosen time, and never post a second notice for the same item on the same date.
     */
    fun isScheduledTime(rule: ReminderRule, now: ZonedDateTime): Boolean =
        rule.enabled && rule.isValid && now.dayOfWeek.value in rule.weekdays &&
            !now.toInstant().isBefore(slot(rule, now.toLocalDate(), now).toInstant())

    fun shouldNotify(
        status: MaintenanceStatus,
        rule: ReminderRule,
        history: ReminderHistory?,
        now: ZonedDateTime,
    ): Boolean {
        if (!isScheduledTime(rule, now)) return false
        val stage = stage(status, rule) ?: return false
        val current = history?.takeIf { it.cycle == cycle(status.item) } ?: return true
        val today = now.toLocalDate().toEpochDay()
        if (current.lastDateEpochDay == today || now.toInstant().toEpochMilli() <= current.lastInstantMillis) {
            return false
        }
        val previous = current.stages[stage] ?: return true
        val red = stage == ReminderStage.RED || stage == ReminderStage.BEFORE_RED
        val cap = if (!rule.advancedEnabled) 0 else if (red) rule.redMaxOccurrences else rule.yellowMaxOccurrences
        if (cap > 0 && previous.occurrences >= cap) return false
        val gap = if (!rule.advancedEnabled) 1 else if (red) rule.redRepeatDays else rule.yellowRepeatDays
        return ChronoUnit.DAYS.between(LocalDate.ofEpochDay(previous.dateEpochDay), now.toLocalDate()) >= gap
    }

    fun afterDelivery(
        status: MaintenanceStatus,
        stage: ReminderStage,
        history: ReminderHistory?,
        now: ZonedDateTime,
    ): ReminderHistory {
        val cycle = cycle(status.item)
        val previous = history?.takeIf { it.cycle == cycle }
        val stages = previous?.stages.orEmpty()
        val date = now.toLocalDate().toEpochDay()
        return ReminderHistory(
            cycle = cycle,
            lastDateEpochDay = date,
            lastInstantMillis = now.toInstant().toEpochMilli(),
            stages = stages + (stage to ReminderStageHistory(date, (stages[stage]?.occurrences ?: 0) + 1)),
        )
    }

    /** Next wall-clock slot. DST gaps shift forward; overlaps use the earlier offset. */
    fun nextScheduledTime(rule: ReminderRule, after: ZonedDateTime): ZonedDateTime? {
        if (!rule.enabled || !rule.isValid) return null
        for (days in 0L..7L) {
            val date = after.toLocalDate().plusDays(days)
            if (date.dayOfWeek.value !in rule.weekdays) continue
            val candidate = slot(rule, date, after)
            if (candidate.toInstant().isAfter(after.toInstant())) return candidate
        }
        return null
    }

    fun cycle(item: MaintenanceItem): String = "${item.lastDoneKm ?: ""}/${item.lastDoneDate ?: ""}"

    private fun slot(rule: ReminderRule, date: LocalDate, reference: ZonedDateTime): ZonedDateTime =
        date.atTime(LocalTime.of(rule.minuteOfDay / 60, rule.minuteOfDay % 60)).atZone(reference.zone)
}
