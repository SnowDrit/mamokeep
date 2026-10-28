package io.github.snowdrit.mamokeep.notifications

import android.Manifest
import android.app.NotificationManager
import android.app.LocaleManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.snowdrit.mamokeep.MainActivity
import io.github.snowdrit.mamokeep.MamoKeepApp
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.data.SettingsStore
import io.github.snowdrit.mamokeep.data.ReminderSettingsStore
import io.github.snowdrit.mamokeep.ui.display
import io.github.snowdrit.mamokeep.ui.currencySymbol
import io.github.snowdrit.mamokeep.domain.ReminderPolicy
import kotlinx.coroutines.flow.first
import io.github.snowdrit.mamokeep.BuildConfig
import java.time.ZonedDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Проверяет выбранные дни/время и лимиты повторов, затем показывает подходящие позиции.
 *
 * Считаем на устройстве, без сервера и без FCM — именно поэтому отсутствие Google
 * Mobile Services на Huawei нас не ломает (docs/DECISIONS.md, решение 12).
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val reminders = ReminderSettingsStore(applicationContext)
        return reminders.withGarageLock { checkReminders(reminders) }
    }

    private suspend fun checkReminders(reminders: ReminderSettingsStore): Result {
        val now = ZonedDateTime.now()
        val reminderSettings = reminders.settings.first()
        val rules = listOf(reminderSettings.defaultRule) + reminderSettings.itemOverrides.values
        // До выбранного времени и в выключенные дни даже не читаем гараж.
        if (rules.none { ReminderPolicy.isScheduledTime(it, now) }) return Result.success()

        val container = (applicationContext as MamoKeepApp).container
        val overviews = container.garageRepository.overviewsForAllCars(now.toLocalDate())
        // Имя машины несём вместе со статусом: в шторке напоминания от разных машин
        // лежат рядом, и различить их можно только текстом.
        val due = overviews.flatMap { overview ->
            overview.statuses
                .filter {
                    val rule = reminderSettings.ruleFor(it.item.id)
                    ReminderPolicy.isScheduledTime(rule, now) && ReminderPolicy.stage(it, rule) != null
                }
                .map { overview.carName to it }
        }
        val manyCars = overviews.size > 1
        Log.d(TAG, "машин=${overviews.size} требуют внимания=${due.size}")

        if (due.isEmpty()) return Result.success()

        Notifications.ensureChannel(applicationContext)
        if (!canPostNotifications()) {
            Log.d(TAG, "нет разрешения на уведомления")
            return Result.success()
        }

        val manager = applicationContext.getSystemService<NotificationManager>()
            ?: return Result.success()

        // Уведомление читает человек: и единицы, и язык берём те же, что на экране.
        val settings = SettingsStore(applicationContext)
        val res = notificationResources()
        val currencyCode = container.regionalCatalog.currencyCode(settings.regionalPreferences.first())
        val display = display(res, settings.unitSystem.first(), currencySymbol(res, currencyCode))

        var delivered = 0
        due.forEach { (carName, status) ->
            val itemId = status.item.id
            // Правило перечитывается внутри атомарной операции: экран мог сохранить
            // изменения, пока воркер рассчитывал пробег. Не расходуем повтор, если
            // другой запуск уже доставил это же напоминание.
            val claim = reminders.claimNotification(status, now) ?: return@forEach
            val body = ReminderTexts.body(res, status, display)
            val notification = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_car_wrench)
                .setContentTitle(ReminderTexts.title(res, status, carName.takeIf { manyCars }))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(openAppIntent(itemId))
                .setAutoCancel(true)
                .addAction(0, res.getString(R.string.notif_action_done), actionIntent(ReminderActionReceiver.ACTION_DONE, itemId))
                .addAction(0, res.getString(R.string.notif_action_later), actionIntent(ReminderActionReceiver.ACTION_DISMISS, itemId))
                .build()
            try {
                manager.notify(itemId.toInt(), notification)
                delivered++
                Log.d(TAG, "показано уведомление для позиции $itemId, этап=${claim.stage}")
            } catch (e: SecurityException) {
                reminders.releaseClaim(claim)
                Log.d(TAG, "разрешение на уведомления изменилось", e)
            }
        }

        if (delivered > 0) {
            WorkerLog.record(applicationContext, "машин=${overviews.size} напоминаний=$delivered")
            showPulse(res, overviews.size, delivered)
        }

        return Result.success()
    }

    /**
     * Пульс только при доставке: отладочный APK тоже уважает выбранные дни и время.
     */
    private fun showPulse(res: android.content.res.Resources, cars: Int, due: Int) {
        if (!BuildConfig.DEBUG) return
        if (!canPostNotifications()) return

        Notifications.ensurePulseChannel(applicationContext)
        val manager = applicationContext.getSystemService<NotificationManager>() ?: return
        val at = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

        manager.notify(
            Notifications.PULSE_ID,
            NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_PULSE)
                .setSmallIcon(R.drawable.ic_car_wrench)
                .setContentTitle(res.getString(R.string.notif_pulse_title, at))
                .setContentText(res.getString(R.string.notif_pulse_body, cars, due))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(res.getString(R.string.notif_pulse_body, cars, due))
                )
                .setOngoing(false)
                .build()
        )
    }

    private companion object {
        const val TAG = "ReminderWorker"
    }

    private fun actionIntent(action: String, itemId: Long): PendingIntent {
        val intent = Intent(applicationContext, ReminderActionReceiver::class.java)
            .setAction(action)
            .putExtra(ReminderActionReceiver.EXTRA_ITEM_ID, itemId)

        return PendingIntent.getBroadcast(
            applicationContext,
            // Свой requestCode на каждую пару «позиция + действие», иначе Android
            // переиспользует один PendingIntent и кнопки начнут делать одно и то же.
            (itemId.toInt() * 31) + action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(itemId: Long): PendingIntent = PendingIntent.getActivity(
        applicationContext,
        itemId.toInt(),
        Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun canPostNotifications(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(applicationContext).areNotificationsEnabled() &&
            applicationContext.getSystemService<NotificationManager>()
                ?.getNotificationChannel(Notifications.CHANNEL_REMINDERS)?.importance != NotificationManager.IMPORTANCE_NONE

    private fun notificationResources(): android.content.res.Resources {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return applicationContext.resources
        val locales = applicationContext.getSystemService(LocaleManager::class.java).applicationLocales
        if (locales.isEmpty) return applicationContext.resources
        val config = Configuration(applicationContext.resources.configuration).apply { setLocales(locales) }
        return applicationContext.createConfigurationContext(config).resources
    }
}
