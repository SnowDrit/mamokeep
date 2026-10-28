package io.github.snowdrit.mamokeep.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import io.github.snowdrit.mamokeep.MamoKeepApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Кнопки в уведомлении.
 *
 * «Сделано» пишет запись в историю с расчётным пробегом на сегодня: человек, который
 * только что поменял масло, не должен ради отметки открывать приложение и сверять одометр.
 *
 * «Не сейчас» убирает уведомление. Следующее приходит по выбранному расписанию и
 * ограничению повторов, причём не раньше следующего выбранного дня.
 */
class ReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val itemId = intent.getLongExtra(EXTRA_ITEM_ID, -1L)
        if (itemId <= 0L) return

        NotificationManagerCompat.from(context).cancel(itemId.toInt())

        if (intent.action != ACTION_DONE) return

        val app = context.applicationContext as? MamoKeepApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.container.garageRepository.markDoneAtCurrentMileage(itemId, LocalDate.now())
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DONE = "io.github.snowdrit.mamokeep.action.DONE"
        const val ACTION_DISMISS = "io.github.snowdrit.mamokeep.action.DISMISS"
        const val EXTRA_ITEM_ID = "itemId"
    }
}
