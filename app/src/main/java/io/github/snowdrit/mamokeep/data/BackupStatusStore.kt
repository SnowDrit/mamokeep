package io.github.snowdrit.mamokeep.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.backupStatusDataStore: DataStore<Preferences> by preferencesDataStore("backup_status")

data class BackupStatus(val lastSuccessAt: Long? = null, val lastAttemptFailed: Boolean = false)

/** A result belongs to the folder that was actually written, not whichever folder is selected now. */
class BackupStatusStore internal constructor(
    private val preferences: DataStore<Preferences>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(context.backupStatusDataStore)

    private val folderKey = stringPreferencesKey("folder")
    private val successKey = longPreferencesKey("last_success_at")
    private val failedKey = booleanPreferencesKey("last_attempt_failed")

    fun observe(folder: String): Flow<BackupStatus> = preferences.data.map { prefs ->
        if (prefs[folderKey] == folder) {
            BackupStatus(prefs[successKey], prefs[failedKey] == true)
        } else {
            BackupStatus()
        }
    }.distinctUntilChanged()

    /** The caller's write includes finalization; an exception must never create a success timestamp. */
    suspend fun trackAttempt(folder: String, createBackup: suspend () -> Unit) {
        try {
            createBackup()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            preferences.edit { prefs ->
                if (prefs[folderKey] != folder) prefs.remove(successKey)
                prefs[folderKey] = folder
                prefs[failedKey] = true
            }
            throw error
        }
        preferences.edit { prefs ->
            prefs[folderKey] = folder
            prefs[successKey] = now()
            prefs[failedKey] = false
        }
    }
}
