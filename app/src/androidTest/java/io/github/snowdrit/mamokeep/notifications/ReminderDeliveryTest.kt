package io.github.snowdrit.mamokeep.notifications

import android.Manifest
import android.app.LocaleManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.snowdrit.mamokeep.MamoKeepApp
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.data.SettingsStore
import io.github.snowdrit.mamokeep.data.db.CarEntity
import io.github.snowdrit.mamokeep.domain.ItemKind
import io.github.snowdrit.mamokeep.domain.MaintenanceItem
import io.github.snowdrit.mamokeep.domain.ReminderRule
import io.github.snowdrit.mamokeep.domain.ReminderSettings
import io.github.snowdrit.mamokeep.domain.Urgency
import io.github.snowdrit.mamokeep.text.distanceText
import io.github.snowdrit.mamokeep.text.sentence
import io.github.snowdrit.mamokeep.ui.display
import io.github.snowdrit.mamokeep.ui.currencySymbol
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Real Room → preferences → WorkManager → Android notification test.
 * Run only with ANDROID_SERIAL=emulator-5554; never against an owner's phone.
 */
@RunWith(AndroidJUnit4::class)
class ReminderDeliveryTest {
    @Test
    fun actualWorkerHonorsWeekdayDeduplicationAndTruthfulEarlyWarning() = runBlocking {
        assumeTrue(
            "This test temporarily changes real app reminder settings and is emulator-only",
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
                Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator"),
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as MamoKeepApp
        val repository = app.container.garageRepository
        val reminders = app.container.reminderSettings
        val manager = context.getSystemService(NotificationManager::class.java)
        val before = reminders.exportSettings()
        val today = LocalDate.now()
        val todayOnly = ReminderRule(minuteOfDay = 0, weekdays = setOf(today.dayOfWeek.value))
        val excludedDay = today.dayOfWeek.value % 7 + 1
        var carId: Long? = null
        val itemIds = mutableListOf<Long>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        Notifications.ensureChannel(context)
        assertTrue("App notification permission must be granted", manager.areNotificationsEnabled())
        assertFalse(
            "Reminder channel must be enabled for delivery verification",
            manager.getNotificationChannel(Notifications.CHANNEL_REMINDERS).importance == NotificationManager.IMPORTANCE_NONE,
        )

        try {
            reminders.withGarageLock {
                reminders.replaceSettings(ReminderSettings(defaultRule = ReminderRule(enabled = false)))
                carId = app.container.garageDao.insertCar(CarEntity(name = "Reminder delivery test"))
            }
            val testCar = requireNotNull(carId)
            val dueId = repository.saveItemWithReminder(
                testCar,
                MaintenanceItem(
                    id = 0, kind = ItemKind.MAINTENANCE, title = "Reminder due test",
                    intervalKm = null, intervalMonths = 1, lastDoneKm = null,
                    lastDoneDate = today.minusMonths(2), warnKm = 500, warnDays = 14,
                ),
                todayOnly,
            )
            itemIds += dueId
            manager.cancel(dueId.toInt())

            runWorker(context)
            val first = awaitNotification(manager, dueId)
            assertEquals(Notifications.CHANNEL_REMINDERS, first.notification.channelId)
            assertTrue(first.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("Reminder due test"))
            val firstPostTime = first.postTime

            runWorker(context)
            // NotificationManager hands posts to the system asynchronously; give an
            // accidental second post time to become observable before comparing it.
            delay(250)
            val second = manager.activeNotifications.singleOrNull { it.id == dueId.toInt() }
            assertNotNull("Second check must leave the first notification in place", second)
            assertEquals("Second check must not repost the same item", firstPostTime, second!!.postTime)

            reminders.withGarageLock {
                reminders.setItemRule(dueId, todayOnly.copy(weekdays = setOf(excludedDay)))
                // Clearing history means a false positive would really post; off-day
                // verification must not accidentally pass because deduplication hid it.
                reminders.clearHistory(dueId)
                manager.cancel(dueId.toInt())
            }
            awaitCancellation(manager, dueId)
            runWorker(context)
            delay(250)
            assertNull("An excluded weekday must never post", manager.activeNotifications.singleOrNull { it.id == dueId.toInt() })

            repository.addOdometerReading(testCar, 30_000, today)
            val earlyId = repository.saveItemWithReminder(
                testCar,
                MaintenanceItem(
                    id = 0, kind = ItemKind.MAINTENANCE, title = "Reminder early test",
                    intervalKm = 10_000, intervalMonths = null, lastDoneKm = 21_500,
                    lastDoneDate = today, warnKm = 500, warnDays = 14,
                ),
                todayOnly.copy(advancedEnabled = true, yellowAdvanceKm = 1_000),
            )
            itemIds += earlyId
            val earlyStatus = repository.overviewsForAllCars(today)
                .single { it.carId == testCar }.statuses.single { it.item.id == earlyId }
            assertEquals(Urgency.OK, earlyStatus.urgency)
            assertEquals(1_500, earlyStatus.kmLeft)

            runWorker(context)
            val earlyNotification = awaitNotification(manager, earlyId).notification
            val settings = SettingsStore(context)
            val res = localizedResources(context)
            val currencyCode = app.container.regionalCatalog.currencyCode(settings.regionalPreferences.first())
            val display = display(res, settings.unitSystem.first(), currencySymbol(res, currencyCode))
            val expectedBody = sentence(res.getString(R.string.notif_left, display.distanceText(1_500)))
            assertEquals(
                "Early warning must state the real remaining distance, not claim it is overdue",
                expectedBody,
                earlyNotification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
            )
            val actualTitle = earlyNotification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
            assertFalse(actualTitle.contains(res.getString(R.string.notif_title_due, "Reminder early test")))
        } finally {
            itemIds.forEach { manager.cancel(it.toInt()) }
            manager.cancel(Notifications.PULSE_ID)
            carId?.let { repository.deleteCar(it) }
            reminders.withGarageLock { reminders.replaceSettings(before) }
        }
    }

    private suspend fun runWorker(context: Context) = withTimeout(20_000) {
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>().build()
        workManager.enqueue(request).result.get(5, TimeUnit.SECONDS)
        while (true) {
            val info = workManager.getWorkInfoById(request.id).get(2, TimeUnit.SECONDS)
            if (info != null && info.state.isFinished) {
                assertEquals("Reminder worker must finish successfully", WorkInfo.State.SUCCEEDED, info.state)
                break
            }
            delay(100)
        }
    }

    private suspend fun awaitNotification(manager: NotificationManager, itemId: Long): StatusBarNotification =
        withTimeout(5_000) {
            var found = manager.activeNotifications.singleOrNull { it.id == itemId.toInt() }
            while (found == null) {
                delay(50)
                found = manager.activeNotifications.singleOrNull { it.id == itemId.toInt() }
            }
            found
        }

    private suspend fun awaitCancellation(manager: NotificationManager, itemId: Long) = withTimeout(5_000) {
        while (manager.activeNotifications.any { it.id == itemId.toInt() }) delay(50)
    }

    private fun localizedResources(context: Context): Resources {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return context.resources
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        if (locales.isEmpty) return context.resources
        val config = Configuration(context.resources.configuration).apply { setLocales(locales) }
        return context.createConfigurationContext(config).resources
    }
}
