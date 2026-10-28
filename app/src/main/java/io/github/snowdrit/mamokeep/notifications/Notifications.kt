package io.github.snowdrit.mamokeep.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.github.snowdrit.mamokeep.R
import androidx.core.content.getSystemService

object Notifications {

    const val CHANNEL_REMINDERS = "reminders"

    /** Отдельный канал: пульс не должен звенеть и не должен смешиваться с напоминаниями. */
    const val CHANNEL_PULSE = "worker-pulse"

    /** Номер пульса не пересекается с позициями: у них номер — это `id`, а он маленький. */
    const val PULSE_ID = Int.MAX_VALUE - 1

    /**
     * Гасит уведомления перечисленных позиций. Номер уведомления — это `id` позиции
     * (`ReminderWorker`), поэтому удалённой машине хватает списка её позиций.
     *
     * Нужно при удалении машины: висящее в шторке «Motocikl · пора менять масло» после
     * того, как мотоцикл удалён, — это напоминание о машине, которой уже нет.
     */
    fun cancelItems(context: Context, itemIds: List<Long>) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        itemIds.forEach { manager.cancel(it.toInt()) }
    }

    fun ensurePulseChannel(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_PULSE) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PULSE,
                context.getString(R.string.notif_pulse_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_pulse_channel_sub)
            }
        )
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_REMINDERS) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                context.getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notif_channel_sub)
            }
        )
    }
}
