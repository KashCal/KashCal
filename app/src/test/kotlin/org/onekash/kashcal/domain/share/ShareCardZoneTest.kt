package org.onekash.kashcal.domain.share

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/**
 * Contract: the share-card preview chooses the same zone the .ics
 * generator chooses, so the rendered date chip and the recipient's
 * .ics agree on the calendar day.
 *
 * For all-day events, that zone is always UTC — regardless of what
 * Event.timezone holds. ICS / CalDAV imports store all-day startTs
 * as UTC midnight with a null timezone (per ICalDateTime.parse), and
 * device-event all-day rows are also UTC-anchored ms (per Android's
 * CalendarProvider convention). Reading either through the user's
 * system zone shifts the displayed date by a day for users in zones
 * west of UTC.
 *
 * For timed events, the zone is the event's own IANA zone — falling
 * back to system default for null / blank / non-IANA values, matching
 * the existing inline behavior in MainActivity.
 */
class ShareCardZoneTest {

    @Test
    fun `all-day with null timezone resolves to UTC, not system default`() {
        // ICS-imported all-day events: timezone=null, startTs=UTC midnight.
        // Falling back to system default would day-shift in zones west of UTC.
        assertEquals(ZoneId.of("UTC"), shareCardZone(timezone = null, isAllDay = true))
    }

    @Test
    fun `all-day with empty timezone resolves to UTC`() {
        assertEquals(ZoneId.of("UTC"), shareCardZone(timezone = "", isAllDay = true))
    }

    @Test
    fun `all-day with non-UTC IANA timezone resolves to UTC anyway`() {
        // Some sync adapters write a non-UTC EVENT_TIMEZONE on UTC-anchored
        // all-day rows. The stored ms is still UTC midnight, so the chip
        // must read it as UTC — never as the event's claimed zone.
        assertEquals(
            ZoneId.of("UTC"),
            shareCardZone(timezone = "America/New_York", isAllDay = true),
        )
    }

    @Test
    fun `all-day with UTC timezone resolves to UTC`() {
        assertEquals(ZoneId.of("UTC"), shareCardZone(timezone = "UTC", isAllDay = true))
    }

    @Test
    fun `timed event with IANA timezone resolves to that zone`() {
        assertEquals(
            ZoneId.of("America/New_York"),
            shareCardZone(timezone = "America/New_York", isAllDay = false),
        )
    }

    @Test
    fun `timed event with non-IANA timezone falls back to system default`() {
        // "Pacific Standard Time" is an Outlook-style label; ZoneId.of rejects it.
        // Match the existing inline behavior: fall back to systemDefault rather
        // than crash. We can't pin "system default" without knowing the test
        // host's zone, so just assert it's NOT the rejected string and is a
        // valid ZoneId — i.e., didn't throw and didn't pick UTC by accident.
        val resolved = shareCardZone(timezone = "Pacific Standard Time", isAllDay = false)
        assertEquals(ZoneId.systemDefault(), resolved)
    }

    @Test
    fun `timed event with null timezone falls back to system default`() {
        assertEquals(ZoneId.systemDefault(), shareCardZone(timezone = null, isAllDay = false))
    }

    @Test
    fun `timed event with blank timezone falls back to system default`() {
        assertEquals(ZoneId.systemDefault(), shareCardZone(timezone = "", isAllDay = false))
    }
}
