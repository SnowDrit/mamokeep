package io.github.snowdrit.mamokeep.ui.scanner

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.data.scanner.ScannerModelError
import io.github.snowdrit.mamokeep.data.scanner.ScannerModelPhase
import io.github.snowdrit.mamokeep.data.scanner.ScannerModelState
import io.github.snowdrit.mamokeep.data.scanner.ScannerVersionOption
import io.github.snowdrit.mamokeep.data.scanner.CatalogError
import io.github.snowdrit.mamokeep.data.scanner.CatalogPhase
import io.github.snowdrit.mamokeep.text.dateText
import io.github.snowdrit.mamokeep.text.formatFileSize
import java.time.Instant
import java.time.ZoneId

private const val PREFERENCES = "scanner_download_ui"
private const val WIFI_ONLY = "wifi_only"

/** Управляет только пакетом: наличие весов пока не означает готовность распознавания. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScannerModelCard(
    state: ScannerModelState,
    onDownload: (wifiOnly: Boolean) -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onRefresh: () -> Unit,
    onSelectVersion: (String) -> Unit,
    onCheckCatalog: (Boolean) -> Unit,
    onRollback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    val defaultWifiOnly = booleanResource(R.bool.scanner_wifi_only_default)
    var wifiOnly by rememberSaveable { mutableStateOf(preferences.getBoolean(WIFI_ONLY, defaultWifiOnly)) }
    var confirmingDownload by rememberSaveable { mutableStateOf(false) }
    var confirmingRemoval by rememberSaveable { mutableStateOf(false) }
    var confirmingRollback by rememberSaveable { mutableStateOf(false) }
    var versionsOpen by rememberSaveable { mutableStateOf(false) }
    val gap = dimensionResource(R.dimen.scanner_section_gap)
    val selectedVersion = state.versions.firstOrNull { it.selected }
    val busy = state.phase in setOf(
        ScannerModelPhase.CHECKING, ScannerModelPhase.QUEUED, ScannerModelPhase.DOWNLOADING,
        ScannerModelPhase.VERIFYING, ScannerModelPhase.CANCELLING, ScannerModelPhase.REMOVING,
    )
    val canDownload = (state.isApkLocal || state.sourceRepository.isNotBlank()) &&
        selectedVersion?.compatible != false &&
        state.phase in setOf(ScannerModelPhase.NOT_INSTALLED, ScannerModelPhase.FAILED)
    val canCancel = state.phase in setOf(ScannerModelPhase.QUEUED, ScannerModelPhase.DOWNLOADING, ScannerModelPhase.VERIFYING)
    val canRemove = state.hasStoredFiles && state.phase in setOf(
        ScannerModelPhase.INSTALLED, ScannerModelPhase.NOT_INSTALLED, ScannerModelPhase.FAILED, ScannerModelPhase.UNAVAILABLE,
    )
    val canRollback = state.previousVersion != null && !busy
    val progress by animateFloatAsState(
        targetValue = if (state.totalBytes > 0) (state.bytesDownloaded.toFloat() / state.totalBytes).coerceIn(0f, 1f) else 0f,
        label = "Scanner model download progress",
    )

    // Проверяется локальная копия; ни открытие экрана, ни возврат на него не скачивают пакет.
    LaunchedEffect(Unit) { onRefresh() }
    LaunchedEffect(state.version) { confirmingDownload = false }

    Card(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(dimensionResource(R.dimen.scanner_card_padding)),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            Text(stringResource(R.string.scanner_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(scannerStatusText(state)),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.phase == ScannerModelPhase.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.installedVersion != null) {
                Text(
                    stringResource(R.string.scanner_installed_version, state.installedVersion),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (state.updateAvailable) {
                Text(
                    stringResource(R.string.scanner_update_available),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.isApkLocal || state.sourceRepository.isNotBlank()) PackageDetails(state)

            when (state.phase) {
                ScannerModelPhase.DOWNLOADING -> {
                    if (state.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(
                                R.string.scanner_progress,
                                formatFileSize(context, state.bytesDownloaded),
                                formatFileSize(context, state.totalBytes),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                ScannerModelPhase.CHECKING, ScannerModelPhase.QUEUED, ScannerModelPhase.VERIFYING,
                ScannerModelPhase.CANCELLING, ScannerModelPhase.REMOVING ->
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else -> Unit
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.scanner_action_gap)),
                verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.scanner_action_gap)),
            ) {
                if (canDownload) {
                    Button(onClick = { confirmingDownload = true }) {
                        Text(stringResource(downloadActionText(state, selectedVersion)))
                    }
                }
                if (canCancel) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(if (state.isApkLocal) R.string.scanner_local_cancel_install else R.string.scanner_cancel_download))
                    }
                }
                TextButton(onClick = {
                    versionsOpen = true
                    onCheckCatalog(false)
                }) { Text(stringResource(R.string.scanner_versions)) }
                if (canRollback) {
                    TextButton(onClick = { confirmingRollback = true }) {
                        Text(stringResource(R.string.scanner_rollback))
                    }
                }
                if (canRemove) {
                    TextButton(onClick = { confirmingRemoval = true }) { Text(stringResource(R.string.scanner_remove)) }
                }
            }
        }
    }

    if (confirmingDownload && canDownload) {
        val useSavedVersion = selectedVersion?.installed == true
        AlertDialog(
            onDismissRequest = { confirmingDownload = false },
            title = {
                Text(stringResource(when {
                    useSavedVersion -> R.string.scanner_use_version_title
                    state.isApkLocal -> R.string.scanner_local_install_title
                    else -> R.string.scanner_download_title
                }))
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    PackageDetails(state)
                    if (selectedVersion?.channel == PREVIEW_CHANNEL) {
                        Text(stringResource(R.string.scanner_preview_confirmation))
                    }
                    if (state.installedVersion != null && state.installedVersion != state.version) {
                        Text(stringResource(R.string.scanner_update_preserves_current))
                    }
                    Text(stringResource(when {
                        useSavedVersion -> R.string.scanner_use_version_message
                        state.isApkLocal -> R.string.scanner_local_install_message
                        else -> R.string.scanner_download_privacy
                    }))
                    Text(stringResource(R.string.scanner_download_verification))
                    if (!useSavedVersion && !state.isApkLocal) Column {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = dimensionResource(R.dimen.scanner_min_touch_height))
                                .toggleable(
                                value = wifiOnly,
                                role = Role.Checkbox,
                                onValueChange = {
                                    wifiOnly = it
                                    preferences.edit().putBoolean(WIFI_ONLY, it).apply()
                                },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.scanner_detail_gap)),
                        ) {
                            Checkbox(checked = wifiOnly, onCheckedChange = null)
                            Text(stringResource(R.string.scanner_wifi_only), style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            stringResource(R.string.scanner_wifi_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDownload = false
                    onDownload(wifiOnly)
                }) { Text(stringResource(downloadActionText(state, selectedVersion))) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDownload = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (confirmingRemoval && canRemove) {
        AlertDialog(
            onDismissRequest = { confirmingRemoval = false },
            title = { Text(stringResource(R.string.scanner_remove_title)) },
            text = { Text(stringResource(R.string.scanner_remove_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemoval = false
                    onRemove()
                }) { Text(stringResource(R.string.scanner_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemoval = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (versionsOpen) {
        ScannerVersionsDialog(
            state = state,
            canSelect = !busy,
            onSelectVersion = onSelectVersion,
            onCheckCatalog = { onCheckCatalog(true) },
            onDismiss = { versionsOpen = false },
        )
    }

    if (confirmingRollback && canRollback) {
        AlertDialog(
            onDismissRequest = { confirmingRollback = false },
            title = { Text(stringResource(R.string.scanner_rollback_title, state.previousVersion.orEmpty())) },
            text = { Text(stringResource(R.string.scanner_rollback_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRollback = false
                    onRollback()
                }) { Text(stringResource(R.string.scanner_rollback_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRollback = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun PackageDetails(state: ScannerModelState) {
    val context = LocalContext.current
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.scanner_detail_gap))) {
            Text(
                if (state.isApkLocal) stringResource(R.string.scanner_local_source)
                else stringResource(R.string.scanner_source, state.sourceRepository),
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.version.isNotBlank()) {
                Text(stringResource(R.string.scanner_selected_version, state.version), style = MaterialTheme.typography.bodySmall)
            }
            if (state.totalBytes > 0) {
                Text(stringResource(R.string.scanner_size, formatFileSize(context, state.totalBytes)), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private const val PREVIEW_CHANNEL = "preview"
private const val FALLBACK_NOTES_LANGUAGE = "en"

@Composable
private fun ScannerVersionsDialog(
    state: ScannerModelState,
    canSelect: Boolean,
    onSelectVersion: (String) -> Unit,
    onCheckCatalog: () -> Unit,
    onDismiss: () -> Unit,
) {
    val gap = dimensionResource(R.dimen.scanner_section_gap)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scanner_versions)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                Text(stringResource(R.string.scanner_versions_hint))
                Column(
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.scanner_detail_gap)),
                ) {
                    if (state.catalogPhase == CatalogPhase.CHECKING) {
                        Text(stringResource(R.string.scanner_catalog_checking))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else if (state.catalogPhase == CatalogPhase.FAILED) {
                        Text(
                            stringResource(catalogErrorText(state.catalogError)),
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (state.catalogPhase == CatalogPhase.READY && !state.catalogExpired) {
                        Text(stringResource(R.string.scanner_catalog_ready))
                    }
                    state.catalogCheckedAtEpochMillis?.let { checkedAt ->
                        val date = Instant.ofEpochMilli(checkedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                        Text(
                            stringResource(R.string.scanner_catalog_checked_at, date.dateText()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.catalogExpired && !(state.catalogPhase == CatalogPhase.FAILED && state.catalogError == CatalogError.EXPIRED)) {
                        Text(stringResource(R.string.scanner_catalog_expired), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (!canSelect) Text(stringResource(R.string.scanner_versions_busy))
                if (state.versions.isEmpty()) Text(stringResource(R.string.scanner_versions_empty))
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    state.versions.forEach { version ->
                        ScannerVersionRow(version, canSelect) { onSelectVersion(version.key) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
        dismissButton = {
            TextButton(onClick = onCheckCatalog, enabled = state.catalogPhase != CatalogPhase.CHECKING) {
                Text(stringResource(R.string.scanner_catalog_check))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScannerVersionRow(version: ScannerVersionOption, canSelect: Boolean, onSelect: () -> Unit) {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    val notes = version.notes[language]?.takeIf { it.isNotBlank() }
        ?: version.notes[FALLBACK_NOTES_LANGUAGE]?.takeIf { it.isNotBlank() }
    val detailGap = dimensionResource(R.dimen.scanner_detail_gap)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (version.selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .selectable(
                    selected = version.selected,
                    enabled = canSelect && version.compatible,
                    role = Role.RadioButton,
                    onClick = onSelect,
                )
                .padding(dimensionResource(R.dimen.scanner_version_padding)),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.scanner_action_gap)),
        ) {
            RadioButton(selected = version.selected, onClick = null, enabled = canSelect && version.compatible)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(detailGap),
            ) {
                Text(stringResource(R.string.scanner_version, version.version), style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.scanner_action_gap)),
                    verticalArrangement = Arrangement.spacedBy(detailGap),
                ) {
                    Text(
                        stringResource(if (version.channel == PREVIEW_CHANNEL) R.string.scanner_channel_preview else R.string.scanner_channel_stable),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (version.isActive) Text(stringResource(R.string.scanner_version_active), style = MaterialTheme.typography.labelMedium)
                    else if (version.installed) Text(stringResource(R.string.scanner_version_saved), style = MaterialTheme.typography.labelMedium)
                    if (version.selected) Text(stringResource(R.string.scanner_version_selected), style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    stringResource(R.string.scanner_size, formatFileSize(context, version.totalBytes)),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!version.compatible) Text(
                    stringResource(R.string.scanner_version_incompatible),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (notes != null) Text(notes, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun downloadActionText(state: ScannerModelState, selected: ScannerVersionOption?): Int = when {
    state.phase == ScannerModelPhase.FAILED -> R.string.scanner_retry
    selected?.installed == true -> R.string.scanner_use_version
    state.isApkLocal -> R.string.scanner_local_install
    state.installedVersion != null -> R.string.scanner_download_version
    else -> R.string.scanner_download
}

private fun catalogErrorText(error: CatalogError?): Int = when (error) {
    CatalogError.NETWORK -> R.string.scanner_catalog_error_network
    CatalogError.SIGNATURE, CatalogError.INVALID, CatalogError.ROLLBACK -> R.string.scanner_catalog_error_verification
    CatalogError.EXPIRED -> R.string.scanner_catalog_expired
    CatalogError.STORAGE -> R.string.scanner_catalog_error_storage
    CatalogError.UNAVAILABLE, null -> R.string.scanner_catalog_error_unavailable
}

internal fun scannerStatusText(state: ScannerModelState): Int = when (state.phase) {
    ScannerModelPhase.CHECKING -> R.string.scanner_checking
    ScannerModelPhase.UNAVAILABLE -> R.string.scanner_unavailable
    ScannerModelPhase.NOT_INSTALLED -> if (state.isApkLocal) R.string.scanner_local_not_installed else R.string.scanner_not_installed
    ScannerModelPhase.QUEUED -> if (state.isApkLocal) R.string.scanner_local_queued else R.string.scanner_queued
    ScannerModelPhase.DOWNLOADING -> R.string.scanner_downloading
    ScannerModelPhase.VERIFYING -> if (state.isApkLocal) R.string.scanner_local_verifying else R.string.scanner_verifying
    ScannerModelPhase.INSTALLED -> R.string.scanner_installed
    ScannerModelPhase.CANCELLING -> if (state.isApkLocal) R.string.scanner_local_cancelling else R.string.scanner_cancelling
    ScannerModelPhase.REMOVING -> R.string.scanner_removing
    ScannerModelPhase.FAILED -> when (state.error) {
        ScannerModelError.NETWORK -> R.string.scanner_error_network
        ScannerModelError.INTEGRITY -> if (state.isApkLocal) R.string.scanner_local_error_integrity else R.string.scanner_error_integrity
        ScannerModelError.STORAGE -> R.string.scanner_error_storage
        ScannerModelError.SOURCE -> if (state.isApkLocal) R.string.scanner_local_error_source else R.string.scanner_error_source
        ScannerModelError.UNKNOWN, null -> R.string.scanner_error_unknown
    }
}
