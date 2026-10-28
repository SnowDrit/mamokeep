package io.github.snowdrit.mamokeep.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.core.net.toUri
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.data.ThemeMode
import io.github.snowdrit.mamokeep.data.scanner.ScannerModelPhase
import io.github.snowdrit.mamokeep.data.scanner.ScannerModelState
import io.github.snowdrit.mamokeep.domain.RegionPreset
import io.github.snowdrit.mamokeep.domain.RegionalCatalog
import io.github.snowdrit.mamokeep.domain.RegionalPreferences
import io.github.snowdrit.mamokeep.domain.UnitSystem
import io.github.snowdrit.mamokeep.ui.help.*
import io.github.snowdrit.mamokeep.ui.scanner.ScannerModelCard
import io.github.snowdrit.mamokeep.ui.scanner.scannerStatusText
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val WEEKLY_SECTION = "weekly"
private const val SETTINGS_HEADING = "settings_heading"
private const val SUPPORT_SECTION = "support"
private const val PRIVACY_SECTION = "privacy"
private val MORE_SECTION_KEYS = listOf(
    HelpTarget.REMINDERS, HelpTarget.EXPORT, HelpTarget.DATA, SETTINGS_HEADING,
    HelpTarget.APPEARANCE, HelpTarget.UNITS, HelpTarget.COUNTRY, HelpTarget.CURRENCY,
    HelpTarget.SCANNER, SUPPORT_SECTION, PRIVACY_SECTION,
)
private val MORE_EXPANDABLE_TARGETS = setOf(
    HelpTarget.APPEARANCE, HelpTarget.UNITS, HelpTarget.COUNTRY, HelpTarget.SCANNER,
)

