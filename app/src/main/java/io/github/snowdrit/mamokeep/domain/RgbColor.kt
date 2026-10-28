package io.github.snowdrit.mamokeep.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/** An opaque sRGB colour. Channels are bytes; invalid input is never silently clamped. */
data class RgbColor(val red: Int, val green: Int, val blue: Int) {
    init {
        require(red in 0..255 && green in 0..255 && blue in 0..255)
    }

    val argb: Int get() = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

    fun toHsv(): HsvColor {
        val r = red / 255f
        val g = green / 255f
        val b = blue / 255f
        val maximum = maxOf(r, g, b)
        val minimum = minOf(r, g, b)
        val chroma = maximum - minimum
        if (chroma == 0f) return HsvColor(0f, 0f, maximum)

        val sector = when (maximum) {
            r -> (g - b) / chroma
            g -> (b - r) / chroma + 2f
            else -> (r - g) / chroma + 4f
        }
        return HsvColor.normalized(sector * 60f, chroma / maximum, maximum)
    }

    companion object {
        private val HEX = Regex("[0-9a-fA-F]{3}|[0-9a-fA-F]{6}")
        private val RGB_FUNCTION = Regex("rgb\\(\\s*(.*?)\\s*\\)", RegexOption.IGNORE_CASE)
        private val RGB_COMMAS = Regex("([0-9]{1,3})\\s*,\\s*([0-9]{1,3})\\s*,\\s*([0-9]{1,3})")
        private val RGB_SPACES = Regex("([0-9]{1,3})\\s+([0-9]{1,3})\\s+([0-9]{1,3})")

        /** Alpha is deliberately discarded: colour selection controls opaque outlines. */
        fun fromArgb(argb: Int): RgbColor = RgbColor(
            red = (argb ushr 16) and 0xFF,
            green = (argb ushr 8) and 0xFF,
            blue = argb and 0xFF,
        )

        /** Supports #RGB and #RRGGBB, with an optional leading #. Alpha is not accepted. */
        fun parseHex(text: String): RgbColor? {
            val digits = text.trim().removePrefix("#")
            if (!HEX.matches(digits)) return null
            val expanded = if (digits.length == 3) digits.flatMap { listOf(it, it) }.joinToString("") else digits
            return fromArgb(expanded.toInt(16))
        }

        /** Supports three integer bytes, comma- or space-separated, optionally in rgb(...). */
        fun parseRgb(text: String): RgbColor? {
            val trimmed = text.trim()
            val components = RGB_FUNCTION.matchEntire(trimmed)?.groupValues?.get(1) ?: trimmed
            val match = RGB_COMMAS.matchEntire(components) ?: RGB_SPACES.matchEntire(components) ?: return null
            val channels = match.groupValues.drop(1).map(String::toInt)
            if (channels.any { it !in 0..255 }) return null
            return RgbColor(channels[0], channels[1], channels[2])
        }
    }
}

/** Hue is in degrees [0, 360); saturation and value are fractions [0, 1]. */
data class HsvColor(val hue: Float, val saturation: Float, val value: Float) {
    init {
        require(hue.isFinite() && hue >= 0f && hue < 360f)
        require(saturation.isFinite() && saturation in 0f..1f)
        require(value.isFinite() && value in 0f..1f)
    }

    fun toRgb(): RgbColor {
        val chroma = value * saturation
        val secondary = chroma * (1f - abs((hue / 60f) % 2f - 1f))
        val offset = value - chroma
        val (r, g, b) = when ((hue / 60f).toInt()) {
            0 -> Triple(chroma, secondary, 0f)
            1 -> Triple(secondary, chroma, 0f)
            2 -> Triple(0f, chroma, secondary)
            3 -> Triple(0f, secondary, chroma)
            4 -> Triple(secondary, 0f, chroma)
            else -> Triple(chroma, 0f, secondary)
        }
        // Clamp only floating-point rounding noise at the byte boundaries.
        fun byte(channel: Float) = ((channel + offset) * 255f).roundToInt().coerceIn(0, 255)
        return RgbColor(byte(r), byte(g), byte(b))
    }

    companion object {
        /** Wraps wheel angles, while retaining strict validation of saturation and value. */
        fun normalized(hue: Float, saturation: Float, value: Float): HsvColor {
            require(hue.isFinite())
            val remainder = hue % 360f
            val wrapped = if (remainder < 0f) remainder + 360f else remainder
            // A negative angle extremely close to zero may round up to 360 in Float.
            return HsvColor(if (wrapped >= 360f || wrapped == 0f) 0f else wrapped, saturation, value)
        }
    }
}
