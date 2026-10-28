package io.github.snowdrit.mamokeep.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Позиция обслуживания.
 *
 * Интервал всегда двумерный: пробег ИЛИ время, что наступит раньше. Тормозная жидкость
 * и антифриз меняются по годам независимо от километров, и приложение, умеющее только
 * километры, будет молча врать (docs/DECISIONS.md, решение 5).
 */
/**
 * Обслуживание или документ.
 *
 * Это одна и та же сущность: документ — напоминание с выключенной километровой осью
 * (docs/DECISIONS.md, решение 9). Разделяем только подачу — тексты и группировку
 * на экране, потому что «продлить ОСАГО» и «поменять масло» звучат по-разному.
 */
enum class ItemKind { MAINTENANCE, DOCUMENT }

data class MaintenanceItem(
    val id: Long,
    val kind: ItemKind,
    val title: String,
    /**
     * Ключ названия у позиции, заведённой приложением. Пусто — человек назвал её сам,
     * и переводить его текст нельзя (см. [ItemTitles]).
     */
    val titleKey: String? = null,
    /**
     * Ключ значка. Именно ключ, а не ресурс: домен не знает про Android, а значки
     * становятся данными — пресеты и сообщество смогут их назначать.
     */
    val iconKey: String? = null,
    val intervalKm: Int?,
    /** Срок в месяцах: автомобильные регламенты задаются так, а не в днях. */
    val intervalMonths: Int?,
    val lastDoneKm: Int?,
    val lastDoneDate: LocalDate?,
    /** За сколько километров до срока предупреждать. */
    val warnKm: Int,
    /** За сколько дней до срока предупреждать. */
    val warnDays: Int,
)

enum class Urgency {
    /** Срок ещё не близко. */
    OK,

    /** Порог предупреждения пройден. */
    SOON,

    /** Срок прошёл. */
    OVERDUE,

    /** Не от чего считать: позиция ни разу не выполнялась или интервал не задан. */
    UNKNOWN,
}

data class MaintenanceStatus(
    val item: MaintenanceItem,
    val kmLeft: Int?,
    val daysLeft: Long?,
    val urgency: Urgency,
    /**
     * Доля израсходованного ресурса: 0.0 — только что сделано, 1.0 — ровно срок,
     * больше единицы — перепробег. `null`, если считать не от чего.
     *
     * Нужна, чтобы цвет карточки был шкалой, а не четырьмя дискретными состояниями:
     * «осталось 9000 из 10000» и «осталось 500 из 10000» должны выглядеть по-разному.
     */
    val wear: Double?,
)

object MaintenanceCalculator {

    fun status(
        item: MaintenanceItem,
        currentKm: Int?,
        today: LocalDate,
    ): MaintenanceStatus {
        val kmLeft = kmLeft(item, currentKm)
        val daysLeft = daysLeft(item, today)

        val urgency = when {
            kmLeft == null && daysLeft == null -> Urgency.UNKNOWN
            (kmLeft != null && kmLeft <= 0) || (daysLeft != null && daysLeft <= 0) -> Urgency.OVERDUE
            (kmLeft != null && kmLeft <= item.warnKm) ||
                (daysLeft != null && daysLeft <= item.warnDays) -> Urgency.SOON
            else -> Urgency.OK
        }

        return MaintenanceStatus(item, kmLeft, daysLeft, urgency, wear(item, currentKm, today))
    }

    /**
     * Берём ту ось, по которой ресурс израсходован сильнее: срок наступает по той,
     * что раньше, значит и показывать надо её.
     */
    private fun wear(item: MaintenanceItem, currentKm: Int?, today: LocalDate): Double? {
        val byKm = run {
            val interval = item.intervalKm ?: return@run null
            val lastDone = item.lastDoneKm ?: return@run null
            val current = currentKm ?: return@run null
            if (interval <= 0) null else (current - lastDone).toDouble() / interval
        }

        val byTime = run {
            val interval = item.intervalMonths ?: return@run null
            val lastDone = item.lastDoneDate ?: return@run null
            val due = lastDone.plusMonths(interval.toLong())
            val whole = ChronoUnit.DAYS.between(lastDone, due)
            val passed = ChronoUnit.DAYS.between(lastDone, today)
            if (whole <= 0) null else passed.toDouble() / whole
        }

        return listOfNotNull(byKm, byTime).maxOrNull()?.coerceAtLeast(0.0)
    }

    private fun kmLeft(item: MaintenanceItem, currentKm: Int?): Int? {
        val interval = item.intervalKm ?: return null
        val lastDone = item.lastDoneKm ?: return null
        val current = currentKm ?: return null
        return lastDone + interval - current
    }

    private fun daysLeft(item: MaintenanceItem, today: LocalDate): Long? {
        val interval = item.intervalMonths ?: return null
        val lastDone = item.lastDoneDate ?: return null
        return ChronoUnit.DAYS.between(today, lastDone.plusMonths(interval.toLong()))
    }
}

/** Факт выполнения работы. */
data class MaintenanceRecord(
    val id: Long,
    val date: LocalDate,
    val km: Int?,
)

/**
 * Порядок на главном экране: сначала то, что горит, в конце — то, о чём можно не думать.
 * `UNKNOWN` идёт выше `OK`, потому что это не «всё хорошо», а «мне нечем считать» —
 * от пользователя требуется действие.
 */
private val Urgency.sortRank: Int
    get() = when (this) {
        Urgency.OVERDUE -> 0
        Urgency.SOON -> 1
        Urgency.UNKNOWN -> 2
        Urgency.OK -> 3
    }

fun List<MaintenanceStatus>.sortedByUrgency(): List<MaintenanceStatus> = sortedWith(
    compareBy<MaintenanceStatus> { it.urgency.sortRank }
        .thenBy { it.kmLeft ?: Int.MAX_VALUE }
        .thenBy { it.daysLeft ?: Long.MAX_VALUE }
)