/** Frequent actions first; detailed settings stay available in their own rows. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MoreScreen(
    onReminders: () -> Unit,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    units: UnitSystem,
    onUnits: (UnitSystem) -> Unit,
    regionalPreferences: RegionalPreferences,
    regionalCatalog: RegionalCatalog,
    onCurrency: (String?) -> Unit,
    regions: List<RegionPreset>,
    onRegion: (String) -> Unit,
    /** Названия документов пакета, которых у машины ещё нет. */
    missingDocuments: suspend (RegionPreset) -> List<String>,
    onAddDocuments: (RegionPreset) -> Unit,
    onExport: () -> Unit,
    onBackup: () -> Unit,
    backupFolder: String?,
    onBackupFolder: (String?) -> Unit,
    scannerSection: @Composable (expanded: Boolean, onToggle: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    lessonDefinition: FeatureLesson,
    startLesson: Boolean = false,
    onLessonStartConsumed: () -> Unit,
    onImportBlockedChanged: (Boolean) -> Unit = {},
) {
    val lesson = rememberLessonState(lessonDefinition, startLesson, onLessonStartConsumed)
    val context = LocalContext.current
    val regionCode = regionalPreferences.countryCode
    val region = regions.firstOrNull { it.code == regionCode }
    var addingFromCode by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedSection by rememberSaveable { mutableStateOf<String?>(null) }
    var licensesOpen by rememberSaveable { mutableStateOf(false) }
    SideEffect { onImportBlockedChanged(lesson.active || addingFromCode != null || licensesOpen) }
    DisposableEffect(Unit) { onDispose { onImportBlockedChanged(false) } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val folderError = stringResource(R.string.more_folder_permission_failed)
    val linkError = stringResource(R.string.more_support_open_failed)
    fun openProjectLink(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar(linkError) }
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // An automatic backup must retain access after the process restarts.
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                onBackupFolder(uri.toString())
            } catch (_: SecurityException) {
                scope.launch { snackbar.showSnackbar(folderError) }
            }
        }
    }
    val listState = rememberLazyListState()
    val activeTarget = if (lesson.active) lesson.step?.reveal ?: HelpTarget.CURRENCY else LocalTourAnchors.current?.activeTarget
    val visibleSection = activeTarget?.takeIf { it in MORE_EXPANDABLE_TARGETS } ?: expandedSection
    // Off-screen lazy items have no anchor yet. Reveal them before tourTarget measures
    // the expanded content; the same keys drive both the list and this lookup.
    LaunchedEffect(activeTarget) {
        val index = MORE_SECTION_KEYS.indexOfFirst { it == activeTarget }
        if (index >= 0) listState.scrollToItem(index)
    }
    fun toggleSection(key: String) {
        expandedSection = if (expandedSection == key) null else key
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val pagePadding = dimensionResource(R.dimen.more_page_padding)
    val detailGap = dimensionResource(R.dimen.more_detail_gap)
    val controlGap = dimensionResource(R.dimen.more_control_gap)

    LessonHost(lesson, LessonSurface.REGIONAL, modifier) {
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_more)) },
                windowInsets = WindowInsets(0),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(bottom = pagePadding),
        ) {
            item(key = HelpTarget.REMINDERS) {
                MoreRow(
                    icon = R.drawable.ic_clipboard_check_outline,
                    title = stringResource(R.string.more_reminders_title),
                    subtitle = stringResource(R.string.more_reminders_summary),
                    onClick = onReminders,
                    modifier = Modifier.tourTarget(HelpTarget.REMINDERS),
                )
            }
            item(key = HelpTarget.EXPORT) {
                Column(Modifier.fillMaxWidth().tourTarget(HelpTarget.EXPORT)) {
                    MoreSectionHeading(stringResource(R.string.more_data_heading))
                    MoreRow(
                        icon = R.drawable.ic_car_wrench,
                        title = stringResource(R.string.more_export),
                        subtitle = stringResource(R.string.more_export_summary),
                        onClick = onExport,
                    )
                }
            }
            item(key = HelpTarget.DATA) {
                Column(Modifier.fillMaxWidth().tourTarget(HelpTarget.DATA)) {
                    MoreRow(
                        icon = R.drawable.ic_file_document_outline,
                        title = stringResource(R.string.more_backup),
                        subtitle = stringResource(R.string.more_backup_sub),
                        onClick = onBackup,
                    )
                    MoreExpandable(
                        title = stringResource(R.string.more_weekly),
                        summary = stringResource(if (backupFolder == null) R.string.more_weekly_disabled_summary else R.string.more_weekly_enabled_summary),
                        expanded = visibleSection == WEEKLY_SECTION,
                        onClick = { toggleSection(WEEKLY_SECTION) },
                    ) {
                        if (backupFolder == null) MoreDetailText(stringResource(R.string.more_weekly_off))
                        else BackupStatusText(backupFolder)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(controlGap)) {
                            TextButton(onClick = { folderLauncher.launch(null) }) {
                                Text(stringResource(if (backupFolder == null) R.string.more_choose_folder else R.string.more_change_folder))
                            }
                            if (backupFolder != null) {
                                TextButton(onClick = { onBackupFolder(null) }) {
                                    Text(stringResource(R.string.more_weekly_disable))
                                }
                            }
                        }
                    }
                }
            }
            item(key = SETTINGS_HEADING) {
                MoreSectionHeading(stringResource(R.string.more_settings_heading))
                Box(Modifier.padding(horizontal = pagePadding)) { LessonButton(lesson) }
            }
            item(key = HelpTarget.APPEARANCE) {
                MoreExpandable(
                    title = stringResource(R.string.more_appearance_language),
                    summary = stringResource(when (themeMode) {
                        ThemeMode.SYSTEM -> R.string.more_theme_system
                        ThemeMode.LIGHT -> R.string.more_theme_light
                        ThemeMode.DARK -> R.string.more_theme_dark
                    }),
                    expanded = visibleSection == HelpTarget.APPEARANCE,
                    onClick = { toggleSection(HelpTarget.APPEARANCE) },
                    modifier = Modifier.tourTarget(HelpTarget.APPEARANCE),
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(controlGap),
                        verticalArrangement = Arrangement.spacedBy(detailGap),
                    ) {
                        ThemeChip(stringResource(R.string.more_theme_system), ThemeMode.SYSTEM, themeMode, onThemeMode)
                        ThemeChip(stringResource(R.string.more_theme_light), ThemeMode.LIGHT, themeMode, onThemeMode)
                        ThemeChip(stringResource(R.string.more_theme_dark), ThemeMode.DARK, themeMode, onThemeMode)
                    }
                    Column(Modifier.tourTarget(LessonTarget.REGIONAL_LANGUAGE)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        LanguagePicker()
                        MoreDetailText(stringResource(R.string.more_language_hint))
                    } else {
                        MoreDetailText(stringResource(R.string.settings_language_system_hint))
                    }
                    }
                }
            }
            item(key = HelpTarget.UNITS) {
                MoreExpandable(
                    title = stringResource(R.string.more_units),
                    summary = stringResource(unitSystemTitle(units)),
                    expanded = visibleSection == HelpTarget.UNITS,
                    onClick = { toggleSection(HelpTarget.UNITS) },
                    modifier = Modifier.tourTarget(HelpTarget.UNITS),
                ) {
                    FlowRow(
                        modifier = Modifier.tourTarget(LessonTarget.REGIONAL_UNITS),
                        horizontalArrangement = Arrangement.spacedBy(controlGap),
                        verticalArrangement = Arrangement.spacedBy(detailGap),
                    ) {
                        UnitSystem.entries.forEach { system ->
                            FilterChip(
                                selected = system == units,
                                onClick = { onUnits(system) },
                                label = { Text(stringResource(unitSystemTitle(system))) },
                            )
                        }
                    }
                    MoreDetailText(stringResource(unitSystemExplained(units)))
                    MoreDetailText(stringResource(R.string.more_units_intervals_note))
                    MoreDetailText(stringResource(R.string.settings_units_independent))
                }
            }
            item(key = HelpTarget.COUNTRY) {
                MoreExpandable(
                    title = stringResource(R.string.more_country),
                    summary = countryTitle(regionCode),
                    expanded = visibleSection == HelpTarget.COUNTRY,
                    onClick = { toggleSection(HelpTarget.COUNTRY) },
                    modifier = Modifier.tourTarget(HelpTarget.COUNTRY),
                ) {
                    Box(Modifier.tourTarget(LessonTarget.REGIONAL_COUNTRY)) {
                        CountryPicker(regionCode, regionalCatalog, onRegion)
                    }
                    MoreDetailText(stringResource(R.string.settings_country_hint))
                    if (region == null) {
                        MoreDetailText(stringResource(R.string.settings_country_no_documents))
                    }
                    region?.let { preset ->
                        TextButton(onClick = { addingFromCode = preset.code }) {
                            Text(stringResource(R.string.more_add_documents, preset.name))
                        }
                    }
                }
            }
            item(key = HelpTarget.CURRENCY) {
                Box(Modifier.fillMaxWidth().tourTarget(HelpTarget.CURRENCY).tourTarget(LessonTarget.REGIONAL_CURRENCY)) {
                    CurrencyPicker(regionalPreferences, regionalCatalog, onCurrency, lesson)
                }
            }
            item(key = HelpTarget.SCANNER) {
                scannerSection(visibleSection == HelpTarget.SCANNER) { toggleSection(HelpTarget.SCANNER) }
            }
            item(key = SUPPORT_SECTION) {
                val supportUrl = stringResource(R.string.project_support_url)
                MoreSectionHeading(stringResource(R.string.more_project_heading))
                MoreRow(
                    icon = R.drawable.ic_wrench,
                    title = stringResource(R.string.more_support),
                    subtitle = stringResource(R.string.more_support_sub),
                ) {
                    openProjectLink(supportUrl)
                }
            }
            item(key = PRIVACY_SECTION) {
                val privacyUrl = stringResource(R.string.project_privacy_url)
                TextButton(onClick = { openProjectLink(privacyUrl) }, modifier = Modifier.padding(horizontal = pagePadding)) {
                    Text(stringResource(R.string.more_privacy_policy))
                }
                TextButton(onClick = { licensesOpen = true }, modifier = Modifier.padding(horizontal = pagePadding)) {
                    Text(stringResource(R.string.more_licenses))
                }
                Text(
                    stringResource(R.string.more_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = pagePadding, vertical = controlGap),
                )
            }
        }
    }
    }
    if (licensesOpen) {
        val lines by produceState<List<String>?>(null) {
            value = withContext(Dispatchers.IO) {
                listOf("NOTICE.txt", "CC-BY-SA-4.0.txt", "LICENSE.txt", "Apache-2.0.txt", "BSD-3-Clause-Protobuf.txt",
                    "ONNXRuntime-MIT.txt", "ONNXRuntime-ThirdPartyNotices.txt",
                    "OpenCV-NOTICE.txt", "OpenCV-ThirdPartyNotices.txt").flatMap { name ->
                    context.assets.open("licenses/$name").bufferedReader().use { it.readLines() } + ""
                }
            }
        }
        AlertDialog(
            onDismissRequest = { licensesOpen = false },
            title = { Text(stringResource(R.string.more_licenses)) },
            text = {
                val loaded = lines
                if (loaded == null) CircularProgressIndicator()
                else LazyColumn { items(loaded) { Text(it, style = MaterialTheme.typography.bodySmall) } }
            },
            confirmButton = {
                TextButton(onClick = { licensesOpen = false }) { Text(stringResource(R.string.common_close)) }
            },
        )
    }
    regions.firstOrNull { it.code == addingFromCode }?.let { preset ->
        AddDocumentsDialog(
            preset = preset,
            missingDocuments = missingDocuments,
            onDismiss = { addingFromCode = null },
            onConfirm = {
                onAddDocuments(preset)
                addingFromCode = null
            },
        )
    }
}

