package io.github.snowdrit.mamokeep.ui.help

import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.snowdrit.mamokeep.R
import kotlin.math.roundToInt

@Stable
class TourAnchors {
    var activeTarget by mutableStateOf<String?>(null)
    var settledTarget by mutableStateOf<String?>(null)
    var spaceForCallout by mutableFloatStateOf(0f)
    val bounds = mutableStateMapOf<String, Rect>()
}

val LocalTourAnchors = staticCompositionLocalOf<TourAnchors?> { null }

/** Measures real layout bounds; no device coordinates or duplicate UI snapshots. */
fun Modifier.tourTarget(id: String): Modifier = composed {
    val anchors = LocalTourAnchors.current ?: return@composed this
    val requester = remember { BringIntoViewRequester() }
    var targetSize by remember { mutableStateOf(IntSize.Zero) }
    DisposableEffect(anchors, id) {
        onDispose { anchors.bounds.remove(id) }
    }
    LaunchedEffect(anchors, anchors.activeTarget == id, anchors.spaceForCallout, targetSize) {
        if (anchors.activeTarget == id) {
            anchors.settledTarget = null
            withFrameNanos { }
            requester.bringIntoView(Rect(
                0f, 0f, targetSize.width.toFloat(), targetSize.height + anchors.spaceForCallout,
            ))
            withFrameNanos { }
            withFrameNanos { }
            anchors.settledTarget = id
        }
    }
    bringIntoViewRequester(requester).onSizeChanged { targetSize = it }.onGloballyPositioned {
        anchors.bounds[id] = it.boundsInRoot()
    }
}

