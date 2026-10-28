package io.github.snowdrit.mamokeep.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.snowdrit.mamokeep.MamoKeepApp
import io.github.snowdrit.mamokeep.domain.export.ExportPreset
import io.github.snowdrit.mamokeep.domain.export.BackupCurrency
import io.github.snowdrit.mamokeep.data.SettingsStore
import io.github.snowdrit.mamokeep.domain.export.MarkdownExporter
import io.github.snowdrit.mamokeep.ui.display
import io.github.snowdrit.mamokeep.ui.currencySymbol
import io.github.snowdrit.mamokeep.ui.exportTexts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Ручной запуск ежесуточной проверки. Только для отладочных сборок.
 *
 * Периодическую задачу нельзя выполнить раньше её окна — `cmd jobscheduler run` WorkManager
 * игнорирует и пишет «executed before schedule». Поэтому для проверки напоминаний
 * ставим ту же работу разовой:
 *
 *     adb shell am broadcast -a io.github.snowdrit.mamokeep.debug.CHECK_NOW
 */
class DebugCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_BACKUP_NOW) {
            WorkManager.getInstance(context)
                .enqueue(OneTimeWorkRequestBuilder<BackupWorker>().build())
            return
        }

        if (intent.action == ACTION_BACKUP_ROUNDTRIP) {
            backupRoundtrip(context)
            return
        }

        if (intent.action == ACTION_RUN_LOG) {
            val lines = WorkerLog.read(context)
            Log.d("MamoKeepRuns", "запусков записано: ${lines.size}")
            lines.forEach { Log.d("MamoKeepRuns", it) }
            return
        }

        if (intent.action == ACTION_DUMP_EXPORT) {
            dumpExport(context, intent.getStringExtra("preset"))
            return
        }

        WorkManager.getInstance(context)
            .enqueue(OneTimeWorkRequestBuilder<ReminderWorker>().build())
    }

    /**
     * Печатает выгрузку в logcat — чтобы видеть её целиком, не вставляя буфер обмена
     * куда-то ещё:
     *
     *     adb shell am broadcast -n io.github.snowdrit.mamokeep/.notifications.DebugCheckReceiver \
     *       -a io.github.snowdrit.mamokeep.debug.DUMP_EXPORT --es preset FULL_DUMP
     */
    private fun dumpExport(context: Context, presetName: String?) {
        val app = context.applicationContext as? MamoKeepApp ?: return
        val preset = runCatching { ExportPreset.valueOf(presetName.orEmpty()) }
            .getOrDefault(ExportPreset.MAINTENANCE_ADVICE)

        CoroutineScope(Dispatchers.IO).launch {
            val settings = SettingsStore(context)
            // Печатаем выгрузку выбранной машины, а не первой попавшейся: иначе на двух
            // машинах отладочная команда врёт — показывает не то, что человек видит.
            val selected = settings.selectedCarId.first()
            val car = app.container.garageDao.allCars().firstOrNull { it.id == selected }
                ?: app.container.garageDao.firstCar()
                ?: return@launch
            val snapshot = app.container.garageRepository.snapshot(car.id, LocalDate.now()) ?: return@launch
            Log.d("MamoKeepExport", "выгрузка машины «${car.name}» (id=${car.id})")
            val currencyCode = app.container.regionalCatalog.currencyCode(settings.regionalPreferences.first())
            val currency = currencySymbol(context.resources, currencyCode)
            val display = display(context.resources, settings.unitSystem.first(), currency)

            MarkdownExporter.render(
                snapshot = snapshot,
                preset = preset,
                anonymize = false,
                display = display,
                texts = exportTexts(context.resources),
            )
                .lines()
                .forEach { Log.d("MamoKeepExport", it) }
        }
    }

    /**
     * Круг «сохранил — восстановил» на настоящей базе: выгружает копию, восстанавливает
     * её же и сверяет содержимое до и после. Проверять это через диалоги SAF вручную
     * долго, а терять историю обслуживания нельзя.
     */
    private fun backupRoundtrip(context: Context) {
        val app = context.applicationContext as? MamoKeepApp ?: return

        CoroutineScope(Dispatchers.IO).launch {
            val dao = app.container.garageDao
            val repo = app.container.garageRepository

            val before = dao.allCars().map { car ->
                listOf(
                    car.name,
                    dao.readings(car.id).size,
                    dao.items(car.id).size,
                    dao.fuelEntries(car.id).size,
                    dao.items(car.id).sumOf { dao.recordsOf(it.id).size },
                ).joinToString("/")
            }

            val backup = repo.exportBackup(LocalDate.now())
            Log.d(TAG, "копия: ${backup.length} символов")
            Log.d(TAG, "до:    $before")

            val currentCurrency = app.container.regionalCatalog.currencyCode(SettingsStore(context).regionalPreferences.first())
            val restored = repo.importBackup(backup, BackupCurrency(currentCurrency))

            val after = dao.allCars().map { car ->
                listOf(
                    car.name,
                    dao.readings(car.id).size,
                    dao.items(car.id).size,
                    dao.fuelEntries(car.id).size,
                    dao.items(car.id).sumOf { dao.recordsOf(it.id).size },
                ).joinToString("/")
            }

            Log.d(TAG, "после: $after")
            Log.d(TAG, "восстановлено машин: $restored")
            Log.d(TAG, if (before == after) "СОВПАЛО" else "РАСХОЖДЕНИЕ")
        }
    }

    companion object {
        const val TAG = "MamoKeepBackup"
        const val ACTION_DUMP_EXPORT = "io.github.snowdrit.mamokeep.debug.DUMP_EXPORT"

        /** Журнал запусков ежесуточной проверки: по нему видно пропущенные дни. */
        const val ACTION_RUN_LOG = "io.github.snowdrit.mamokeep.debug.RUN_LOG"
        const val ACTION_BACKUP_ROUNDTRIP = "io.github.snowdrit.mamokeep.debug.BACKUP_ROUNDTRIP"
        const val ACTION_BACKUP_NOW = "io.github.snowdrit.mamokeep.debug.BACKUP_NOW"
    }
}
