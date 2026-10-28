package io.github.snowdrit.mamokeep.notifications

import android.content.res.Resources
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.domain.Display
import io.github.snowdrit.mamokeep.domain.ItemKind
import io.github.snowdrit.mamokeep.domain.MaintenanceStatus
import io.github.snowdrit.mamokeep.domain.Urgency
import io.github.snowdrit.mamokeep.text.distanceText
import io.github.snowdrit.mamokeep.text.durationText
import io.github.snowdrit.mamokeep.text.sentence
import kotlin.math.abs

/**
 * Тексты напоминаний.
 *
 * Правило проекта: текст говорит о последствии для человека, а не о механизме внутри.
 * Никаких «интервал истёк» и «просрочено на 1200 км» — вместо этого «ты проехал
 * на 1200 км больше, чем стоило» (docs/DECISIONS.md, решение 13).
 *
 * Живёт в `notifications/`, а не в домене, поэтому строки берёт прямо из ресурсов:
 * уведомление читает человек, и оно обязано быть на языке приложения.
 */
object ReminderTexts {

    /**
     * @param carName имя машины, если машин больше одной. Иначе `null`: владельцу
     * единственной машины её имя в заголовке ничего не сообщает.
     *
     * Имя идёт **первым**: в свёрнутой шторке видно только начало заголовка, и без него
     * два напоминания про масло на двух машинах выглядят одинаково — человек не знает,
     * какой машине он нажимает «Сделано».
     */
    fun title(res: Resources, status: MaintenanceStatus, carName: String? = null): String {
        val document = status.item.kind == ItemKind.DOCUMENT
        val pattern = when (status.urgency) {
            Urgency.OVERDUE -> if (document) R.string.notif_title_expired else R.string.notif_title_due
            Urgency.SOON -> if (document) R.string.notif_title_expiring else R.string.notif_title_soon
            else -> return withCar(res, status.item.title, carName)
        }
        return withCar(res, res.getString(pattern, status.item.title), carName)
    }

    private fun withCar(res: Resources, title: String, carName: String?): String =
        if (carName.isNullOrBlank()) title else res.getString(R.string.notif_title_car, carName, title)

    /**
     * @param display остаток показываем в единицах человека: «проехал на 1200 км больше»
     * ничего не говорит тому, кто живёт в милях.
     */
    fun body(res: Resources, status: MaintenanceStatus, display: Display): String =
        when (status.urgency) {
            Urgency.OVERDUE -> overdueBody(res, status, display)
            Urgency.SOON -> soonBody(res, status, display)
            Urgency.OK -> okBody(res, status, display)
            Urgency.UNKNOWN -> res.getString(
                if (status.item.kind == ItemKind.DOCUMENT) {
                    R.string.notif_unknown_document
                } else {
                    R.string.notif_unknown_item
                }
            )
        }

    private fun overdueBody(res: Resources, status: MaintenanceStatus, display: Display): String {
        val km = status.kmLeft
        val days = status.daysLeft
        return when {
            km != null && km <= 0 ->
                res.getString(R.string.notif_over_distance, display.distanceText(abs(km)))
            days != null && days <= 0 && status.item.kind == ItemKind.DOCUMENT ->
                res.getString(R.string.notif_expired_ago, display.durationText(abs(days)))
            days != null && days <= 0 ->
                res.getString(R.string.notif_overdue_ago, display.durationText(abs(days)))
            else -> res.getString(R.string.notif_overdue_plain)
        }
    }

    private fun soonBody(res: Resources, status: MaintenanceStatus, display: Display): String {
        val parts = buildList {
            status.kmLeft?.let {
                add(res.getString(R.string.notif_in_distance, display.distanceText(it)))
            }
            status.daysLeft?.let {
                add(res.getString(R.string.notif_in_time, display.durationText(it)))
            }
        }
        if (parts.isEmpty()) return res.getString(R.string.notif_soon_plain)
        return sentence(
            res.getString(
                R.string.notif_soon,
                parts.joinToString(" ${res.getString(R.string.common_or)} "),
            )
        )
    }

    private fun okBody(res: Resources, status: MaintenanceStatus, display: Display): String {
        val parts = buildList {
            status.kmLeft?.let { add(display.distanceText(it)) }
            status.daysLeft?.let { add(display.durationText(it)) }
        }
        if (parts.isEmpty()) return res.getString(R.string.notif_no_term)

        val pattern = if (status.item.kind == ItemKind.DOCUMENT) {
            R.string.notif_valid_for
        } else {
            R.string.notif_left
        }
        return sentence(
            res.getString(pattern, parts.joinToString(" ${res.getString(R.string.common_or)} "))
        )
    }
}
