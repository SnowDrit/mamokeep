package io.github.snowdrit.mamokeep.ui

import io.github.snowdrit.mamokeep.domain.FuelType
import io.github.snowdrit.mamokeep.domain.UnitSystem
import io.github.snowdrit.mamokeep.support.EN_WORDS
import io.github.snowdrit.mamokeep.support.RU_WORDS
import io.github.snowdrit.mamokeep.text.formatInputDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class FuelAmountInputTest {
    @Test
    fun unchangedRoundedFieldsPreserveStoredAndScannedAmountsExactly() {
        for (units in UnitSystem.entries) for (type in FuelType.entries) {
            for (words in listOf(EN_WORDS, RU_WORDS)) for (original in listOf(20.5, 42.123456789, 0.0004)) {
                val initial = formatInputDecimal(units.volume(original, type), words)
                val saved = fuelAmountForSave(initial, type, units, original, initial, type)
                assertEquals("$units $type $initial", original.toBits(), checkNotNull(saved).toBits())
            }
        }
    }

    @Test
    fun changingTheQuantityConvertsTheExplicitValueInsteadOfRestoringTheOriginal() {
        for (entered in listOf("5.5", "5,5")) {
            assertEquals(20.819764812, fuelAmountForSave(
                entered, FuelType.PETROL, UnitSystem.US, 20.5, "5.416", FuelType.PETROL,
            )!!, 0.000000001)
        }
    }

    @Test
    fun changingFuelTypeUsesTheDisplayedQuantityInTheSelectedUnit() {
        assertEquals(5.416, fuelAmountForSave(
            "5.416", FuelType.ELECTRIC, UnitSystem.US, 20.5, "5.416", FuelType.PETROL,
        )!!, 0.0)
        assertEquals(20.501790222144, fuelAmountForSave(
            "5.416", FuelType.DIESEL, UnitSystem.US, 20.5, "5.416", FuelType.PETROL,
        )!!, 0.000000001)
    }

    @Test
    fun aNewManualEntryHasNoOriginalQuantityToRestore() {
        assertEquals(20.819764812, fuelAmountForSave(
            "5.5", FuelType.PETROL, UnitSystem.US, null, "", FuelType.PETROL,
        )!!, 0.000000001)
    }
}
