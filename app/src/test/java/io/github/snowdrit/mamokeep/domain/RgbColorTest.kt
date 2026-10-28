package io.github.snowdrit.mamokeep.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class RgbColorTest {
    @Test
    fun `hex accepts long short and mixed case codes`() {
        val expected = RgbColor(170, 187, 204)
        listOf("#aBc", "abc", "#AaBbCc", "AABBCC", "  #abc  ").forEach {
            assertEquals(it, expected, RgbColor.parseHex(it))
        }
        assertEquals(RgbColor(0, 0, 0), RgbColor.parseHex("000"))
        assertEquals(RgbColor(255, 255, 255), RgbColor.parseHex("#fff"))
    }

    @Test
    fun `hex rejects alpha malformed and incomplete codes`() {
        listOf("", "#", "#12", "#1234", "#12345", "#12345678", "1234567", "##abc", "#agc", "0xff00", "ab cd ef", "＋abc").forEach {
            assertNull(it, RgbColor.parseHex(it))
        }
    }

    @Test
    fun `rgb accepts integer channels and optional function notation`() {
        val expected = RgbColor(0, 127, 255)
        listOf("0,127,255", " 0, 127, 255 ", "0 127 255", "rgb(0, 127, 255)", "RGB( 0 127 255 )").forEach {
            assertEquals(it, expected, RgbColor.parseRgb(it))
        }
    }

    @Test
    fun `rgb rejects out of range or ambiguous input without clamping`() {
        listOf("", "0,0", "0,0,0,0", "-1,0,0", "256,0,0", "0,999,0", "0,0,999999999999999", "1.5,0,0", "100%,0%,0%", "+1,0,0", "1,,2,3", "1,2 3", "rgb(1,2,3", "rgba(1,2,3,1)", "rgb(1,2,3))", "rgb(1,2,3) text").forEach {
            assertNull(it, RgbColor.parseRgb(it))
        }
    }

    @Test
    fun `argb restores channels and always writes opaque alpha`() {
        val color = RgbColor(18, 52, 86)
        assertEquals(0xFF123456.toInt(), color.argb)
        assertEquals(color, RgbColor.fromArgb(0x00123456))
        assertEquals(color, RgbColor.fromArgb(0x80123456.toInt()))
    }

    @Test
    fun `invalid channel and HSV constructors fail`() {
        listOf(-1, 256).forEach { channel ->
            assertThrows(IllegalArgumentException::class.java) { RgbColor(channel, 0, 0) }
            assertThrows(IllegalArgumentException::class.java) { RgbColor(0, channel, 0) }
            assertThrows(IllegalArgumentException::class.java) { RgbColor(0, 0, channel) }
        }
        listOf(-1f, 360f, Float.NaN, Float.POSITIVE_INFINITY).forEach { hue ->
            assertThrows(IllegalArgumentException::class.java) { HsvColor(hue, 1f, 1f) }
        }
        listOf(-0.1f, 1.1f, Float.NaN, Float.NEGATIVE_INFINITY).forEach { fraction ->
            assertThrows(IllegalArgumentException::class.java) { HsvColor(0f, fraction, 1f) }
            assertThrows(IllegalArgumentException::class.java) { HsvColor(0f, 1f, fraction) }
        }
        assertThrows(IllegalArgumentException::class.java) { HsvColor.normalized(Float.NaN, 1f, 1f) }
    }

    @Test
    fun `primary and secondary colours match wheel sectors`() {
        listOf(
            0f to RgbColor(255, 0, 0),
            60f to RgbColor(255, 255, 0),
            120f to RgbColor(0, 255, 0),
            180f to RgbColor(0, 255, 255),
            240f to RgbColor(0, 0, 255),
            300f to RgbColor(255, 0, 255),
        ).forEach { (hue, rgb) ->
            assertEquals(rgb, HsvColor(hue, 1f, 1f).toRgb())
            assertEquals(hue, rgb.toHsv().hue, 0.0001f)
            assertEquals(1f, rgb.toHsv().saturation, 0f)
            assertEquals(1f, rgb.toHsv().value, 0f)
        }
    }

    @Test
    fun `wheel angle wraps smoothly through red`() {
        assertEquals(HsvColor(0f, 1f, 1f), HsvColor.normalized(360f, 1f, 1f))
        assertEquals(HsvColor(0f, 1f, 1f), HsvColor.normalized(-720f, 1f, 1f))
        assertEquals(HsvColor(300f, 1f, 1f), HsvColor.normalized(-60f, 1f, 1f))
        assertEquals(HsvColor(60f, 1f, 1f), HsvColor.normalized(780f, 1f, 1f))
        assertEquals(HsvColor(359.5f, 0.25f, 0.75f), HsvColor.normalized(-360.5f, 0.25f, 0.75f))
        assertEquals(RgbColor(255, 0, 0), HsvColor.normalized(-Float.MIN_VALUE, 1f, 1f).toRgb())
        assertEquals(RgbColor(255, 0, 1), RgbColor(255, 0, 1).toHsv().toRgb())
    }

    @Test
    fun `saturation and brightness produce intermediate colours`() {
        assertEquals(RgbColor(51, 128, 204), HsvColor(210f, 0.75f, 0.8f).toRgb())
        assertEquals(RgbColor(128, 64, 64), HsvColor(0f, 0.5f, 0.5f).toRgb())
        val hsv = RgbColor(64, 128, 192).toHsv()
        assertEquals(210f, hsv.hue, 0.0001f)
        assertEquals(2f / 3f, hsv.saturation, 0.0001f)
        assertEquals(192f / 255f, hsv.value, 0.0001f)
    }

    @Test
    fun `grayscale is stable and zero value is black for every hue`() {
        listOf(0, 1, 127, 128, 254, 255).forEach { channel ->
            val rgb = RgbColor(channel, channel, channel)
            assertEquals(0f, rgb.toHsv().saturation, 0f)
            assertEquals(channel / 255f, rgb.toHsv().value, 0f)
            assertEquals(rgb, rgb.toHsv().toRgb())
        }
        listOf(0f, 45f, 179f, 359.99f).forEach {
            assertEquals(RgbColor(0, 0, 0), HsvColor(it, 1f, 0f).toRgb())
            assertEquals(RgbColor(128, 128, 128), HsvColor(it, 0f, 0.5f).toRgb())
        }
    }

    @Test
    fun `RGB survives conversion through HSV without byte drift`() {
        val random = Random(724)
        repeat(4096) {
            val rgb = RgbColor(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            assertEquals(rgb, rgb.toHsv().toRgb())
        }
    }
}
