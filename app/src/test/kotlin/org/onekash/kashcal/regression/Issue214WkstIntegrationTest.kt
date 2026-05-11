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
 * End-to-end pin for issue #214 — biweekly recurrences with day-of-week sets
 * that include Sunday produce wrong occurrences when DTSTART falls on Sunday
 * (or when WKST is otherwise mis-anchored relative to the user's locale).
 *
 * These tests drive the full path the picker uses: RruleBuilder.weekly with
 * the user's first-day-of-week-derived `wkst` -> RruleBuilder.withCount ->
 * IcalDavRRuleEngine.expandToTimestamps. They verify that picking Sun/Tue/Thu
 * + Fortnightly under a Sunday-first user produces the user-expected pattern,
 * and that the same picker under a Monday-first user produces the natural
 * Monday-first pattern.
 *
 * The 4th test pins the locked round-trip decision: third-party RRULEs with
 * a foreign WKST (e.g. WKST=SA from a CalDAV server) get re-emitted with the
 * KashCal user's setting on save, dropping the foreign WKST.
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
        // Picker emits: FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH,SU;WKST=SU;COUNT=6
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
        // Regression guard: DTSTART not at the WKST boundary still anchors weeks
        // by WKST=SU. Week containing Apr 29 is [Sun Apr 27..Sat May 3]; Apr 27
        // < DTSTART so its Sun is dropped. COUNT=6 stretches into week 5.
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
        // A CalDAV server sends FREQ=WEEKLY;INTERVAL=2;BYDAY=SU,TU,TH;WKST=SA.
        // KashCal opens the event for editing; parseRrule extracts FREQ/INTERVAL/BYDAY
        // but deliberately does NOT carry WKST into ParsedRecurrence (locked decision —
        // user's setting wins on save). The picker rebuilds via weekly(2, days,
        // wkst=user's setting). This test pins that the foreign WKST=SA is gone and
        // the user's WKST=SU is what gets emitted.
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
