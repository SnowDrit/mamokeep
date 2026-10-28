package io.github.snowdrit.mamokeep.ui.help

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.snowdrit.mamokeep.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(topics: List<HelpTopic>, lessons: List<FeatureLesson>, onLesson: (String) -> Unit, onClose: () -> Unit, onReplay: () -> Unit, onShow: (HelpTopic) -> Unit, modifier: Modifier = Modifier) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = topics.firstOrNull { it.id == selectedId }
    val back = { if (selectedId != null) selectedId = null else onClose() }
    BackHandler(onBack = back)
    val margin = dimensionResource(R.dimen.help_margin)
    val gap = dimensionResource(R.dimen.help_gap)
    Scaffold(
        modifier = modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.help_title)) },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.help_previous))
                    }
                },
                actions = {
                    if (selected != null) TextButton(onClick = onClose) { Text(stringResource(R.string.help_finish)) }
                },
                windowInsets = WindowInsets(0),
            )
        },
    ) { inner ->
        AnimatedContent(targetState = selected, label = "help article", modifier = Modifier.padding(inner)) { article ->
            if (article != null) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(margin), verticalArrangement = Arrangement.spacedBy(gap)) {
                    Text(stringResource(article.title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    OutlinedButton(onClick = { article.lesson?.let(onLesson) ?: onShow(article) }) {
                        Text(stringResource(if (article.lesson != null) R.string.help_lesson_start else R.string.help_show))
                    }
                    Text(stringResource(article.detail), style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(margin), verticalArrangement = Arrangement.spacedBy(gap)) {
                    item {
                        Text(stringResource(R.string.help_lessons_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.help_lessons_hint), style = MaterialTheme.typography.bodyMedium)
                    }
                    items(lessons, key = { "lesson_${it.id}" }) { lesson ->
                        ListItem(
                            headlineContent = { Text(stringResource(lesson.title)) },
                            supportingContent = { Text(stringResource(R.string.help_lesson_steps, lesson.steps.size)) },
                            trailingContent = { Icon(painterResource(R.drawable.ic_more_chevron_right), null) },
                            modifier = Modifier.clickable { onLesson(lesson.id) },
                        )
                    }
                    item {
                        OutlinedButton(onClick = onReplay, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.help_replay)) }
                    }
                    topics.filter { it.showInHelp }.groupBy { it.page }.forEach { (page, group) ->
                        item(key = page.name) {
                            Text(stringResource(page.label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = gap).semantics { heading() })
                        }
                        items(group, key = { it.id }) { topic ->
                            Card(onClick = { selectedId = topic.id }, modifier = Modifier.fillMaxWidth()) {
                                ListItem(
                                    headlineContent = { Text(stringResource(topic.title)) },
                                    supportingContent = { Text(stringResource(topic.summary)) },
                                    trailingContent = { Icon(painterResource(R.drawable.ic_help_outline), contentDescription = null) },
                                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