@Composable
fun TourOverlay(
    topics: List<HelpTopic>, catalog: List<HelpTopic>, index: Int, anchors: TourAnchors,
    onPrevious: () -> Unit, onNext: () -> Unit, onClose: () -> Unit,
    progressLabel: String? = null,
    @StringRes closeLabel: Int = R.string.help_skip,
) {
    val topic = topics[index]
    BackHandler { if (index > 0) onPrevious() else onClose() }
    val duration = integerResource(R.integer.help_transition_ms)
    val density = LocalDensity.current
    val margin = dimensionResource(R.dimen.help_margin)
    val gap = dimensionResource(R.dimen.help_gap)
    val focusPadding = dimensionResource(R.dimen.help_focus_padding)
    val focusRadius = dimensionResource(R.dimen.help_focus_radius)
    val stroke = dimensionResource(R.dimen.help_focus_stroke)
    val surface = colorResource(R.color.help_surface)
    val ink = colorResource(R.color.help_on_surface)
    val secondary = colorResource(R.color.help_secondary)
    val accent = colorResource(R.color.help_accent)
    val scrim = colorResource(R.color.help_scrim)
    val maxHeightFraction = LocalResources.current.getFraction(R.fraction.help_max_height, 1, 1)
    val windowSafeTop = WindowInsets.safeDrawing.getTop(density).toFloat()
    val windowSafeBottom = WindowInsets.safeDrawing.getBottom(density).toFloat()
    val title = stringResource(R.string.help_title)
    var origin by remember { mutableStateOf(Offset.Zero) }
    var spaceBelow by remember { mutableFloatStateOf(0f) }
    val safeTop = (windowSafeTop - origin.y).coerceAtLeast(0f)
    val safeBottom = (windowSafeBottom - spaceBelow).coerceAtLeast(0f)
    val rect = anchors.bounds[topic.id]?.translate(-origin)
    val ready = rect != null && rect.width > 0 && rect.height > 0 && anchors.settledTarget == topic.id
    val focusAlpha by animateFloatAsState(if (ready) 1f else 0f, tween(duration), label = "tour focus")
    val left by animateFloatAsState(rect?.left ?: 0f, tween(duration), label = "focus left")
    val top by animateFloatAsState(rect?.top ?: 0f, tween(duration), label = "focus top")
    val right by animateFloatAsState(rect?.right ?: 0f, tween(duration), label = "focus right")
    val bottom by animateFloatAsState(rect?.bottom ?: 0f, tween(duration), label = "focus bottom")

    BoxWithConstraints(
        Modifier.fillMaxSize().onGloballyPositioned {
            origin = it.positionInRoot()
            spaceBelow = it.findRootCoordinates().size.height - origin.y - it.size.height
        }.semantics { paneTitle = title }

    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val marginPx = with(density) { margin.toPx() }
        val gapPx = with(density) { gap.toPx() }
        val padPx = with(density) { focusPadding.toPx() }
        val landscape = maxWidth > maxHeight
        val cardWidth = minOf(
            if (landscape) (maxWidth - margin * 3) / 2 else maxWidth - margin * 2,
            dimensionResource(R.dimen.help_max_width),
        )
        var cardHeight by remember { mutableIntStateOf(0) }
        val availableHeight = with(density) { (heightPx - safeTop - safeBottom).toDp() }
        val minY = safeTop + marginPx
        val maxY = (heightPx - safeBottom - marginPx - cardHeight).coerceAtLeast(minY)
        // Prefer the side with enough space, using the measured card and target.
        val below = (rect?.bottom ?: safeTop) + padPx + gapPx
        val above = (rect?.top ?: safeTop) - padPx - gapPx - cardHeight
        val desiredY = when {
            below <= maxY -> below
            above >= minY -> above
            (rect?.center?.y ?: 0f) < heightPx / 2 -> maxY
            else -> minY
        }.coerceIn(minY, maxY)
        val y by animateFloatAsState(desiredY, tween(duration), label = "tour card position")
        val cardWidthPx = with(density) { cardWidth.toPx() }
        val cardX = if (landscape) {
            if ((rect?.center?.x ?: 0f) > widthPx / 2) marginPx
            else widthPx - marginPx - cardWidthPx
        } else (widthPx - cardWidthPx) / 2
        Canvas(Modifier.fillMaxSize()
            .pointerInput(Unit) {
                // Only the scrim consumes gestures. The card is a sibling above it,
                // so its text can scroll without allowing touches through the hole.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
        ) {
            val hole = Rect(
                (left - padPx).coerceIn(0f, size.width),
                (top - padPx).coerceIn(safeTop, size.height),
                (right + padPx).coerceIn(0f, size.width),
                (bottom + padPx).coerceIn(safeTop, size.height - safeBottom),
            )
            if (hole.width > 0 && hole.height > 0) {
                val path = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, size.width, size.height))
                    addRoundRect(RoundRect(hole, CornerRadius(focusRadius.toPx())))
                }
                drawPath(path, scrim)
                drawRoundRect(scrim.copy(alpha = scrim.alpha * (1f - focusAlpha)), hole.topLeft, hole.size, CornerRadius(focusRadius.toPx()))
                drawRoundRect(surface.copy(alpha = focusAlpha), hole.topLeft, hole.size, CornerRadius(focusRadius.toPx()), style = Stroke(stroke.toPx()))
            } else drawRect(scrim)
        }
        Surface(
            modifier = Modifier.offset { IntOffset(cardX.roundToInt(), y.roundToInt()) }
                .width(cardWidth).heightIn(max = if (landscape) availableHeight - margin * 2 else availableHeight * maxHeightFraction)
                .onSizeChanged { cardHeight = it.height },
            shape = RoundedCornerShape(dimensionResource(R.dimen.help_card_radius)),
            color = surface, contentColor = ink,
            shadowElevation = dimensionResource(R.dimen.help_elevation),
        ) {
            Column(Modifier.padding(dimensionResource(R.dimen.help_card_padding))) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.help_progress, index + 1, topics.size, progressLabel ?: stringResource(topic.page.label)),
                    style = MaterialTheme.typography.labelMedium, color = secondary,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = onClose, colors = ButtonDefaults.textButtonColors(contentColor = secondary)) {
                    Text(stringResource(closeLabel))
                }
                }
                Spacer(Modifier.height(gap))
                AnimatedContent(
                    targetState = topic,
                    modifier = Modifier.weight(1f, fill = false),
                    transitionSpec = { fadeIn(tween(duration)).togetherWith(fadeOut(tween(duration))) },
                    label = "tour copy",
                ) { shown ->
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) {
                        Text(stringResource(shown.title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Text(stringResource(shown.summary), style = MaterialTheme.typography.bodyMedium)
                        shown.recommended.forEach { id ->
                            val recommended = catalog.first { it.id == id }
                            Text(
                                stringResource(R.string.help_recommended_topic, stringResource(recommended.title)),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(gap))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (index > 0) TextButton(onClick = onPrevious, colors = ButtonDefaults.textButtonColors(contentColor = accent)) {
                            Text(stringResource(R.string.help_previous))
                        }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = onNext, enabled = ready, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = surface)) {
                            Text(stringResource(if (index == topics.lastIndex) R.string.help_finish else R.string.help_next))
                        }
                }
            }
        }
    }
}
