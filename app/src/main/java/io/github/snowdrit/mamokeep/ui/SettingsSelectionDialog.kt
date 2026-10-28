package io.github.snowdrit.mamokeep.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.ui.help.*
import io.github.snowdrit.mamokeep.ui.theme.SystemBarAppearance
import java.text.Normalizer

data class SettingsOption(val key: String, val title: String, val detail: String = "")

private val COMBINING_MARKS = Regex("\\p{M}+")
private fun searchText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
    .replace(COMBINING_MARKS, "")

/** Shared searchable list for longer setting choices. The selected row stays explicit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSelectionDialog(
    title: String,
    options: List<SettingsOption>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    hint: String? = null,
    lesson: LessonState? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val searchable = remember(options) {
        options.map { it to searchText("${it.title} ${it.detail} ${it.key}") }
    }
    val matches = remember(searchable, query) {
        val needle = searchText(query.trim())
        searchable.filter { (_, text) -> text.contains(needle, ignoreCase = true) }.map { it.first }
    }
    val listState = rememberLazyListState()
    val learning = lesson?.step?.surface == LessonSurface.CURRENCY
    val shownOptions = if (learning) options else matches
    val manualKey = options.firstOrNull { it.key.isNotEmpty() }?.key
    LaunchedEffect(lesson?.step?.target) {
        if (learning) {
            val key = if (lesson.step?.target == LessonTarget.CURRENCY_AUTO) "" else manualKey
            val index = shownOptions.indexOfFirst { it.key == key }
            if (index >= 0) listState.scrollToItem(index + if (hint != null) 1 else 0)
        }
    }
    val padding = dimensionResource(R.dimen.more_page_padding)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        SystemBarAppearance()
        Surface(Modifier.fillMaxSize()) {
            val body: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
                Scaffold(
                    modifier = Modifier.widthIn(max = dimensionResource(R.dimen.settings_content_max_width)).fillMaxSize(),
                    contentWindowInsets = WindowInsets(0),
                    topBar = {
                        TopAppBar(
                            title = { Text(title) },
                            navigationIcon = {
                                IconButton(onClick = onDismiss) {
                                    Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.common_back))
                                }
                            },
                            windowInsets = WindowInsets(0),
                        )
                    },
                ) { inner ->
                    Column(Modifier.fillMaxSize().padding(inner)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(R.string.settings_search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = padding),
                        )
                        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(vertical = dimensionResource(R.dimen.more_control_gap))) {
                            hint?.let {
                                item {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(padding),
                                    )
                                }
                            }
                            if (shownOptions.isEmpty()) {
                                item { Text(stringResource(R.string.settings_search_empty), Modifier.padding(padding)) }
                            }
                            items(shownOptions, key = { it.key }) { option ->
                                ListItem(
                                    headlineContent = { Text(option.title) },
                                    supportingContent = option.detail.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                                    trailingContent = { RadioButton(selected = option.key == selected, onClick = null) },
                                    modifier = Modifier.then(when (option.key) {
                                        "" -> Modifier.tourTarget(LessonTarget.CURRENCY_AUTO)
                                        manualKey -> Modifier.tourTarget(LessonTarget.CURRENCY_MANUAL)
                                        else -> Modifier
                                    }).selectable(selected = option.key == selected, role = Role.RadioButton) {
                                        onDismiss()
                                        onSelect(option.key)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            }
            if (lesson != null) LessonHost(lesson, LessonSurface.CURRENCY, content = body) else body()
        }
    }
}

@Composable
fun SettingsChoiceRow(title: String, value: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(value) },
            trailingContent = { Icon(painterResource(R.drawable.ic_more_chevron_right), null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )
    }
}
