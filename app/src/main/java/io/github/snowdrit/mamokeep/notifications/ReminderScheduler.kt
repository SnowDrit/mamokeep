package io.github.snowdrit.mamokeep.notifications

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Проверка расписания раз в четверть часа; время и дни решает ReminderPolicy.
 *
 * Точные будильники сознательно не используем: `SCHEDULE_EXACT_ALARM` под ограничениями
 * Android 12+/14+, а напоминанию про масло безразлично, придёт оно в 9:00 или в 9:40
 * (docs/DECISIONS.md, решение 12).
 */
object ReminderScheduler {

    private const val WORK_NAME = "scheduled-maintenance-check-v2"
    private const val LEGACY_WORK_NAME = "daily-maintenance-check"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES)
            .build()

        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(LEGACY_WORK_NAME)
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
