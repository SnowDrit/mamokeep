package io.github.snowdrit.mamokeep.notifications

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.snowdrit.mamokeep.MamoKeepApp
import io.github.snowdrit.mamokeep.data.BackupStatusStore
import io.github.snowdrit.mamokeep.data.SettingsStore
import io.github.snowdrit.mamokeep.data.writeDocumentText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Еженедельная копия в выбранную пользователем папку.
 *
 * Если папка синхронизируется с облаком, копия оказывается вне телефона — то есть
 * защищает ровно от того случая, ради которого она нужна. Своего сервера у нас нет
 * и не будет (docs/DECISIONS.md, решение 7).
 */
class BackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MamoKeepApp
        val folder = SettingsStore(applicationContext).backupFolder.first() ?: return Result.success()
        return try {
            BackupStatusStore(applicationContext).trackAttempt(folder) {
                val today = LocalDate.now()
                val content = app.container.garageRepository.exportBackup(today)
                withContext(Dispatchers.IO) {
                    val tree = DocumentFile.fromTreeUri(applicationContext, Uri.parse(folder))
                        ?: throw IOException("Backup folder is unavailable")
                    writeDailyBackup(tree, "mamokeep-$today.json") { uri ->
                        applicationContext.contentResolver.writeDocumentText(uri, content)
                    }
                }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "weekly-backup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

/** SAF has no atomic replace: keep the previous copy until the new document is fully written. */
internal suspend fun writeDailyBackup(tree: DocumentFile, name: String, write: suspend (Uri) -> Unit) {
    if (!tree.canWrite()) throw IOException("Backup folder is not writable")
    val previous = tree.findFile(name)
    val staged = tree.createFile("application/json", "$name.${UUID.randomUUID()}.pending.json")
        ?: throw IOException("Could not create backup document")
    try {
        write(staged.uri)
    } catch (error: Exception) {
        try {
            if (!staged.delete()) error.addSuppressed(IOException("Could not remove incomplete backup"))
        } catch (cleanup: Exception) {
            error.addSuppressed(cleanup)
        }
        throw error
    }
    // Once writing succeeded, retain the complete new document even if finalization fails.
    if (previous != null && !previous.delete()) throw IOException("Could not remove previous daily backup")
    if (!staged.renameTo(name)) throw IOException("Could not finalize daily backup name")
}
