package io.github.snowdrit.mamokeep.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.DialogProperties
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.domain.HsvColor
import io.github.snowdrit.mamokeep.domain.RgbColor
import io.github.snowdrit.mamokeep.text.hexText
import io.github.snowdrit.mamokeep.text.rgbText
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private enum class ColourInput { HEX, RGB }

/** The dialog edits a local draft; the garage still saves the complete car explicitly. */
@Composable
fun LineColorSelector(
    color: Int?,
    bodyType: String?,
    onColorChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val tint = silhouetteTint(color)
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.line_color_gap)),
        ) {
            ColourDisc(tint)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.line_color_choose))
                Text(
                    color?.let { RgbColor.fromArgb(it).hexText() } ?: stringResource(R.string.line_color_theme),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (open) {
        LineColorDialog(
            initialColor = color,
            bodyType = bodyType,
            onDismiss = { open = false },
            onConfirm = { onColorChange(it); open = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineColorDialog(
    initialColor: Int?, bodyType: String?, onDismiss: () -> Unit, onConfirm: (Int?) -> Unit,
) {
    val themeColor = silhouetteTint(null)
    val initialRgb = RgbColor.fromArgb(initialColor ?: themeColor.toArgb())
    val initialHsv = initialRgb.toHsv()
    var draft by rememberSaveable { mutableStateOf(initialColor) }
    var hue by rememberSaveable { mutableFloatStateOf(initialHsv.hue) }
    var saturation by rememberSaveable { mutableFloatStateOf(initialHsv.saturation) }
    var brightness by rememberSaveable { mutableFloatStateOf(initialHsv.value) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var hex by rememberSaveable { mutableStateOf(initialRgb.hexText()) }
    var rgb by rememberSaveable { mutableStateOf(initialRgb.rgbText()) }
    var invalidHex by rememberSaveable { mutableStateOf(false) }
    var invalidRgb by rememberSaveable { mutableStateOf(false) }
    var hexFocused by remember { mutableStateOf(false) }
    var rgbFocused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val gap = dimensionResource(R.dimen.line_color_gap)
    val smallGap = dimensionResource(R.dimen.line_color_small_gap)
    val duration = integerResource(R.integer.line_color_transition_ms)
    val tint = silhouetteTint(draft)
    val colourText = draft?.let { RgbColor.fromArgb(it).hexText() } ?: stringResource(R.string.line_color_theme)
    val resources = LocalResources.current
    val palette = remember(resources) {
        val values = resources.obtainTypedArray(R.array.line_color_palette)
        try { List(values.length()) { values.getColor(it, 0) } } finally { values.recycle() }
    }
    val names = stringArrayResource(R.array.line_color_palette_names)
    require(palette.size == names.size)

    fun syncColour(value: RgbColor, preserveInput: ColourInput? = null) {
        val next = value.toHsv()
        // Hue is undefined for grey; retain the wheel position for later saturation changes.
        if (next.saturation > 0f) hue = next.hue
        if (next.value > 0f) saturation = next.saturation
        brightness = next.value
        draft = value.argb
        if (preserveInput != ColourInput.HEX) hex = value.hexText()
        if (preserveInput != ColourInput.RGB) rgb = value.rgbText()
        invalidHex = false
        invalidRgb = false
    }

    fun moveWheel(value: HsvColor) {
        focus.clearFocus()
        hue = value.hue
        saturation = value.saturation
        brightness = value.value
        val next = value.toRgb()
        draft = next.argb
        hex = next.hexText()
        rgb = next.rgbText()
        invalidHex = false
        invalidRgb = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
        modifier = Modifier.imePadding(),
        title = { Text(stringResource(R.string.garage_line_color)) },
        text = {
            Column(
                Modifier.heightIn(max = dialogContentMaxHeight()).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    val previewWidth = dimensionResource(R.dimen.line_color_preview_width)
                    val previewHeight = dimensionResource(R.dimen.line_color_preview_height)
                    Icon(
                        rememberCarProfilePainter(bodyType, previewWidth, previewHeight),
                        contentDescription = stringResource(R.string.line_color_preview, colourText),
                        tint = tint,
                        modifier = Modifier
                            .width(previewWidth)
                            .height(previewHeight)
                            .clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                    Text(colourText, style = MaterialTheme.typography.titleMedium)
                }
                AnimatedContent(
                    targetState = advanced,
                    transitionSpec = { fadeIn(tween(duration)) togetherWith fadeOut(tween(duration)) },
                    label = "colour controls",
                ) { expanded ->
                    Column(verticalArrangement = Arrangement.spacedBy(smallGap)) {
                        if (!expanded) {
                            FilterChip(
                                selected = draft == null,
                                onClick = { syncColour(RgbColor.fromArgb(themeColor.toArgb())); draft = null },
                                label = { Text(stringResource(R.string.line_color_theme)) },
                            )
                            Text(stringResource(R.string.line_color_standard), style = MaterialTheme.typography.labelLarge)
                            FlowRow(
                                Modifier.fillMaxWidth().selectableGroup(),
                                horizontalArrangement = Arrangement.spacedBy(smallGap),
                                verticalArrangement = Arrangement.spacedBy(smallGap),
                            ) {
                                palette.forEachIndexed { index, value ->
                                    ColourChoice(Color(value), names[index], draft == value) {
                                        syncColour(RgbColor.fromArgb(value))
                                    }
                                }
                            }
                            TextButton(onClick = { advanced = true }) {
                                Text(stringResource(R.string.line_color_advanced))
                            }
                        } else {
                            TextButton(onClick = {
                                focus.clearFocus()
                                // Returning to the palette discards unfinished code input only.
                                val selected = RgbColor.fromArgb(draft ?: themeColor.toArgb())
                                hex = selected.hexText()
                                rgb = selected.rgbText()
                                invalidHex = false
                                invalidRgb = false
                                advanced = false
                            }) {
                                Text(stringResource(R.string.line_color_standard))
                            }
                            Text(stringResource(R.string.line_color_wheel_hint), style = MaterialTheme.typography.bodySmall)
                            HueSaturationWheel(HsvColor(hue, saturation, brightness), ::moveWheel)
                            Text(stringResource(R.string.line_color_brightness), style = MaterialTheme.typography.labelLarge)
                            val brightnessLabel = stringResource(R.string.line_color_brightness)
                            Slider(
                                value = brightness,
                                onValueChange = { moveWheel(HsvColor(hue, saturation, it)) },
                                modifier = Modifier.semantics { contentDescription = brightnessLabel },
                            )
                            OutlinedTextField(
                                value = hex,
                                onValueChange = { input ->
                                    hex = input
                                    val parsed = RgbColor.parseHex(input)
                                    if (parsed == null) invalidHex = true else syncColour(parsed, preserveInput = ColourInput.HEX)
                                },
                                modifier = Modifier.fillMaxWidth().onFocusChanged { hexFocused = it.isFocused },
                                label = { Text(stringResource(R.string.line_color_hex)) },
                                supportingText = { Text(stringResource(if (invalidHex && !hexFocused) R.string.line_color_hex_error else R.string.line_color_hex_hint)) },
                                isError = invalidHex && !hexFocused,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                            )
                            OutlinedTextField(
                                value = rgb,
                                onValueChange = { input ->
                                    rgb = input
                                    val parsed = RgbColor.parseRgb(input)
                                    if (parsed == null) invalidRgb = true else syncColour(parsed, preserveInput = ColourInput.RGB)
                                },
                                modifier = Modifier.fillMaxWidth().onFocusChanged { rgbFocused = it.isFocused },
                                label = { Text(stringResource(R.string.line_color_rgb)) },
                                supportingText = { Text(stringResource(if (invalidRgb && !rgbFocused) R.string.line_color_rgb_error else R.string.line_color_rgb_hint)) },
                                isError = invalidRgb && !rgbFocused,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }, enabled = !invalidHex && !invalidRgb) {
                Text(stringResource(R.string.line_color_apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun ColourDisc(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(dimensionResource(R.dimen.line_color_swatch_size))
            .clip(CircleShape).background(color)
            .border(dimensionResource(R.dimen.line_color_border), MaterialTheme.colorScheme.outline, CircleShape)
    )
}

@Composable
private fun ColourChoice(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    val stroke = dimensionResource(R.dimen.line_color_selected_border)
    val mark = colorResource(if (color.luminance() > 0.179f) R.color.line_color_black else R.color.line_color_white)
    Box(
        Modifier.size(dimensionResource(R.dimen.line_color_touch_size))
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
            .then(if (selected) Modifier.border(stroke, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        ColourDisc(color)
        if (selected) Canvas(Modifier.size(dimensionResource(R.dimen.line_color_swatch_size))) {
            val check = Path().apply {
                moveTo(size.width * .25f, size.height * .5f)
                lineTo(size.width * .43f, size.height * .68f)
                lineTo(size.width * .76f, size.height * .32f)
            }
            drawPath(check, mark, style = Stroke(stroke.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun HueSaturationWheel(value: HsvColor, onChange: (HsvColor) -> Unit) {
    val latest by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val segments = integerResource(R.integer.line_color_hue_segments).coerceAtLeast(3)
    val spectrum = remember(segments) {
        (0..segments).map { Color(HsvColor.normalized(it * 360f / segments, 1f, 1f).toRgb().argb) }
    }
    val markerRadius = dimensionResource(R.dimen.line_color_marker_radius)
    val markerStroke = dimensionResource(R.dimen.line_color_marker_stroke)
    val white = colorResource(R.color.line_color_white)
    val black = colorResource(R.color.line_color_black)
    val label = stringResource(R.string.line_color_wheel)
    val code = value.toRgb().hexText()
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val diameter = minOf(maxWidth, dimensionResource(R.dimen.line_color_wheel_size))
        Canvas(
            Modifier.size(diameter)
                .semantics { contentDescription = label; stateDescription = code }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radius = minOf(size.width, size.height) / 2f - markerRadius.toPx() - markerStroke.toPx()
                        // The whole marker remains draggable even at full saturation.
                        if ((down.position - center).getDistance() <= radius + markerRadius.toPx() + markerStroke.toPx()) {
                            fun select(position: Offset) {
                                val delta = position - center
                                val angle = atan2(delta.y, delta.x) * 180f / PI.toFloat()
                                change(HsvColor.normalized(angle, (delta.getDistance() / radius).coerceIn(0f, 1f), latest.value))
                            }
                            down.consume()
                            select(down.position)
                            do {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!pointer.pressed) break
                                pointer.consume()
                                select(pointer.position)
                            } while (true)
                        }
                    }
                }
        ) {
            val radius = size.minDimension / 2f - markerRadius.toPx() - markerStroke.toPx()
            drawCircle(Brush.sweepGradient(spectrum, center), radius)
            drawCircle(Brush.radialGradient(listOf(white, Color.Transparent), center, radius), radius)
            val angle = value.hue * PI.toFloat() / 180f
            val marker = center + Offset(cos(angle), sin(angle)) * radius * value.saturation
            drawCircle(black, markerRadius.toPx() + markerStroke.toPx(), marker)
            drawCircle(white, markerRadius.toPx(), marker)
            drawCircle(Color(value.toRgb().argb), markerRadius.toPx() - markerStroke.toPx(), marker)
        }
    }
}
