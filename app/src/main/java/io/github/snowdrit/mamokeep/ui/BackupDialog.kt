package io.github.snowdrit.mamokeep.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.domain.RegionalCatalog
import io.github.snowdrit.mamokeep.domain.RegionalPreferences
import io.github.snowdrit.mamokeep.domain.export.BackupCurrency
import kotlinx.coroutines.CancellationException

/**
 * Резервная копия.
 *
 * Сервера нет и не будет, поэтому копия — единственная защита от потери телефона.
 * Ценность приложения накапливается годами, и терять её нельзя (docs/DECISIONS.md, решение 7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupDialog(
    onDismiss: () -> Unit,
    buildBackup: suspend () -> String,
    restoreBackup: suspend (String, BackupCurrency) -> Int?,
    regionalPreferences: RegionalPreferences,
    regionalCatalog: RegionalCatalog,
    onCurrency: (String?) -> Unit,
) {
    val context = LocalContext.current
    val state: BackupDialogViewModel = viewModel()
    val currentCurrency = BackupCurrency(regionalCatalog.currencyCode(regionalPreferences))
    val preview = state.restorePreview
    val dismiss = {
        if (!state.busy) {
            state.reset()
            onDismiss()
        }
    }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> state.saveFile(uri, context.applicationContext.contentResolver) }
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> state.restoreFile(uri, context.applicationContext.contentResolver) }

    LaunchedEffect(currentCurrency) { state.confirmUnknownCurrency(null) }

    LaunchedEffect(state.savePickerRequested) {
        if (state.savePickerRequested) {
            state.pickerLaunched()
            try {
                saveLauncher.launch("mamokeep-backup.json")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.selectionFailed(saving = true)
            }
        }
    }

    BasicAlertDialog(onDismissRequest = dismiss) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            contentColor = AlertDialogDefaults.textContentColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                        .padding(dimensionResource(R.dimen.more_page_padding)),
                    verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.more_control_gap)),
                ) {
                    Text(
                        stringResource(R.string.backup_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = AlertDialogDefaults.titleContentColor,
                    )
                    if (preview == null) {
                        Text(stringResource(R.string.backup_explain), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.backup_cloud_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider()
                    } else {
                        Text(stringResource(R.string.backup_preview_cars, preview.carCount))
                    }

                    Text(
                        stringResource(R.string.backup_replaces),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    if (preview != null) {
                        val sourceCode = preview.currency?.code
                        Text(if (sourceCode == null) stringResource(R.string.backup_currency_unknown)
                            else stringResource(R.string.backup_currency_known, sourceCode))
                        if (!state.busy) {
                            CurrencyPicker(regionalPreferences, regionalCatalog, onCurrency)
                        }
                        if (sourceCode == null) {
                            val checked = state.confirmedUnknownCurrency == currentCurrency
                            Row(
                                modifier = Modifier.fillMaxWidth().toggleable(
                                    value = checked,
                                    enabled = !state.busy,
                                    role = Role.Checkbox,
                                    onValueChange = { state.confirmUnknownCurrency(if (it) currentCurrency else null) },
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null, enabled = !state.busy)
                                Text(stringResource(R.string.backup_currency_confirm,
                                    currentCurrency.code ?: stringResource(R.string.currency_unset_symbol)))
                            }
                        }
                    }

                    if (state.busy) CircularProgressIndicator()
                    val result = state.restoredCars?.let { stringResource(R.string.backup_restored, it) }
                        ?: state.status?.let { stringResource(it) }
                    result?.let {
                        HorizontalDivider()
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(
                            dimensionResource(R.dimen.more_detail_gap), Alignment.End,
                        ),
                        verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.more_detail_gap)),
                    ) {
                        if (preview != null) {
                            TextButton(enabled = !state.busy, onClick = state::cancelRestore) {
                                Text(stringResource(R.string.common_cancel))
                            }
                            TextButton(enabled = !state.busy && state.canRestore(currentCurrency),
                                onClick = { state.confirmRestore(currentCurrency, restoreBackup) }) {
                                Text(stringResource(R.string.backup_restore))
                            }
                        } else {
                            TextButton(enabled = !state.busy, onClick = {
                                if (state.beginRestoreSelection()) {
                                    try {
                                        openLauncher.launch(arrayOf("application/json", "*/*"))
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        state.selectionFailed(saving = false)
                                    }
                                }
                            }) { Text(stringResource(R.string.backup_restore)) }
                            TextButton(enabled = !state.busy, onClick = dismiss) {
                                Text(stringResource(R.string.common_close))
                            }
                            TextButton(enabled = !state.busy, onClick = { state.prepare(buildBackup) }) {
                                Text(stringResource(R.string.backup_save))
                            }
                        }
                    }
                }
            }
        }
    }
}
