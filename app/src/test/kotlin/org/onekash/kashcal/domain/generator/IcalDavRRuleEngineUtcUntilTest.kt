package org.onekash.kashcal.domain.generator

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * A UTC UNTIL bounds a series at an instant (RFC 5545 section 3.3.10), so
 * which occurrences exist must not depend on the phone's timezone. The app's
 * engine expands Room events (local, CalDAV, iCloud, ICS), so this is about
 * those sources; device-calendar events are expanded by the platform.
 */
class IcalDavRRuleEngineUtcUntilTest {

    private lateinit var savedZone: TimeZone

    @Before
    fun save() {
        savedZone = TimeZone.getDefault()
    }

    @After
    fun restore() = TimeZone.setDefault(savedZone)

    /** Expands a New York series with the phone's timezone set to [zone]. */
    private fun expandInPhoneZone(zone: String, rrule: String, dtstartMs: Long, rangeEndMs: Long): List<Long> {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        return IcalDavRRuleEngine.expandToTimestamps(
            rrule = rrule,
            dtstartMs = dtstartMs,
            rangeStartMs = dtstartMs - DAY,
            rangeEndMs = rangeEndMs,
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )
    }

    /** Tuesdays at 10:00 New York from 2024-03-05; UNTIL is one second before the third one. */
    private fun expandInPhoneZone(zone: String): List<Long> =
        expandInPhoneZone(zone, "FREQ=WEEKLY;BYDAY=TU;UNTIL=20240319T135959Z", MAR_5, MAR_5 + 40 * DAY)

    @Test
    fun `a UTC end date ends the series at the same occurrence on a phone in Los Angeles`() {
        assertEquals(listOf(MAR_5, MAR_12), expandInPhoneZone("America/Los_Angeles"))
    }

    @Test
    fun `a UTC end date ends the series at the same occurrence on a phone in Berlin`() {
        assertEquals(listOf(MAR_5, MAR_12), expandInPhoneZone("Europe/Berlin"))
    }

    @Test
    fun `a UTC end date ends the series at the same occurrence on a phone in UTC`() {
        assertEquals(listOf(MAR_5, MAR_12), expandInPhoneZone("UTC"))
    }

    /**
     * RFC 5545 section 3.8.5.3, "Every day in January, for 3 years":
     * UNTIL=20000131T140000Z is 09:00 EST on Jan 31 2000, the last occurrence.
     */
    private fun everyDayInJanuary(zone: String): List<Long> = expandInPhoneZone(
        zone,
        "FREQ=DAILY;UNTIL=20000131T140000Z;BYMONTH=1",
        newYork(1998, 1, 1, hour = 9),
        newYork(2000, 2, 1, hour = 9),
    )

    private fun newYork(year: Int, month: Int, day: Int, hour: Int) =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli()

    @Test
    fun `the RFC every day in January example ends on Jan 31 2000 on phones in Los Angeles, UTC and Tokyo`() {
        for (zone in listOf("America/Los_Angeles", "UTC", "Asia/Tokyo")) {
            val result = everyDayInJanuary(zone)
            assertEquals(zone, 93, result.size)
            assertEquals(zone, newYork(2000, 1, 31, hour = 9), result.last())
        }
    }

    private companion object {
        const val DAY = 86_400_000L
        const val MAR_5 = 1_709_650_800_000L // 15:00Z
        const val MAR_12 = 1_710_252_000_000L // 14:00Z (after the US clock change)
    }
}
