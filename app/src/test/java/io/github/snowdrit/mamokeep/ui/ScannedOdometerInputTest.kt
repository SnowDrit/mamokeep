package io.github.snowdrit.mamokeep.ui

import io.github.snowdrit.mamokeep.domain.UnitSystem
import io.github.snowdrit.mamokeep.ui.scanner.scannedOdometerForSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.roundToInt

class ScannedOdometerInputTest {
    @Test
    fun unchangedScannedKilometersSurviveRoundedMilesExactly() {
        for (units in UnitSystem.entries) for (originalKm in listOf(0, 1, 2, 15_000, 9_999_999)) {
            val displayed = units.distance(originalKm).roundToInt().toString()
            assertEquals("$units $displayed", originalKm, scannedOdometerForSave(displayed, originalKm, units))
        }
        assertEquals(15_000, scannedOdometerForSave("9321", 15_000, UnitSystem.US))
    }

    @Test
    fun editedAndNewReadingsConvertTheExplicitDisplayValue() {
        assertEquals(16_093, scannedOdometerForSave("10000", 15_000, UnitSystem.US))
        assertEquals(16_093, scannedOdometerForSave("10000", null, UnitSystem.UK))
        assertEquals(10_000, scannedOdometerForSave("10000", 15_000, UnitSystem.METRIC))
    }

    @Test
    fun removingTheReadingDoesNotRestoreTheScannedValue() {
        assertNull(scannedOdometerForSave("", 15_000, UnitSystem.US))
        assertNull(scannedOdometerForSave("", null, UnitSystem.US))
    }
}
