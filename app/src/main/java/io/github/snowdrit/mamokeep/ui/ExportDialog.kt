package io.github.snowdrit.mamokeep.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.getSystemService
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.ui.help.FeatureLesson
import io.github.snowdrit.mamokeep.domain.export.ExportPreset
import io.github.snowdrit.mamokeep.text.formatNumber
import io.github.snowdrit.mamokeep.ui.help.LessonHost
import io.github.snowdrit.mamokeep.ui.help.LessonSurface
import io.github.snowdrit.mamokeep.ui.help.LessonTarget
import io.github.snowdrit.mamokeep.ui.help.rememberLessonState
import io.github.snowdrit.mamokeep.ui.help.tourTarget
import io.github.snowdrit.mamokeep.ui.theme.SystemBarAppearance
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExportDialog(
    carName: String,
    onDismiss: () -> Unit,
    buildText: suspend (ExportPreset, Boolean, String) -> String,
    lessonDefinition: FeatureLesson,
    startLesson: Boolean = false,
    onLessonStartConsumed: () -> Unit,
) {
    val context = LocalContext.current
    // The snapshot stays in memory across rotation; large car histories never enter a Bundle.
    val state: ExportDialogViewModel = viewModel()
    val lesson = rememberLessonState(lessonDefinition, startLesson, onLessonStartConsumed)
    var awaitingLessonPreview by rememberSaveable { mutableStateOf(false) }
    val gap = dimensionResource(R.dimen.export_dialog_gap)
    val sectionGap = dimensionResource(R.dimen.export_section_gap)
    val padding = dimensionResource(R.dimen.export_screen_padding)
    val detailGap = dimensionResource(R.dimen.export_detail_gap)
    val minTouchHeight = dimensionResource(R.dimen.export_dialog_min_touch_height)
    val clipboardLimit = integerResource(R.integer.export_clipboard_safe_chars)
    val clipboardLabel = stringResource(R.string.app_name)
    val filename = stringResource(R.string.export_file_name)
    val shareTitle = stringResource(R.string.export_share_title)
    val questionLabel = stringResource(R.string.export_question_label)
    // The lesson can explain setup while retaining an already reviewed snapshot and draft.
    val preparedPreview = state.preview
    val preview = preparedPreview.takeIf { !lesson.active || lesson.step?.surface == LessonSurface.EXPORT_PREVIEW }
    val busy = state.operation != null
    val canClose = state.operation != ExportOperation.WRITING_FILE
    val largePreview = preview != null && preview.length > clipboardLimit
    val close = {
        if (canClose) {
            state.reset()
            onDismiss()
        }
    }
    val back = { if (preview == null) close() else state.back() }
    val dismiss = { if (lesson.active) lesson.previous() else back() }
    LaunchedEffect(lesson.active, lesson.step?.target, state.operation, preparedPreview) {
        if (awaitingLessonPreview && (!lesson.active || lesson.step?.target != LessonTarget.EXPORT_BUILD)) {
            awaitingLessonPreview = false
        }
        if (awaitingLessonPreview && state.operation == null) {
            awaitingLessonPreview = false
            if (preparedPreview != null) lesson.next()
        }
        // Process recreation may restore the step, but never the large in-memory snapshot.
        if (lesson.active && lesson.step?.surface == LessonSurface.EXPORT_PREVIEW && preparedPreview == null) {
            lesson.show(LessonTarget.EXPORT_BUILD)
        }
    }
    val nextLesson = {
        if (lesson.step?.target == LessonTarget.EXPORT_BUILD && preparedPreview == null) {
            if (!busy) {
                awaitingLessonPreview = true
                state.prepare(buildText)
            }
        } else lesson.next()
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        state.saveFile(uri, context.applicationContext.contentResolver)
    }
    val saveFile = {
        if (state.beginFileSelection()) {
            try {
                saveLauncher.launch(filename)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.fileSelectionFailed()
            }
        }
    }
    val copyText = {
        state.copy(clipboardLimit) { snapshot ->
            checkNotNull(context.getSystemService<ClipboardManager>())
                .setPrimaryClip(ClipData.newPlainText(clipboardLabel, snapshot))
        }
    }
    val shareText = {
        state.share(clipboardLimit) { snapshot ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, snapshot)
            }
            context.startActivity(Intent.createChooser(intent, shareTitle))
        }
    }

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        SystemBarAppearance()
        Surface(Modifier.fillMaxSize()) {
            LessonHost(
                state = lesson,
                surface = if (preview == null) LessonSurface.EXPORT_SETUP else LessonSurface.EXPORT_PREVIEW,
                onNext = nextLesson,
            ) {
                Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                    Scaffold(
                        modifier = Modifier.widthIn(max = dimensionResource(R.dimen.export_content_max_width)).fillMaxSize(),
                        contentWindowInsets = WindowInsets(0),
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(
                                        stringResource(if (preview == null) R.string.export_screen_title else R.string.export_preview_title),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = dismiss, enabled = if (preview == null) canClose else !busy) {
                                        Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.export_back))
                                    }
                                },
                                actions = {
                                    if (!busy) IconButton(onClick = lesson::start) {
                                        Icon(painterResource(R.drawable.ic_help_outline), stringResource(R.string.help_lesson_start))
                                    }
                                    if (preview != null) {
                                        TextButton(onClick = close, enabled = canClose) { Text(stringResource(R.string.common_close)) }
                                    }
                                },
                                windowInsets = WindowInsets(0),
                            )
                        },
                        bottomBar = {
                            Surface {
                                Column {
                                    HorizontalDivider()
                                    Column(
                                        Modifier.padding(horizontal = padding, vertical = gap)
                                            .tourTarget(if (preview == null) LessonTarget.EXPORT_BUILD else LessonTarget.EXPORT_SEND),
                                        verticalArrangement = Arrangement.spacedBy(gap),
                                    ) {
                                        state.operation?.let { operation ->
                                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                            Text(
                                                stringResource(operation.label),
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                            )
                                        }
                                        state.status?.let { message ->
                                            Text(
                                                stringResource(message),
                                                color = if (state.statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                            )
                                        }
                                        if (preview == null) {
                                            Button(onClick = { state.prepare(buildText) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = minTouchHeight)) {
                                                Text(stringResource(R.string.export_preview))
                                            }
                                        } else {
                                            Button(onClick = if (largePreview) saveFile else copyText, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = minTouchHeight)) {
                                                Text(stringResource(if (largePreview) R.string.export_save_file else R.string.export_copy))
                                            }
                                            if (!largePreview) {
                                                FlowRow(
                                                    Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(gap),
                                                    verticalArrangement = Arrangement.spacedBy(gap),
                                                ) {
                                                    OutlinedButton(
                                                        onClick = shareText,
                                                        enabled = !busy,
                                                        modifier = Modifier.weight(1f).heightIn(min = minTouchHeight),
                                                    ) { Text(stringResource(R.string.export_share)) }
                                                    OutlinedButton(onClick = saveFile, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = minTouchHeight)) {
                                                        Text(stringResource(R.string.export_save_file))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    ) { inner ->
                        // Every stage starts at the top; rotation retains its scroll position.
                        key(preview != null) {
                            if (preview == null) {
                                Column(
                                    Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(padding),
                                    verticalArrangement = Arrangement.spacedBy(sectionGap),
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                                        Text(carName, style = MaterialTheme.typography.titleLarge)
                                        Text(stringResource(R.string.export_setup_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Column(Modifier.tourTarget(LessonTarget.EXPORT_CONTENT), verticalArrangement = Arrangement.spacedBy(gap)) {
                                        Text(stringResource(R.string.export_scenario_label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(gap)) {
                                            ExportPreset.entries.forEach { option ->
                                                ExportOption(option, selected = state.preset == option, enabled = !busy, onClick = { state.selectPreset(option) })
                                            }
                                        }
                                    }
                                    Column(Modifier.tourTarget(LessonTarget.EXPORT_QUESTION), verticalArrangement = Arrangement.spacedBy(gap)) {
                                        Text(questionLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                                        OutlinedTextField(
                                            value = state.question,
                                            onValueChange = state::updateQuestion,
                                            enabled = !busy,
                                            placeholder = { Text(stringResource(R.string.export_question_hint)) },
                                            supportingText = { Text(stringResource(R.string.export_question_supporting)) },
                                            minLines = 2,
                                            maxLines = 4,
                                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = questionLabel },
                                        )
                                    }
                                    Column(Modifier.tourTarget(LessonTarget.EXPORT_PRIVACY), verticalArrangement = Arrangement.spacedBy(gap)) {
                                        Text(stringResource(R.string.export_privacy_label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                                        Row(
                                            Modifier.fillMaxWidth().heightIn(min = minTouchHeight).toggleable(value = state.anonymize, enabled = !busy, role = Role.Switch, onValueChange = state::setHideIdentity),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(gap),
                                        ) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(detailGap)) {
                                                Text(stringResource(R.string.export_anonymize), style = MaterialTheme.typography.bodyLarge)
                                                Text(stringResource(R.string.export_anonymize_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Switch(checked = state.anonymize, onCheckedChange = null, enabled = !busy)
                                        }
                                    }
                                }
                            } else {
                                SelectionContainer(Modifier.fillMaxSize().padding(inner)) {
                                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(padding), verticalArrangement = Arrangement.spacedBy(detailGap)) {
                                        item {
                                            Column(Modifier.tourTarget(LessonTarget.EXPORT_REVIEW), verticalArrangement = Arrangement.spacedBy(gap)) {
                                                Text(carName, style = MaterialTheme.typography.titleLarge)
                                                Text(
                                                    stringResource(R.string.export_preview_size, formatNumber(preview.length, displayWords(LocalResources.current)), stringResource(exportPresetTitle(state.preset))),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Text(stringResource(R.string.export_preview_review), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                if (largePreview) Text(stringResource(R.string.export_large_preview_hint), style = MaterialTheme.typography.bodyMedium)
                                                HorizontalDivider(Modifier.padding(vertical = gap))
                                            }
                                        }
                                        // Only visible lines are measured, including for a full multi-year history.
                                        items(state.previewLines) { line ->
                                            when {
                                                line.isBlank() -> Spacer(Modifier.height(gap))
                                                line == "---" -> HorizontalDivider(Modifier.padding(vertical = gap))
                                                line.startsWith("## ") -> Text(line.removePrefix("## "), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = gap).semantics { heading() })
                                                else -> Text(line, style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportOption(option: ExportPreset, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .padding(dimensionResource(R.dimen.export_option_padding)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.export_dialog_gap)),
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.export_detail_gap))) {
                Text(stringResource(exportPresetTitle(option)), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(exportPresetSubtitle(option)), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
