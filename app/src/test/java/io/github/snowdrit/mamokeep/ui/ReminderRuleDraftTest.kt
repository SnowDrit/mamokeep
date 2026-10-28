package io.github.snowdrit.mamokeep.ui

import io.github.snowdrit.mamokeep.domain.ReminderRule
import io.github.snowdrit.mamokeep.domain.UnitSystem
import io.github.snowdrit.mamokeep.support.display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderRuleDraftTest {
    @Test
    fun `saving unchanged distances in miles preserves exact kilometre values`() {
        val initial = ReminderRule(advancedEnabled = true, yellowAdvanceKm = 500, redAdvanceKm = 1234)
        val draft = ReminderRuleDraft(initial, display(UnitSystem.US).units)

        assertEquals("311", draft.yellowAdvance)
        draft.minuteOfDay = 18 * 60 + 45
        val saved = draft.toRuleOrNull()!!

        assertEquals(500, saved.yellowAdvanceKm)
        assertEquals(1234, saved.redAdvanceKm)
        assertEquals(18 * 60 + 45, saved.minuteOfDay)
    }

    @Test
    fun `edited distances in miles convert to canonical kilometres`() {
        val draft = ReminderRuleDraft(ReminderRule(advancedEnabled = true), display(UnitSystem.UK).units)
        draft.yellowAdvance = "100"
        draft.redAdvance = "200"

        assertEquals(161, draft.toRuleOrNull()!!.yellowAdvanceKm)
        assertEquals(322, draft.toRuleOrNull()!!.redAdvanceKm)
    }

    @Test
    fun `active custom fields reject blanks overflow and invalid repeat intervals`() {
        val draft = ReminderRuleDraft(ReminderRule(advancedEnabled = true), display().units)
        draft.yellowAdvance = ""
        assertFalse(draft.isValid)
        assertNull(draft.toRuleOrNull())

        draft.yellowAdvance = "500"
        draft.redRepeatDays = "0"
        assertFalse(draft.isValid)

        draft.redRepeatDays = "1"
        draft.yellowMaxOccurrences = "99999999999999999999"
        assertNull(draft.toRuleOrNull())

        draft.yellowMaxOccurrences = "0"
        assertTrue(draft.isValid)
    }

    @Test
    fun `returning to simple schedule permits saving despite an unfinished custom field`() {
        val initial = ReminderRule(advancedEnabled = true, yellowAdvanceKm = 250, redRepeatDays = 7)
        val draft = ReminderRuleDraft(initial, display().units)
        draft.yellowAdvance = ""
        draft.redRepeatDays = ""
        assertNull(draft.toRuleOrNull())

        draft.advancedEnabled = false
        val saved = draft.toRuleOrNull()
        assertNotNull(saved)
        assertFalse(saved!!.advancedEnabled)
        assertEquals(initial.yellowAdvanceKm, saved.yellowAdvanceKm)
        assertEquals(initial.redRepeatDays, saved.redRepeatDays)

        // Re-enabling the custom fields in the same editing session still shows the draft.
        draft.advancedEnabled = true
        assertEquals("", draft.yellowAdvance)
        assertNull(draft.toRuleOrNull())
    }

    @Test
    fun `at least one valid weekday is required`() {
        val draft = ReminderRuleDraft(ReminderRule(), display().units)
        draft.weekdays = emptySet()
        assertFalse(draft.isValid)
        draft.weekdays = setOf(0, 8)
        assertFalse(draft.isValid)
        draft.weekdays = setOf(3)
        assertTrue(draft.isValid)
        assertEquals(setOf(3), draft.toRuleOrNull()!!.weekdays)
    }

    @Test
    fun `state restoration keeps unfinished fields selected weekdays and the open time dialog`() {
        val initial = ReminderRule(yellowAdvanceKm = 500, redAdvanceKm = 750)
        val draft = ReminderRuleDraft(initial, display(UnitSystem.US).units).apply {
            minuteOfDay = 20 * 60 + 30
            weekdays = setOf(3, 7)
            advancedEnabled = true
            advancedExpanded = true
            yellowRepeatDays = "3"
            redMaxOccurrences = ""
            timeDialogOpen = true
            timeHours = "22"
            timeMinutes = ""
        }
        val restored = ReminderRuleDraft(initial, display(UnitSystem.US).units).apply { restore(draft.save()) }

        assertEquals(20 * 60 + 30, restored.minuteOfDay)
        assertEquals(setOf(3, 7), restored.weekdays)
        assertTrue(restored.advancedEnabled)
        assertTrue(restored.advancedExpanded)
        assertEquals("3", restored.yellowRepeatDays)
        assertEquals("", restored.redMaxOccurrences)
        assertTrue(restored.timeDialogOpen)
        assertEquals("22", restored.timeHours)
        assertEquals("", restored.timeMinutes)
        assertNull(restored.toRuleOrNull())

        restored.redMaxOccurrences = "0"
        assertEquals(500, restored.toRuleOrNull()!!.yellowAdvanceKm)
        assertEquals(750, restored.toRuleOrNull()!!.redAdvanceKm)
    }
}
