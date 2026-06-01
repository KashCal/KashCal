package org.onekash.kashcal.domain.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event

/**
 * Pure-JVM tests for [singleOccurrenceForShare]. The helper turns a master
 * event + the user-tapped occurrence's start/end timestamps into a synthetic
 * single-occurrence Event suitable for ICS export. The recipient should get
 * one standalone calendar entry — not the full recurring series.
 */
class SingleOccurrenceForShareTest {

    private val masterEvent = Event(
        id = 42L,
        uid = "master-uid-original",
        calendarId = 1L,
        title = "Brunch at Sam's",
        startTs = 1748736000000L, // master starts here
        endTs = 1748741400000L,
        timezone = "America/New_York",
        isAllDay = false,
        location = "Sam's Café",
        rrule = "FREQ=WEEKLY;BYDAY=SA",
        dtstamp = 1748736000000L,
    )

    @Test
    fun `single-occurrence event has the supplied occurrence timestamps`() {
        val occurrenceStart = 1749340800000L  // a different week's Saturday
        val occurrenceEnd = occurrenceStart + (masterEvent.endTs - masterEvent.startTs)

        val out = singleOccurrenceForShare(masterEvent, occurrenceStart, occurrenceEnd)

        assertEquals(occurrenceStart, out.startTs)
        assertEquals(occurrenceEnd, out.endTs)
    }

    @Test
    fun `single-occurrence strips the RRULE`() {
        val out = singleOccurrenceForShare(masterEvent, 1749340800000L, 1749346200000L)
        assertNull(out.rrule)
    }

    @Test
    fun `single-occurrence strips the originalEventId and originalInstanceTime`() {
        // Even if the input is an exception event with an originalEventId,
        // the share-card export should be a standalone event.
        val exceptionEvent = masterEvent.copy(
            id = 43L,
            originalEventId = 42L,
            originalInstanceTime = 1749340800000L,
        )
        val out = singleOccurrenceForShare(exceptionEvent, 1749340800000L, 1749346200000L)

        assertNull(out.originalEventId)
        assertNull(out.originalInstanceTime)
    }

    @Test
    fun `single-occurrence assigns a fresh UID distinct from the master`() {
        val out = singleOccurrenceForShare(masterEvent, 1749340800000L, 1749346200000L)
        assertNotEquals(masterEvent.uid, out.uid)
        assertNotNull(out.uid)
        assert(out.uid.isNotBlank())
    }

    @Test
    fun `single-occurrence preserves title, location, timezone, and isAllDay`() {
        val out = singleOccurrenceForShare(masterEvent, 1749340800000L, 1749346200000L)
        assertEquals(masterEvent.title, out.title)
        assertEquals(masterEvent.location, out.location)
        assertEquals(masterEvent.timezone, out.timezone)
        assertEquals(masterEvent.isAllDay, out.isAllDay)
    }

    @Test
    fun `non-recurring event also works — occurrence timestamps just match the input`() {
        val plain = masterEvent.copy(rrule = null, originalEventId = null)
        val out = singleOccurrenceForShare(plain, plain.startTs, plain.endTs)

        assertEquals(plain.startTs, out.startTs)
        assertEquals(plain.endTs, out.endTs)
        assertNull(out.rrule)
    }

    @Test
    fun `id is reset to 0 so the ICS treats it as a standalone insert`() {
        val out = singleOccurrenceForShare(masterEvent, 1749340800000L, 1749346200000L)
        assertEquals(0L, out.id)
    }

    @Test
    fun `rawIcal is cleared so IcsExporter takes the generateFresh path`() {
        // Without this, IcsPatcher.patch would preserve ATTENDEE / ORGANIZER /
        // X-* lines from the server's original ICS body and leak them to the
        // share-card recipient.
        val withRawIcal = masterEvent.copy(rawIcal = "BEGIN:VCALENDAR\r\n...END:VCALENDAR\r\n")
        val out = singleOccurrenceForShare(withRawIcal, withRawIcal.startTs, withRawIcal.endTs)
        assertNull(out.rawIcal)
    }

    @Test
    fun `organizer fields are cleared`() {
        val withOrganizer = masterEvent.copy(
            organizerEmail = "alice@example.com",
            organizerName = "Alice",
            organizerSentBy = "delegate@example.com",
            organizerScheduleStatus = "1.0",
        )
        val out = singleOccurrenceForShare(withOrganizer, withOrganizer.startTs, withOrganizer.endTs)
        assertNull(out.organizerEmail)
        assertNull(out.organizerName)
        assertNull(out.organizerSentBy)
        assertNull(out.organizerScheduleStatus)
    }

    @Test
    fun `extraProperties etag and caldavUrl are cleared`() {
        val withServerState = masterEvent.copy(
            extraProperties = mapOf("X-APPLE-CREATOR-IDENTITY" to "com.apple.mobilecal"),
            etag = "\"abc123\"",
            caldavUrl = "https://caldav.icloud.com/.../master.ics",
        )
        val out = singleOccurrenceForShare(withServerState, withServerState.startTs, withServerState.endTs)
        assertNull(out.extraProperties)
        assertNull(out.etag)
        assertNull(out.caldavUrl)
    }

    @Test
    fun `dtstamp createdAt updatedAt are stamped to nowMs per RFC 5545`() {
        val nowMs = 1748736000000L
        val out = singleOccurrenceForShare(
            masterEvent,
            occurrenceStartTs = 1749340800000L,
            occurrenceEndTs = 1749346200000L,
            nowMs = nowMs,
        )
        assertEquals(nowMs, out.dtstamp)
        assertEquals(nowMs, out.createdAt)
        assertEquals(nowMs, out.updatedAt)
    }
}
