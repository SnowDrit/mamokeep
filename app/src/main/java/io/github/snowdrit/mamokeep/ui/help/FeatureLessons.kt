package io.github.snowdrit.mamokeep.ui.help

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.snowdrit.mamokeep.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object LessonTarget {
    const val REMINDER_TIME = "reminder_time"
    const val REMINDER_DAYS = "reminder_days"
    const val REMINDER_ADVANCED = "reminder_advanced"
    const val REMINDER_THRESHOLDS = "reminder_thresholds"
    const val REMINDER_REPEATS = "reminder_repeats"
    const val REMINDER_SAVE = "reminder_save"
    const val EXPORT_CONTENT = "export_content"
    const val EXPORT_QUESTION = "export_question"
    const val EXPORT_PRIVACY = "export_privacy"
    const val EXPORT_BUILD = "export_build"
    const val EXPORT_REVIEW = "export_review"
    const val EXPORT_SEND = "export_send"
    const val REGIONAL_LANGUAGE = "regional_language"
    const val REGIONAL_COUNTRY = "regional_country"
    const val REGIONAL_UNITS = "regional_units"
    const val REGIONAL_CURRENCY = "regional_currency"
    const val CURRENCY_AUTO = "currency_auto"
    const val CURRENCY_MANUAL = "currency_manual"
}

enum class LessonSurface { REMINDERS, EXPORT_SETUP, EXPORT_PREVIEW, REGIONAL, CURRENCY }

data class LessonStep(
    val target: String,
    val surface: LessonSurface,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    val reveal: String? = null,
)

data class FeatureLesson(val id: String, @StringRes val title: Int, val steps: List<LessonStep>) {
    val topics = steps.map { HelpTopic(it.target, HelpPage.MORE, it.title, it.summary, it.summary) }
}

@Serializable
internal data class LessonStepAsset(
    val target: String, val surface: LessonSurface, val title: String, val summary: String,
    val reveal: String? = null,
)

@Serializable
internal data class LessonAsset(val id: String, val title: String, val steps: List<LessonStepAsset>)

object LessonCatalog {
    internal fun parse(text: String): List<LessonAsset> = Json.decodeFromString<List<LessonAsset>>(text).also { lessons ->
        require(lessons.isNotEmpty() && lessons.map { it.id }.distinct().size == lessons.size)
        lessons.forEach { lesson ->
            require(lesson.steps.isNotEmpty())
            require(lesson.steps.map { it.target }.distinct().size == lesson.steps.size)
        }
    }

    fun load(context: Context): List<FeatureLesson> {
        val lessons = context.assets.open("help/lessons.json").bufferedReader().use { parse(it.readText()) }
        fun stringId(name: String) = context.resources.getIdentifier(name, "string", context.packageName)
            .also { require(it != 0) { "Missing lesson resource: $name" } }
        return lessons.map { lesson ->
            FeatureLesson(lesson.id, stringId(lesson.title), lesson.steps.map {
                LessonStep(it.target, it.surface, stringId(it.title), stringId(it.summary), it.reveal)
            })
        }
    }
}

/** Only presentation state. A lesson has no access to settings, records or external actions. */
@Stable
class LessonState(val lesson: FeatureLesson, initialIndex: Int = -1) {
    var index by mutableIntStateOf(initialIndex)
        private set
    val active: Boolean get() = index >= 0
    val step: LessonStep? get() = lesson.steps.getOrNull(index)
    fun start() { index = 0 }
    fun close() { index = -1 }
    fun previous() { if (index > 0) index-- else close() }
    fun next() { if (index < lesson.steps.lastIndex) index++ else close() }
    fun show(target: String) { index = lesson.steps.indexOfFirst { it.target == target }.also { require(it >= 0) } }
}

@Composable
fun rememberLessonState(lesson: FeatureLesson, start: Boolean = false, onStartConsumed: () -> Unit): LessonState {
    val state = rememberSaveable(lesson.id, saver = Saver(
        save = { it.index }, restore = { LessonState(lesson, it) },
    )) { LessonState(lesson, if (start) 0 else -1) }
    // A navigation request starts this instance once. Keeping it pending would
    // replay a completed lesson when another full-screen flow replaces its host.
    SideEffect { if (start) onStartConsumed() }
    return state
}

@Composable
fun LessonButton(state: LessonState) {
    TextButton(onClick = state::start) { Text(stringResource(R.string.help_lesson_start)) }
}

/** Each Android window owns its measured anchors; the same session can cross windows. */
@Composable
fun LessonHost(
    state: LessonState,
    surface: LessonSurface,
    modifier: Modifier = Modifier,
    onNext: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val anchors = remember { TourAnchors() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.active) {
        if (state.active) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    val visible = state.active && state.step?.surface == surface
    SideEffect { anchors.activeTarget = if (visible) state.step?.target else null }
    val parentAnchors = LocalTourAnchors.current
    CompositionLocalProvider(LocalTourAnchors provides if (state.active) anchors else parentAnchors) {
        val calloutFraction = LocalResources.current.getFraction(R.fraction.help_max_height, 1, 1)
        val calloutGap = dimensionResource(R.dimen.help_margin) + dimensionResource(R.dimen.help_gap) + dimensionResource(R.dimen.help_focus_padding)
        val calloutGapPx = with(LocalDensity.current) { calloutGap.toPx() }
        BoxWithConstraints(modifier.fillMaxSize().onSizeChanged {
            anchors.spaceForCallout = if (it.height >= it.width) it.height * calloutFraction + calloutGapPx else 0f
        }) {
            // Keep controls visible beside the callout in short, wide windows.
            // This reflows the real form; its anchors are measured again after rotation.
            val landscapeFraction = LocalResources.current.getFraction(R.fraction.help_landscape_content, 1, 1)
            val contentWidth = if (state.active && maxWidth > maxHeight) maxWidth * landscapeFraction else maxWidth
            Box(Modifier.width(contentWidth).fillMaxHeight()
                .then(if (state.active) Modifier.clearAndSetSemantics { } else Modifier)) { content() }
            if (visible) {
                TourOverlay(
                    topics = state.lesson.topics, catalog = emptyList(), index = state.index, anchors = anchors,
                    onPrevious = state::previous, onNext = onNext ?: state::next, onClose = state::close,
                    progressLabel = stringResource(state.lesson.title), closeLabel = R.string.help_lesson_close,
                )
            }
        }
    }
}
