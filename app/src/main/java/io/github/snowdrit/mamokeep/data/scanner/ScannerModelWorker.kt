package io.github.snowdrit.mamokeep.data.scanner

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.snowdrit.mamokeep.MamoKeepApp
import io.github.snowdrit.mamokeep.data.scanner.ScannerModelManager.Companion.BYTES_KEY
import io.github.snowdrit.mamokeep.data.scanner.ScannerModelManager.Companion.ERROR_KEY
import io.github.snowdrit.mamokeep.data.scanner.ScannerModelManager.Companion.MANIFEST_KEY
import io.github.snowdrit.mamokeep.data.scanner.ScannerModelManager.Companion.PHASE_KEY
import kotlinx.coroutines.CancellationException

class ScannerModelWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = (applicationContext as MamoKeepApp).container.scannerModels
        val digest = inputData.getString(MANIFEST_KEY) ?: return failure(ScannerModelError.SOURCE)
        return try {
            manager.installPending(digest) { phase, bytes ->
                setProgress(workDataOf(PHASE_KEY to phase.name, BYTES_KEY to bytes))
            }
            Result.success()
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { failure(ScannerModelManager.errorOf(e)) }
    }

    private fun failure(error: ScannerModelError) = Result.failure(workDataOf(ERROR_KEY to error.name))
}
