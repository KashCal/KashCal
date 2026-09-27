package org.onekash.kashcal.regression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.domain.generator.IcalDavRRuleEngine
import org.onekash.kashcal.domain.rrule.RruleBuilder
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pins issue #214: a biweekly rule whose days include Sunday gives the wrong occurrences when
 * DTSTART falls on a Sunday or WKST otherwise doesn't match the user's week start.
 *
 * The expansion tests build the rule the way the picker does for a new Custom weekly rule
 * ([RruleBuilder.weekly] with the user's week start as `wkst`, then [RruleBuilder.withCount])
 * and expand it with [IcalDavRRuleEngine.expandToTimestamps]. Sun/Tue/Thu every two weeks gives
 * the Sunday-first pattern for a Sunday-first user, from a Sunday or a mid-week DTSTART, and the
 * Monday-first pattern for a Monday-first user.
 *
 * The last test parses a rule carrying WKST=SA, rebuilds it with an explicit WKST=SU and checks
 * only that the result has WKST=SU and not WKST=SA. The picker keeps an inbound WKST on save
 * (`RecurrencePickerSelections.parsedWkst`); this test doesn't go through the picker.
 *
 * https://github.com/KashCal/KashCal/issues/214
 */
class Issue214WkstIntegrationTest {

    private val ETZ: ZoneId = ZoneId.of("America/New_York")

    /** 9:00 AM Eastern on the given date as epoch ms. */
    private fun et9(y: Int, m: Int, d: Int): Long =
        ZonedDateTime.of(y, m, d, 9, 0, 0, 0, ETZ).toInstant().toEpochMilli()

    @Test
    fun `issue 214 headline — Sunday-first user, DTSTART=Sun May 4 2025, biweekly Sun_Tue_Thu`() {
        // Builds FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH,SU;WKST=SU;COUNT=6.
        val days = setOf(DayOfWeek.SUNDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)
        val base = RruleBuilder.weekly(interval = 2, days = days, wkst = DayOfWeek.SUNDAY)
        val rrule = RruleBuilder.withCount(base, 6)

        val result = IcalDavRRuleEngine.expandToTimestamps(
            rrule = rrule,
            dtstartMs = et9(2025, 5, 4), // Sun May 4 2025
            rangeStartMs = et9(2025, 5, 1),
            rangeEndMs = et9(2025, 6, 1),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )

        assertEquals(
            listOf(
                et9(2025, 5, 4),   // Sun (week 1)
                et9(2025, 5, 6),   // Tue (week 1)
                et9(2025, 5, 8),   // Thu (week 1)
                et9(2025, 5, 18),  // Sun (week 3)
                et9(2025, 5, 20),  // Tue (week 3)
                et9(2025, 5, 22),  // Thu (week 3)
            ),
            result,
        )
    }

    @Test
    fun `Sunday-first user with mid-week DTSTART=Tue Apr 29 2025, biweekly Sun_Tue_Thu`() {
        // A DTSTART off the WKST boundary still anchors weeks by WKST=SU. The week holding
        // Apr 29 is Sun Apr 27 to Sat May 3; Apr 27 is before DTSTART, so that Sunday is left
        // out and COUNT=6 reaches into week 5.
        val days = setOf(DayOfWeek.SUNDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)
        val base = RruleBuilder.weekly(interval = 2, days = days, wkst = DayOfWeek.SUNDAY)
        val rrule = RruleBuilder.withCount(base, 6)

        val result = IcalDavRRuleEngine.expandToTimestamps(
            rrule = rrule,
            dtstartMs = et9(2025, 4, 29), // Tue Apr 29 2025
            rangeStartMs = et9(2025, 4, 1),
            rangeEndMs = et9(2025, 6, 1),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )

        assertEquals(
            listOf(
                et9(2025, 4, 29),  // Tue (week 1; Sun Apr 27 was before DTSTART)
                et9(2025, 5, 1),   // Thu (week 1)
                et9(2025, 5, 11),  // Sun (week 3)
                et9(2025, 5, 13),  // Tue (week 3)
                et9(2025, 5, 15),  // Thu (week 3)
                et9(2025, 5, 25),  // Sun (week 5; COUNT reached)
            ),
            result,
        )
    }

    @Test
    fun `Monday-first European user, DTSTART=Tue May 6 2025, biweekly Tue_Thu_Sun`() {
        val days = setOf(DayOfWeek.SUNDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)
        val base = RruleBuilder.weekly(interval = 2, days = days, wkst = DayOfWeek.MONDAY)
        val rrule = RruleBuilder.withCount(base, 6)

        val result = IcalDavRRuleEngine.expandToTimestamps(
            rrule = rrule,
            dtstartMs = et9(2025, 5, 6), // Tue May 6 2025
            rangeStartMs = et9(2025, 5, 1),
            rangeEndMs = et9(2025, 6, 1),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )

        assertEquals(
            listOf(
                et9(2025, 5, 6),   // Tue (week 1, Mon-Sun)
                et9(2025, 5, 8),   // Thu (week 1)
                et9(2025, 5, 11),  // Sun (week 1, end of MO-anchored week)
                et9(2025, 5, 20),  // Tue (week 3)
                et9(2025, 5, 22),  // Thu (week 3)
                et9(2025, 5, 25),  // Sun (week 3)
            ),
            result,
        )
    }

    @Test
    fun `round-trip — third-party WKST=SA RRULE rebuilt with user's WKST=SU drops foreign WKST`() {
        // A rule with WKST=SA, as a CalDAV server might send it. parseRrule keeps WKST=SA in
        // ParsedRecurrence.wkst, but the rebuild below passes WKST=SU explicitly, so only
        // RruleBuilder.weekly's use of the wkst it is given is checked.
        val parsed = RruleBuilder.parseRrule(
            "FREQ=WEEKLY;INTERVAL=2;BYDAY=SU,TU,TH;WKST=SA",
            defaultWeekday = DayOfWeek.SUNDAY,
            defaultDayOfMonth = 1,
            defaultOrdinal = 1,
        )
        val rebuilt = RruleBuilder.weekly(
            interval = parsed.interval,
            days = parsed.weekdays,
            wkst = DayOfWeek.SUNDAY,
        )
        assertTrue("rebuilt RRULE should contain WKST=SU: $rebuilt", rebuilt.contains(";WKST=SU"))
        assertTrue("rebuilt RRULE must NOT carry foreign WKST=SA: $rebuilt", !rebuilt.contains(";WKST=SA"))
    }
}
