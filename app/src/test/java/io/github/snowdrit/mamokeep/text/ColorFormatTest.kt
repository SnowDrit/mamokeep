package io.github.snowdrit.mamokeep.text

import io.github.snowdrit.mamokeep.domain.RgbColor
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorFormatTest {
    @Test
    fun `hex preserves leading zeroes and uses uppercase six digit notation`() {
        assertEquals("#000AFF", RgbColor(0, 10, 255).hexText())
        assertEquals("#000000", RgbColor(0, 0, 0).hexText())
        assertEquals("#FFFFFF", RgbColor(255, 255, 255).hexText())
    }

    @Test
    fun `formatted colour codes can be pasted back without losing channels`() {
        listOf(RgbColor(0, 10, 255), RgbColor(255, 0, 31), RgbColor(1, 2, 3)).forEach { color ->
            assertEquals(color, RgbColor.parseHex(color.hexText()))
            assertEquals(color, RgbColor.parseRgb(color.rgbText()))
        }
        assertEquals("0, 10, 255", RgbColor(0, 10, 255).rgbText())
    }
}