@Composable
fun ScannerSettingsSection(
    state: ScannerModelState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDownload: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onRefresh: () -> Unit,
    onSelectVersion: (String) -> Unit,
    onCheckCatalog: (Boolean) -> Unit,
    onRollback: () -> Unit,
    onScan: (() -> Unit)? = null,
) {
    MoreExpandable(
        title = stringResource(R.string.scanner_title),
        summary = scannerSummary(state),
        expanded = expanded,
        onClick = onToggle,
        modifier = Modifier.tourTarget(HelpTarget.SCANNER),
    ) {
        Button(onClick = { onScan?.invoke() }, enabled = onScan != null) {
            Text(stringResource(R.string.ocr_open))
        }
        ScannerModelCard(
            state = state,
            onDownload = onDownload,
            onCancel = onCancel,
            onRemove = onRemove,
            onRefresh = onRefresh,
            onSelectVersion = onSelectVersion,
            onCheckCatalog = onCheckCatalog,
            onRollback = onRollback,
        )
    }
}

@Composable
private fun MoreSectionHeading(title: String) {
    Column(Modifier.fillMaxWidth().padding(top = dimensionResource(R.dimen.more_section_gap))) {
        HorizontalDivider()
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(
                horizontal = dimensionResource(R.dimen.more_page_padding),
                vertical = dimensionResource(R.dimen.more_heading_padding),
            ).semantics { heading() },
        )
    }
}

