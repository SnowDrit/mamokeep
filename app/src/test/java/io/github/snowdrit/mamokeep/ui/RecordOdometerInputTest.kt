package io.github.snowdrit.mamokeep.ui

import io.github.snowdrit.mamokeep.domain.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordOdometerInputTest {
    @Test
    fun `saving an unchanged reading or only its note preserves exact kilometres`() {
        for (units in listOf(UnitSystem.US, UnitSystem.UK)) {
            assertEquals(1000, recordOdometerForSave("621", 1000, units, initialWasEstimated = false))
        }
    }

    @Test
    fun `editing the displayed miles converts the new value`() {
        assertEquals(1001, recordOdometerForSave("622", 1000, UnitSystem.US, initialWasEstimated = false))
        assertNull(recordOdometerForSave("", 1000, UnitSystem.US, initialWasEstimated = false))
    }

    @Test
    fun `new estimated work uses its current date field instead of retaining the initial estimate`() {
        // A changed date may produce 999 km, displayed as the same 621 mi as the initial 1000 km.
        assertEquals(999, recordOdometerForSave("621", 1000, UnitSystem.US, initialWasEstimated = true))
        assertEquals(805, recordOdometerForSave("500", 1000, UnitSystem.US, initialWasEstimated = true))
        assertNull(recordOdometerForSave("", 1000, UnitSystem.US, initialWasEstimated = true))
    }
}
