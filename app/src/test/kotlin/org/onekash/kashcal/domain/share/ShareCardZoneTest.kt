package org.onekash.kashcal.domain.share

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/**
 * Tests [shareCardZone], the zone the share-card preview reads an event's times in.
 *
 * For all-day events it is always UTC, whatever Event.timezone holds, so the date chip and the
 * recipient's .ics agree on the calendar day. ICS and CalDAV imports store an all-day startTs as
 * UTC midnight with a null timezone (per ICalDateTime.parse), and device-event all-day rows are
 * also UTC-anchored ms (per Android's CalendarProvider convention). Reading either in the user's
 * system zone shows the previous day for users west of UTC.
 *
 * For timed events it is the event's own IANA zone, falling back to the system default for a
 * null, blank or non-IANA value.
 */
class ShareCardZoneTest {

    @Test
    fun `all-day with null timezone resolves to UTC, not system default`() {
        // ICS-imported all-day events: timezone=null, startTs=UTC midnight. Falling back to
        // the system default would shift the day in zones west of UTC.
        assertEquals(ZoneId.of("UTC"), shareCardZone(timezone = null, isAllDay = true))
    }

    @Test
    fun `all-day with empty timezone resolves to UTC`() {
        assertEquals(ZoneId.of("UTC"), shareCardZone(timezone = "", isAllDay = true))
    }

    @Test
    fun `all-day with non-UTC IANA timezone resolves to UTC anyway`() {
        // Some sync adapters write a non-UTC EVENT_TIMEZONE on UTC-anchored all-day rows. The
        // stored ms is still UTC midnight, so the chip must read it as UTC, never as the
        // event's claimed zone.
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
        // "Pacific Standard Time" is a Windows-style zone name that ZoneId.of rejects; the
        // zone falls back to the system default instead of crashing.
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