@Composable
private fun MoreExpandable(
    title: String,
    summary: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val stateLabel = stringResource(if (expanded) R.string.more_expanded else R.string.more_collapsed)
    Column(modifier.fillMaxWidth()) {
        MoreRow(
            title = title,
            subtitle = summary,
            onClick = onClick,
            modifier = Modifier.semantics { stateDescription = stateLabel },
            arrowRotation = if (expanded) -90f else 90f,
        )
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(
                    start = dimensionResource(R.dimen.more_page_padding),
                    end = dimensionResource(R.dimen.more_page_padding),
                    bottom = dimensionResource(R.dimen.more_row_padding),
                ),
                verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.more_detail_gap)),
                content = content,
            )
        }
    }
}

@Composable
private fun MoreDetailText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun scannerSummary(state: ScannerModelState): String = when (state.phase) {
    ScannerModelPhase.QUEUED, ScannerModelPhase.DOWNLOADING, ScannerModelPhase.VERIFYING,
    ScannerModelPhase.CANCELLING, ScannerModelPhase.REMOVING -> stringResource(scannerStatusText(state))
    ScannerModelPhase.FAILED -> stringResource(R.string.more_scanner_failed)
    else -> stringResource(R.string.more_scanner_summary)
}

/**
 * Показывает, что именно появится, до того как оно появится.
 *
 * Список считается на месте: сверка с уже заведёнными позициями живёт в репозитории,
 * и дублировать её здесь значило бы завести вторую правду.
 */
@Composable
private fun AddDocumentsDialog(
    preset: RegionPreset,
    missingDocuments: suspend (RegionPreset) -> List<String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var missing by remember(preset.code) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(preset.code) { missing = missingDocuments(preset) }

    val titles = missing
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.more_documents_title, preset.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.more_detail_gap))) {
                when {
                    titles == null -> Text(stringResource(R.string.more_documents_looking))
                    titles.isEmpty() -> Text(stringResource(R.string.more_documents_nothing))
                    else -> {
                        Text(stringResource(R.string.more_documents_will_add))
                        titles.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                        Text(
                            stringResource(R.string.more_documents_term_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !titles.isNullOrEmpty()) { Text(stringResource(R.string.common_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun ThemeChip(
    label: String,
    mode: ThemeMode,
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    FilterChip(
        selected = mode == selected,
        onClick = { onSelect(mode) },
        label = { Text(label) },
    )
}

@Composable
internal fun MoreRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    arrowRotation: Float = 0f,
    onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = dimensionResource(R.dimen.more_min_touch_height))
            .padding(
                horizontal = dimensionResource(R.dimen.more_page_padding),
                vertical = dimensionResource(R.dimen.more_row_padding),
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.more_row_gap)),
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(dimensionResource(R.dimen.more_icon_size)),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.more_detail_gap))) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            MoreDetailText(subtitle)
        }
        Icon(
            painter = painterResource(R.drawable.ic_more_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(dimensionResource(R.dimen.more_disclosure_size)).rotate(arrowRotation),
        )
    }
}
