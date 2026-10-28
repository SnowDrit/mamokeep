package io.github.snowdrit.mamokeep

import android.app.Application
import android.os.Process
import io.github.snowdrit.mamokeep.notifications.Notifications
import io.github.snowdrit.mamokeep.data.SettingsStore
import io.github.snowdrit.mamokeep.notifications.BackupWorker
import io.github.snowdrit.mamokeep.notifications.ReminderScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MamoKeepApp : Application() {

    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // The PDF sandbox has no access to the app's private storage or scheduled work.
        if (Process.myUid() != applicationInfo.uid) return
        container = AppContainer(this)
        Notifications.ensureChannel(this)
        ReminderScheduler.schedule(this)

        scope.launch {
            val settings = SettingsStore(this@MamoKeepApp)
            // Страна телефона подсказывает единицы и регион ровно один раз: дальше это
            // выбор человека, и смена языка его не трогает.
            settings.rememberDefaults()

            // Регион и единицы берутся из настроек, а пока человек их не трогал — из
            // страны телефона: наполнение базы происходит раньше, чем он дойдёт до «Ещё».
            val regionCode = settings.region.first()
            val region = container.regionPresets.firstOrNull { it.code == regionCode }
            // Документам, заведённым сборками без ключей, ключ проставляется задним
            // числом — иначе их названия навсегда застыли бы на том языке, на котором
            // машину однажды завели.
            container.garageRepository.linkRegionDocuments(container.regionPresets)
            val units = settings.unitSystem.first()
            settings.initializeGarageIfNeeded {
                container.garageRepository.seedIfEmpty(
                    region = region,
                    units = units,
                    carName = getString(R.string.garage_default_name),
                )
            }

            if (settings.backupFolder.first() != null) {
                BackupWorker.schedule(this@MamoKeepApp)
            }
        }
    }
}
