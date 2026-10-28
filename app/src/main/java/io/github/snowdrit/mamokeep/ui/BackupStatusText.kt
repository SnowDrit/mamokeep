package io.github.snowdrit.mamokeep.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.data.BackupStatus
import io.github.snowdrit.mamokeep.data.BackupStatusStore
import java.text.DateFormat
import java.util.Date

/** Place inside the existing weekly-copy section, below the schedule setting. */
@Composable
fun BackupStatusText(folder: String?, modifier: Modifier = Modifier) {
    if (folder == null) return
    val context = LocalContext.current.applicationContext
    val store = remember(context) { BackupStatusStore(context) }
    val locale = LocalConfiguration.current.locales[0]
    key(folder) {
        val status by remember(store, folder) { store.observe(folder) }
            .collectAsStateWithLifecycle<BackupStatus?>(null)
        val loaded = status
        Column(modifier) {
            when {
                loaded == null -> Text(
                    stringResource(R.string.backup_status_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                loaded.lastAttemptFailed -> Text(
                    stringResource(R.string.backup_status_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                loaded.lastSuccessAt == null -> Text(
                    stringResource(R.string.backup_status_waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            loaded?.lastSuccessAt?.let { timestamp ->
                val date = remember(timestamp, locale) {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(timestamp))
                }
                Text(
                    stringResource(R.string.backup_status_last_success, date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
