package io.github.snowdrit.mamokeep.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderPolicyTest {
    private val item = MaintenanceItem(
        id = 1, kind = ItemKind.MAINTENANCE, title = "Масло",
        intervalKm = 10_000, intervalMonths = null,
        lastDoneKm = 20_000, lastDoneDate = LocalDate.of(2026, 1, 1),
        warnKm = 1_000, warnDays = 14,
    )
    private val wednesday = ZonedDateTime.of(2026, 10, 7, 9, 0, 0, 0, ZoneId.of("Europe/Moscow"))
    private val advanced = ReminderRule(advancedEnabled = true, yellowAdvanceKm = 500, redAdvanceKm = 300)

    private fun status(kmLeft: Int, source: MaintenanceItem = item) =
        MaintenanceCalculator.status(source, source.lastDoneKm!! + source.intervalKm!! - kmLeft, wednesday.toLocalDate())

    @Test fun `early yellow begins exactly the chosen distance before yellow threshold`() {
        assertNull(ReminderPolicy.stage(status(1_501), advanced))
        assertEquals(ReminderStage.BEFORE_YELLOW, ReminderPolicy.stage(status(1_500), advanced))
        assertEquals(Urgency.OK, status(1_500).urgency)
        assertEquals(ReminderStage.YELLOW, ReminderPolicy.stage(status(1_000), advanced))
    }

    @Test fun `pre red never changes the actual overdue status`() {
        assertEquals(ReminderStage.YELLOW, ReminderPolicy.stage(status(301), advanced))
        assertEquals(ReminderStage.BEFORE_RED, ReminderPolicy.stage(status(300), advanced))
        assertEquals(Urgency.SOON, status(300).urgency)
        assertEquals(ReminderStage.RED, ReminderPolicy.stage(status(0), advanced))
        assertEquals(ReminderStage.RED, ReminderPolicy.stage(status(-800), advanced))
    }

    @Test fun `red advance can start before yellow when the user chooses a larger buffer`() {
        assertEquals(ReminderStage.BEFORE_RED, ReminderPolicy.stage(status(2_000), advanced.copy(redAdvanceKm = 2_000)))
    }

    @Test fun `turning advanced settings off preserves values but ignores them`() {
        val rule = advanced.copy(advancedEnabled = false, yellowMaxOccurrences = 1, yellowRepeatDays = 30)
        assertNull(ReminderPolicy.stage(status(1_400), rule))
        val soon = status(900)
        val sent = ReminderPolicy.afterDelivery(soon, ReminderStage.YELLOW, null, wednesday)
        assertTrue(ReminderPolicy.shouldNotify(soon, rule, sent, wednesday.plusDays(1)))
    }

    @Test fun `unknown baseline and disabled items never send`() {
        val unknown = MaintenanceCalculator.status(item.copy(lastDoneKm = null, lastDoneDate = null), 30_000, wednesday.toLocalDate())
        assertNull(ReminderPolicy.stage(unknown, advanced))
        assertFalse(ReminderPolicy.shouldNotify(status(-200), advanced.copy(enabled = false), null, wednesday))
    }

    @Test fun `calendar documents use the same advanced stages without a kilometre baseline`() {
        val document = item.copy(kind = ItemKind.DOCUMENT, intervalKm = null, lastDoneKm = null, intervalMonths = 12)
        val rule = advanced.copy(yellowAdvanceDays = 5, redAdvanceDays = 3)
        fun on(date: String) = MaintenanceCalculator.status(document, null, LocalDate.parse(date))
        assertNull(ReminderPolicy.stage(on("2026-12-12"), rule))
        assertEquals(ReminderStage.BEFORE_YELLOW, ReminderPolicy.stage(on("2026-12-13"), rule))
        assertEquals(ReminderStage.YELLOW, ReminderPolicy.stage(on("2026-12-18"), rule))
        assertEquals(ReminderStage.BEFORE_RED, ReminderPolicy.stage(on("2026-12-29"), rule))
        assertEquals(ReminderStage.RED, ReminderPolicy.stage(on("2027-01-01"), rule))
    }

    @Test fun `calendar overdue outranks even a fresh kilometre axis`() {
        val mixed = item.copy(intervalMonths = 1)
        val calculated = MaintenanceCalculator.status(mixed, 20_000, wednesday.toLocalDate())
        assertEquals(ReminderStage.RED, ReminderPolicy.stage(calculated, advanced))
    }

    @Test fun `Wednesday schedule skips other days and never runs before selected local time`() {
        val rule = ReminderRule(weekdays = setOf(3))
        assertFalse(ReminderPolicy.shouldNotify(status(100), rule, null, wednesday.minusMinutes(1)))
        assertTrue(ReminderPolicy.shouldNotify(status(100), rule, null, wednesday))
        assertTrue(ReminderPolicy.shouldNotify(status(100), rule, null, wednesday.plusHours(2)))
        assertFalse(ReminderPolicy.shouldNotify(status(100), rule, null, wednesday.plusDays(1)))
        assertEquals(wednesday.plusWeeks(1), ReminderPolicy.nextScheduledTime(rule, wednesday))
    }

    @Test fun `six days a week excludes Sunday`() {
        val rule = ReminderRule(weekdays = (1..6).toSet())
        assertFalse(ReminderPolicy.isScheduledTime(rule, wednesday.plusDays(4)))
        assertTrue(ReminderPolicy.isScheduledTime(rule, wednesday.plusDays(5)))
    }

    @Test fun `two concurrent checks and stage escalation cannot create same day duplicates`() {
        val sent = ReminderPolicy.afterDelivery(status(300), ReminderStage.BEFORE_RED, null, wednesday)
        assertFalse(ReminderPolicy.shouldNotify(status(300), advanced, sent, wednesday.plusMinutes(15)))
        assertFalse(ReminderPolicy.shouldNotify(status(0), advanced, sent, wednesday.plusHours(5)))
        assertTrue(ReminderPolicy.shouldNotify(status(0), advanced, sent, wednesday.plusDays(1)))
    }

    @Test fun `minimum gap and selected weekdays both constrain repeats`() {
        val rule = advanced.copy(weekdays = setOf(1, 3, 5), yellowRepeatDays = 3)
        val soon = status(900)
        val sent = ReminderPolicy.afterDelivery(soon, ReminderStage.YELLOW, null, wednesday)
        assertFalse(ReminderPolicy.shouldNotify(soon, rule, sent, wednesday.plusDays(2)))
        assertFalse(ReminderPolicy.shouldNotify(soon, rule, sent, wednesday.plusDays(3)))
        assertTrue(ReminderPolicy.shouldNotify(soon, rule, sent, wednesday.plusDays(5)))
    }

    @Test fun `once before red still permits once when red is reached`() {
        val rule = advanced.copy(redMaxOccurrences = 1, redRepeatDays = 30)
        val sent = ReminderPolicy.afterDelivery(status(300), ReminderStage.BEFORE_RED, null, wednesday)
        assertFalse(ReminderPolicy.shouldNotify(status(200), rule, sent, wednesday.plusDays(40)))
        assertTrue(ReminderPolicy.shouldNotify(status(0), rule, sent, wednesday.plusDays(1)))
        val dueSent = ReminderPolicy.afterDelivery(status(0), ReminderStage.RED, sent, wednesday.plusDays(1))
        assertFalse(ReminderPolicy.shouldNotify(status(-100), rule, dueSent, wednesday.plusDays(40)))
    }

    @Test fun `yellow prewarning cap does not suppress reaching yellow`() {
        val rule = advanced.copy(yellowMaxOccurrences = 1)
        val sent = ReminderPolicy.afterDelivery(status(1_500), ReminderStage.BEFORE_YELLOW, null, wednesday)
        assertFalse(ReminderPolicy.shouldNotify(status(1_400), rule, sent, wednesday.plusDays(1)))
        assertTrue(ReminderPolicy.shouldNotify(status(1_000), rule, sent, wednesday.plusDays(1)))
    }

    @Test fun `configured total count includes first delivery`() {
        val rule = advanced.copy(yellowMaxOccurrences = 2)
        val soon = status(900)
        val first = ReminderPolicy.afterDelivery(soon, ReminderStage.YELLOW, null, wednesday)
        assertTrue(ReminderPolicy.shouldNotify(soon, rule, first, wednesday.plusDays(1)))
        val second = ReminderPolicy.afterDelivery(soon, ReminderStage.YELLOW, first, wednesday.plusDays(1))
        assertFalse(ReminderPolicy.shouldNotify(soon, rule, second, wednesday.plusDays(2)))
    }

    @Test fun `completing service starts a new reminder cycle`() {
        val rule = advanced.copy(redMaxOccurrences = 1)
        val sent = ReminderPolicy.afterDelivery(status(0), ReminderStage.RED, null, wednesday)
        val serviced = item.copy(lastDoneKm = 30_000, lastDoneDate = wednesday.toLocalDate())
        assertTrue(ReminderPolicy.shouldNotify(status(0, serviced), rule, sent, wednesday.plusMonths(6)))
        assertNull(ReminderPolicy.stage(status(10_000, serviced), rule))
    }

    @Test fun `each item inherits default unless it has its own complete rule`() {
        val custom = ReminderRule(enabled = false, weekdays = setOf(3))
        val settings = ReminderSettings(itemOverrides = mapOf(1L to custom))
        assertEquals(custom, settings.ruleFor(1))
        assertEquals(ReminderRule(), settings.ruleFor(2))
        assertEquals(custom, settings.copy(defaultRule = advanced).ruleFor(1))
    }

    @Test fun `DST spring gap shifts a missing local time forward and does not deliver early`() {
        val zone = ZoneId.of("Europe/Berlin")
        val before = ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0, zone)
        val rule = ReminderRule(minuteOfDay = 2 * 60 + 30)
        val next = ReminderPolicy.nextScheduledTime(rule, before)!!
        assertEquals("2026-03-29T03:30+02:00[Europe/Berlin]", next.toString())
        assertFalse(ReminderPolicy.isScheduledTime(rule, next.minusMinutes(1)))
        assertTrue(ReminderPolicy.isScheduledTime(rule, next))
    }

    @Test fun `DST repeated local hour delivers only once`() {
        val first = ZonedDateTime.of(2026, 10, 25, 2, 30, 0, 0, ZoneId.of("Europe/Berlin"))
            .withEarlierOffsetAtOverlap()
        val second = first.withLaterOffsetAtOverlap()
        val rule = advanced.copy(minuteOfDay = 150)
        val sent = ReminderPolicy.afterDelivery(status(300), ReminderStage.BEFORE_RED, null, first)
        assertFalse(ReminderPolicy.shouldNotify(status(300), rule, sent, second))
        assertEquals(first.toLocalDate().plusDays(1), ReminderPolicy.nextScheduledTime(rule, first)!!.toLocalDate())
    }

    @Test fun `local daily repeats do not require 24 hours across DST`() {
        val first = ZonedDateTime.of(2026, 3, 28, 9, 0, 0, 0, ZoneId.of("Europe/Berlin"))
        val sent = ReminderPolicy.afterDelivery(status(300), ReminderStage.BEFORE_RED, null, first)
        assertTrue(ReminderPolicy.shouldNotify(status(300), advanced, sent, first.plusDays(1)))
    }

    @Test fun `clock moving backwards cannot deliver an older instant`() {
        val sent = ReminderPolicy.afterDelivery(status(300), ReminderStage.BEFORE_RED, null, wednesday)
        assertFalse(ReminderPolicy.shouldNotify(status(0), advanced, sent, wednesday.minusDays(1)))
    }

    @Test fun `invalid rules are rejected and can never schedule`() {
        val invalid = listOf(
            advanced.copy(weekdays = emptySet()), advanced.copy(weekdays = setOf(0, 8)),
            advanced.copy(minuteOfDay = 1440), advanced.copy(minuteOfDay = -1),
            advanced.copy(redAdvanceKm = -1), advanced.copy(yellowAdvanceDays = Int.MAX_VALUE),
            advanced.copy(yellowRepeatDays = 0), advanced.copy(redMaxOccurrences = -1),
        )
        invalid.forEach {
            assertFalse(it.isValid)
            assertNull(ReminderPolicy.nextScheduledTime(it, wednesday))
            assertFalse(ReminderPolicy.shouldNotify(status(0), it, null, wednesday))
        }
    }

    @Test fun `settings and history survive serialized round trip including item keys`() {
        val settings = ReminderSettings(advanced, mapOf(42L to advanced.copy(weekdays = setOf(3))))
        assertEquals(settings, Json.decodeFromString<ReminderSettings>(Json.encodeToString(settings)))
        val sent = ReminderPolicy.afterDelivery(status(300), ReminderStage.BEFORE_RED, null, wednesday)
        assertEquals(sent, Json.decodeFromString<ReminderHistory>(Json.encodeToString(sent)))
    }
}
