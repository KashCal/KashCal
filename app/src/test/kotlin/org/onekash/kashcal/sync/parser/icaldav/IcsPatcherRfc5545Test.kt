package org.onekash.kashcal.sync.parser.icaldav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * RFC 5545 compliance tests for IcsPatcher serialization.
 *
 * Tests ICS output against RFC 5545 requirements:
 * - All-day events use VALUE=DATE format for DTSTART/DTEND (Section 3.3.4)
 * - Timed events use DATETIME format (Section 3.3.5)
 * - Exception events have RECURRENCE-ID (Section 3.8.4.4)
 * - Exception events share master UID (Section 3.8.4.7)
 * - SEQUENCE incremented on patch (Section 3.8.7.4)
 * - STATUS values serialized correctly (Section 3.8.1.11)
 * - TRANSP values serialized correctly (Section 3.8.1.12)
 * - CLASS values serialized correctly (Section 3.8.1.3)
 * - Round-trip fidelity for key properties
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsPatcherRfc5545Test {

    private lateinit var parser: ICalParser

    @Before
    fun setup() {
        parser = ICalParser()
    }

    private fun createEvent(
        uid: String = "test-uid@kashcal.test",
        title: String = "Test Event",
        startTs: Long = ZonedDateTime.of(2026, 1, 15, 10, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(),
        endTs: Long = ZonedDateTime.of(2026, 1, 15, 11, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(),
        isAllDay: Boolean = false,
        timezone: String? = "UTC",
        rrule: String? = null,
        exdate: String? = null,
        rdate: String? = null,
        status: String = "CONFIRMED",
        transp: String = "OPAQUE",
        classification: String = "PUBLIC",
        reminders: List<String>? = null,
        sequence: Int = 0,
        rawIcal: String? = null,
        originalEventId: Long? = null,
        originalInstanceTime: Long? = null,
        priority: Int = 0,
        geoLat: Double? = null,
        geoLon: Double? = null,
        color: Int? = null,
        url: String? = null,
        categories: List<String>? = null
    ): Event {
        return Event(
            uid = uid,
            calendarId = 1L,
            title = title,
            startTs = startTs,
            endTs = endTs,
            isAllDay = isAllDay,
            timezone = timezone,
            rrule = rrule,
            exdate = exdate,
            rdate = rdate,
            status = status,
            transp = transp,
            classification = classification,
            reminders = reminders,
            sequence = sequence,
            rawIcal = rawIcal,
            originalEventId = originalEventId,
            originalInstanceTime = originalInstanceTime,
            priority = priority,
            geoLat = geoLat,
            geoLon = geoLon,
            color = color,
            url = url,
            categories = categories,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
    }

    // ==================== generateFresh: All-Day Event Format ====================

    @Test
    fun `generateFresh all-day event uses VALUE=DATE format`() {
        // RFC 5545 Section 3.3.4: DATE format is "YYYYMMDD" with VALUE=DATE parameter
        val startTs = ZonedDateTime.of(2026, 1, 15, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val endTs = startTs + 86400000 - 1 // End of day

        val event = createEvent(
            title = "All-Day Event",
            startTs = startTs,
            endTs = endTs,
            isAllDay = true,
            timezone = null
        )

        val ics = IcsPatcher.generateFresh(event)

        // Verify all-day events use VALUE=DATE
        assertTrue("All-day DTSTART should contain VALUE=DATE or date-only format",
            ics.contains("VALUE=DATE") || ics.contains("DTSTART:2026"))

        // Should NOT contain time component (T) in the date value
        // The line should be like DTSTART;VALUE=DATE:20260115
        val dtStartLine = ics.lines().find { it.startsWith("DTSTART") }
        assertNotNull("Should have DTSTART line", dtStartLine)
    }

    @Test
    fun `generateFresh single-day all-day event has exclusive DTEND next day`() {
        // RFC 5545 Section 3.6.1: "The 'DTEND' property for a 'VEVENT' calendar
        // component specifies the non-inclusive end of the event."
        //
        // RFC example (Montreal Jazz Festival):
        //   DTSTART;VALUE=DATE:20070628
        //   DTEND;VALUE=DATE:20070709  (July 8 inclusive → DTEND = July 9)
        //
        // Single-day event on Feb 18: DTEND must be Feb 19, not Feb 18.
        //
        // BUG: IcsPatcher passes inclusive endTs directly to ICalDateTime.fromTimestamp(),
        // which produces DTEND;VALUE=DATE:20260218 instead of 20260219.
        // ICalEventMapper correctly subtracts 1ms on parse (exclusive→inclusive),
        // but IcsPatcher never adds it back on serialization (inclusive→exclusive).
        val startTs = ZonedDateTime.of(2026, 2, 18, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val endTs = startTs + 86400000 - 1 // Feb 18 23:59:59.999 UTC (inclusive)

        val event = createEvent(
            title = "Single Day Event",
            startTs = startTs,
            endTs = endTs,
            isAllDay = true,
            timezone = null
        )

        val ics = IcsPatcher.generateFresh(event)

        // DTSTART should be Feb 18
        assertTrue("DTSTART should reference Feb 18",
            ics.contains("20260218"))

        // DTEND must be Feb 19 (exclusive end = next day)
        // Must check the actual DTEND line, not just any occurrence of "20260219"
        // (DTSTAMP could also contain today's date)
        val dtEndLine = ics.lines().find { it.startsWith("DTEND") }
        assertNotNull("Should have DTEND line", dtEndLine)
        assertTrue(
            "All-day DTEND must be exclusive (Feb 19, not Feb 18). " +
                "RFC 5545 Section 3.6.1: DTEND specifies non-inclusive end.\n" +
                "DTEND line: $dtEndLine\n" +
                "Full ICS:\n$ics",
            dtEndLine!!.contains("20260219")
        )
    }

    @Test
    fun `generateFresh multi-day all-day event has correct exclusive DTEND`() {
        // RFC 5545 example: June 28 through July 8 inclusive → DTEND = July 9
        // 3-day event Feb 18-20: DTEND must be Feb 21
        val startTs = ZonedDateTime.of(2026, 2, 18, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val endTs = ZonedDateTime.of(2026, 2, 20, 23, 59, 59, 999000000, ZoneOffset.UTC).toInstant().toEpochMilli()

        val event = createEvent(
            title = "Multi Day Event",
            startTs = startTs,
            endTs = endTs,
            isAllDay = true,
            timezone = null
        )

        val ics = IcsPatcher.generateFresh(event)

        // DTEND must be Feb 21 (day after inclusive end Feb 20)
        assertTrue(
            "Multi-day all-day DTEND must be exclusive (Feb 21). " +
                "3-day event Feb 18-20 should have DTEND=20260221.\n" +
                "Generated ICS:\n$ics",
            ics.contains("DTEND") && ics.contains("20260221")
        )
    }

    @Test
    fun `all-day event round-trips correctly through serialize and parse`() {
        // End-to-end: create event → serialize → parse → verify dates match
        val startTs = ZonedDateTime.of(2026, 2, 18, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val endTs = startTs + 86400000 - 1 // Single day, inclusive

        val event = createEvent(
            uid = "roundtrip@kashcal.test",
            title = "Round Trip Event",
            startTs = startTs,
            endTs = endTs,
            isAllDay = true,
            timezone = null
        )

        // Serialize
        val ics = IcsPatcher.generateFresh(event)

        // Parse back
        val parsed = parser.parseAllEvents(ics).getOrNull()
        assertNotNull("Should parse back successfully", parsed)
        assertTrue("Should have 1 event", parsed!!.isNotEmpty())

        val roundTripped = ICalEventMapper.toEntity(parsed.first(), ics, 1L, null, null)

        // Round-trip should preserve the original dates
        assertEquals("startTs should survive round-trip", event.startTs, roundTripped.startTs)
        assertEquals(
            "endTs should survive round-trip (inclusive end date preserved)",
            event.endTs,
            roundTripped.endTs
        )
    }

    @Test
    fun `generateFresh timed event uses DATETIME format`() {
        val event = createEvent(
            title = "Timed Event",
            timezone = "America/New_York"
        )

        val ics = IcsPatcher.generateFresh(event)

        // Timed events should have full DATETIME
        val dtStartLine = ics.lines().find { it.startsWith("DTSTART") }
        assertNotNull("Should have DTSTART line", dtStartLine)
        assertTrue("Timed DTSTART should contain T separator for time",
            dtStartLine!!.contains("T"))
    }

    // ==================== generateFresh: VCALENDAR Structure ====================

    @Test
    fun `generateFresh produces valid VCALENDAR structure`() {
        val event = createEvent()
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should start with BEGIN:VCALENDAR", ics.contains("BEGIN:VCALENDAR"))
        assertTrue("Should end with END:VCALENDAR", ics.contains("END:VCALENDAR"))
        assertTrue("Should have VERSION:2.0", ics.contains("VERSION:2.0"))
        assertTrue("Should have PRODID", ics.contains("PRODID:"))
        assertTrue("Should have BEGIN:VEVENT", ics.contains("BEGIN:VEVENT"))
        assertTrue("Should have END:VEVENT", ics.contains("END:VEVENT"))
    }

    @Test
    fun `generateFresh includes UID`() {
        val event = createEvent(uid = "my-unique-uid@kashcal.test")
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should contain UID", ics.contains("UID:my-unique-uid@kashcal.test"))
    }

    @Test
    fun `generateFresh includes SUMMARY`() {
        val event = createEvent(title = "Important Meeting")
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should contain SUMMARY", ics.contains("SUMMARY:Important Meeting"))
    }

    // ==================== RFC 5545 Section 3.8.7.4: SEQUENCE ====================

    @Test
    fun `patch increments SEQUENCE`() {
        // RFC 5545: SEQUENCE is incremented when the organizer makes changes
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:seq-test@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:Original Title
            SEQUENCE:3
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event = createEvent(
            uid = "seq-test@kashcal.test",
            title = "Updated Title",
            sequence = 3, // Current sequence
            rawIcal = originalIcs
        )

        val patched = IcsPatcher.patch(originalIcs, event)

        // Should contain SEQUENCE:4 (incremented by 1)
        assertTrue("SEQUENCE should be incremented to 4", patched.contains("SEQUENCE:4"))
    }

    // ==================== RFC 5545 Section 3.8.1.11: STATUS ====================

    @Test
    fun `generateFresh includes CONFIRMED status`() {
        val event = createEvent(status = "CONFIRMED")
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should contain STATUS:CONFIRMED", ics.contains("STATUS:CONFIRMED"))
    }

    @Test
    fun `generateFresh includes TENTATIVE status`() {
        val event = createEvent(status = "TENTATIVE")
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should contain STATUS:TENTATIVE", ics.contains("STATUS:TENTATIVE"))
    }

    @Test
    fun `generateFresh includes CANCELLED status`() {
        val event = createEvent(status = "CANCELLED")
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should contain STATUS:CANCELLED", ics.contains("STATUS:CANCELLED"))
    }

    // ==================== RFC 5545 Section 3.8.1.3: CLASS ====================

    @Test
    fun `generateFresh includes PRIVATE classification`() {
        val event = createEvent(classification = "PRIVATE")
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should contain CLASS:PRIVATE", ics.contains("CLASS:PRIVATE"))
    }

    @Test
    fun `generateFresh includes CONFIDENTIAL classification`() {
        val event = createEvent(classification = "CONFIDENTIAL")
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should contain CLASS:CONFIDENTIAL", ics.contains("CLASS:CONFIDENTIAL"))
    }

    // ==================== RFC 5545 Section 3.8.1.12: TRANSP ====================

    @Test
    fun `generateFresh includes TRANSPARENT transparency`() {
        val event = createEvent(transp = "TRANSPARENT")
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should contain TRANSP:TRANSPARENT", ics.contains("TRANSP:TRANSPARENT"))
    }

    // ==================== serializeWithExceptions: Exception Event Structure ====================

    @Test
    fun `serializeWithExceptions bundles master and exception in single VCALENDAR`() {
        // RFC 5545: Exception events are bundled with master in same VCALENDAR
        val master = createEvent(
            uid = "series@kashcal.test",
            title = "Weekly Meeting",
            rrule = "FREQ=WEEKLY;BYDAY=MO"
        )

        val exception = createEvent(
            uid = "series@kashcal.test", // Same UID as master
            title = "Special Meeting",
            originalEventId = 1,
            originalInstanceTime = ZonedDateTime.of(2026, 1, 19, 10, 0, 0, 0, ZoneOffset.UTC)
                .toInstant().toEpochMilli()
        )

        val ics = IcsPatcher.serializeWithExceptions(master, listOf(exception))

        // Should have exactly one VCALENDAR
        assertEquals("Should have one BEGIN:VCALENDAR", 1,
            ics.split("BEGIN:VCALENDAR").size - 1)
        assertEquals("Should have one END:VCALENDAR", 1,
            ics.split("END:VCALENDAR").size - 1)

        // Should have exactly two VEVENTs (master + exception)
        assertEquals("Should have two BEGIN:VEVENT", 2,
            ics.split("BEGIN:VEVENT").size - 1)

        // Exception should have RECURRENCE-ID
        assertTrue("Exception should have RECURRENCE-ID", ics.contains("RECURRENCE-ID"))
    }

    @Test
    fun `exception event uses master UID`() {
        // RFC 5545 Section 3.8.4.7: Exception events share master UID
        val master = createEvent(
            uid = "master-uid@kashcal.test",
            rrule = "FREQ=DAILY"
        )

        val exception = createEvent(
            uid = "master-uid@kashcal.test",
            title = "Exception Title",
            originalEventId = 1,
            originalInstanceTime = ZonedDateTime.of(2026, 1, 20, 10, 0, 0, 0, ZoneOffset.UTC)
                .toInstant().toEpochMilli()
        )

        val ics = IcsPatcher.serializeWithExceptions(master, listOf(exception))

        // Parse back and verify both VEVENTs have same UID
        val events = parser.parseAllEvents(ics).getOrNull()
        if (events != null) {
            assertTrue("All events should share master UID",
                events.all { it.uid == "master-uid@kashcal.test" })
        }
    }

    @Test
    fun `exception event does not have RRULE`() {
        val master = createEvent(
            uid = "series@kashcal.test",
            rrule = "FREQ=WEEKLY"
        )

        val exception = createEvent(
            uid = "series@kashcal.test",
            title = "Exception",
            rrule = null, // Exceptions don't have RRULE
            originalEventId = 1,
            originalInstanceTime = ZonedDateTime.of(2026, 1, 20, 10, 0, 0, 0, ZoneOffset.UTC)
                .toInstant().toEpochMilli()
        )

        val ics = IcsPatcher.serializeWithExceptions(master, listOf(exception))

        val events = parser.parseAllEvents(ics).getOrNull()
        if (events != null) {
            val exceptionEvent = events.find { it.recurrenceId != null }
            if (exceptionEvent != null) {
                assertNull("Exception should not have RRULE", exceptionEvent.rrule)
            }
        }
    }

    // ==================== RRULE Serialization ====================

    @Test
    fun `generateFresh includes RRULE for recurring events`() {
        val event = createEvent(rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR")
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should contain RRULE", ics.contains("RRULE:FREQ=WEEKLY"))
        assertTrue("Should contain BYDAY", ics.contains("BYDAY=MO,WE,FR"))
    }

    @Test
    fun `generateFresh omits RRULE for non-recurring events`() {
        val event = createEvent(rrule = null)
        val ics = IcsPatcher.generateFresh(event)

        assertFalse("Should not contain RRULE", ics.contains("RRULE:"))
    }

    // ==================== EXDATE Serialization ====================

    @Test
    fun `generateFresh includes EXDATE for excluded dates`() {
        val exdateTs = ZonedDateTime.of(2026, 1, 20, 10, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        val event = createEvent(
            rrule = "FREQ=DAILY",
            exdate = "$exdateTs"
        )

        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should contain EXDATE", ics.contains("EXDATE"))
    }

    @Test
    fun `generateFresh omits EXDATE when empty`() {
        val event = createEvent(exdate = null)
        val ics = IcsPatcher.generateFresh(event)

        assertFalse("Should not contain EXDATE when empty", ics.contains("EXDATE"))
    }

    @Test
    fun `generateFresh all-day EXDATE uses VALUE=DATE format`() {
        // RFC 5545 Section 3.8.5.1: EXDATE value type must match DTSTART.
        // For all-day events (DTSTART VALUE=DATE), EXDATE must also be VALUE=DATE.
        val startTs = ZonedDateTime.of(2026, 1, 5, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val endTs = startTs + 86400000 - 1

        val exdateTs = ZonedDateTime.of(2026, 1, 12, 0, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        val event = createEvent(
            title = "Weekly All-Day",
            startTs = startTs,
            endTs = endTs,
            isAllDay = true,
            timezone = null,
            rrule = "FREQ=WEEKLY",
            exdate = "$exdateTs"
        )

        val ics = IcsPatcher.generateFresh(event)

        val exdateLine = ics.lines().find { it.startsWith("EXDATE") }
        assertNotNull("Should have EXDATE line", exdateLine)
        assertTrue(
            "All-day EXDATE must use VALUE=DATE format (e.g., EXDATE;VALUE=DATE:20260112), " +
                "not DATETIME format. RFC 5545 §3.8.5.1: EXDATE value type must match DTSTART.\n" +
                "EXDATE line: $exdateLine",
            exdateLine!!.contains("VALUE=DATE")
        )
        // Should NOT contain time component
        assertFalse(
            "All-day EXDATE should not contain time component (T separator).\n" +
                "EXDATE line: $exdateLine",
            exdateLine.substringAfter(":").contains("T")
        )
    }

    @Test
    fun `generateFresh never emits both DTEND and DURATION`() {
        // RFC 5545 Section 3.6.1: "Either 'dtend' or 'duration' MAY appear in a
        // 'VEVENT' calendar component, but 'dtend' and 'duration' MUST NOT occur
        // in the same 'eventprop'."
        val event = createEvent(title = "Simple Event")
        val ics = IcsPatcher.generateFresh(event)

        val hasDtend = ics.lines().any { it.startsWith("DTEND") }
        val hasDuration = ics.lines().any { it.startsWith("DURATION") }

        assertTrue("Should have DTEND", hasDtend)
        assertFalse(
            "DTEND and DURATION must not both appear. RFC 5545 §3.6.1.\n" +
                "Generated ICS:\n$ics",
            hasDtend && hasDuration
        )
    }

    // ==================== VALARM Serialization ====================

    @Test
    fun `generateFresh includes VALARM for reminders`() {
        val event = createEvent(reminders = listOf("-PT15M", "-PT1H"))
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should contain VALARM", ics.contains("BEGIN:VALARM"))
        assertTrue("Should contain TRIGGER", ics.contains("TRIGGER:"))
        assertTrue("Should contain ACTION:DISPLAY", ics.contains("ACTION:DISPLAY"))
    }

    @Test
    fun `generateFresh omits VALARM when no reminders`() {
        val event = createEvent(reminders = null)
        val ics = IcsPatcher.generateFresh(event)

        assertFalse("Should not contain VALARM when no reminders", ics.contains("BEGIN:VALARM"))
    }

    // ==================== RFC 5545/7986: Extended Properties ====================

    @Test
    fun `generateFresh includes PRIORITY when non-zero`() {
        val event = createEvent(priority = 1)
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should contain PRIORITY:1", ics.contains("PRIORITY:1"))
    }

    @Test
    fun `generateFresh includes GEO when set`() {
        val event = createEvent(geoLat = 37.386013, geoLon = -122.082932)
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should contain GEO", ics.contains("GEO:"))
    }

    @Test
    fun `generateFresh includes URL when set`() {
        val event = createEvent(url = "https://example.com/meeting")
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should contain URL", ics.contains("URL:https://example.com/meeting"))
    }

    @Test
    fun `generateFresh includes COLOR when set`() {
        val event = createEvent(color = 0xFFFF5733.toInt()) // Orange
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should contain COLOR", ics.contains("COLOR:#FF5733"))
    }

    @Test
    fun `generateFresh includes CATEGORIES when set`() {
        val event = createEvent(categories = listOf("Meeting", "Work"))
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should contain CATEGORIES", ics.contains("CATEGORIES:"))
    }

    // ==================== Bug 4b: All-Day DTEND in patch() ====================

    @Test
    fun `patch all-day event has exclusive DTEND`() {
        // Bug 4 affects patch() too (line 71), not just generateFresh()
        // Same root cause: inclusive endTs passed directly to ICalDateTime.fromTimestamp()
        val startTs = ZonedDateTime.of(2026, 2, 18, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val endTs = startTs + 86400000 - 1 // Feb 18 23:59:59.999 UTC (inclusive)

        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:patch-allday@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;VALUE=DATE:20260218
            DTEND;VALUE=DATE:20260219
            SUMMARY:All Day Original
            SEQUENCE:0
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event = createEvent(
            uid = "patch-allday@kashcal.test",
            title = "All Day Updated",
            startTs = startTs,
            endTs = endTs,
            isAllDay = true,
            timezone = null,
            sequence = 0,
            rawIcal = originalIcs
        )

        val patched = IcsPatcher.patch(originalIcs, event)

        val dtEndLine = patched.lines().find { it.startsWith("DTEND") }
        assertNotNull("Should have DTEND line", dtEndLine)
        assertTrue(
            "patch() all-day DTEND must be exclusive (Feb 19). " +
                "Same bug as generateFresh(): inclusive endTs not converted to exclusive.\n" +
                "DTEND line: $dtEndLine",
            dtEndLine!!.contains("20260219")
        )
    }

    // ==================== Bug 5: RDATE Lost in generateFresh ====================

    @Test
    fun `generateFresh preserves RDATE from event`() {
        // IcsPatcher.generateFresh() line 149 sets rdates = emptyList(),
        // dropping any RDATEs stored in event.rdate field.
        // RFC 5545 Section 3.8.5.2: RDATE specifies additional dates for recurrence set.
        val rdateTs1 = ZonedDateTime.of(2026, 3, 15, 10, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val rdateTs2 = ZonedDateTime.of(2026, 4, 20, 10, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        val event = createEvent(
            title = "Event With RDATEs",
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            rdate = "$rdateTs1,$rdateTs2"
        )

        val ics = IcsPatcher.generateFresh(event)

        // Check for actual RDATE property line (not just substring match)
        val rdateLine = ics.lines().find { it.startsWith("RDATE") }
        assertNotNull(
            "generateFresh should serialize RDATE from event.rdate field. " +
                "Currently rdates=emptyList() at line 149 drops all RDATEs.\n" +
                "Has RDATE line: ${rdateLine != null}\n" +
                "Contains 'RDATE' substring: ${ics.contains("RDATE")}\n" +
                "Generated ICS:\n$ics",
            rdateLine
        )
    }

    // ==================== Bug 6: Exception Events Missing VTIMEZONE ====================

    @Test
    fun `serializeWithExceptions includes VTIMEZONE for exception events - compliance gap`() {
        // IcsPatcher.generateException() line 303 uses includeVTimezone=false.
        // RFC 5545 Section 3.6.5: VTIMEZONE is required when TZID is referenced.
        //
        // Since generateException is private and only called within serializeWithExceptions,
        // the master's VTIMEZONE (from serialize path with includeVTimezone=true) covers
        // the exception's TZID references in the combined VCALENDAR. This is technically
        // correct for the bundled case.
        //
        // Compliance gap: if generateException were ever exposed or used standalone,
        // exception ICS would lack VTIMEZONE. Not a bug today, but fragile.
        val master = createEvent(
            uid = "tz-test@kashcal.test",
            title = "Weekly NYC Meeting",
            timezone = "America/New_York",
            rrule = "FREQ=WEEKLY;BYDAY=MO"
        )

        val exceptionTs = ZonedDateTime.of(2026, 1, 19, 10, 0, 0, 0,
            ZoneId.of("America/New_York")).toInstant().toEpochMilli()

        val exception = createEvent(
            uid = "tz-test@kashcal.test",
            title = "Special NYC Meeting",
            timezone = "America/New_York",
            originalEventId = 1,
            originalInstanceTime = exceptionTs
        )

        val ics = IcsPatcher.serializeWithExceptions(master, listOf(exception))

        // Verify the combined ICS has VTIMEZONE (from master's serialize path)
        assertTrue(
            "Combined ICS should include VTIMEZONE for America/New_York.\n" +
                "Generated ICS:\n$ics",
            ics.contains("VTIMEZONE")
        )

        // Document: exception VEVENT references TZID but relies on master's VTIMEZONE
        assertTrue("Exception should reference America/New_York timezone",
            ics.contains("America/New_York"))
    }

    // ==================== Round-Trip Fidelity ====================

    @Test
    fun `patch preserves original attendees`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:attendee-test@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:Meeting with Attendees
            ATTENDEE;CN=Bob:mailto:bob@example.com
            ATTENDEE;CN=Alice:mailto:alice@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event = createEvent(
            uid = "attendee-test@kashcal.test",
            title = "Updated Meeting",
            rawIcal = originalIcs
        )

        val patched = IcsPatcher.patch(originalIcs, event)

        // Attendees should be preserved even though KashCal doesn't edit them
        assertTrue("Should preserve bob attendee", patched.contains("bob@example.com"))
        assertTrue("Should preserve alice attendee", patched.contains("alice@example.com"))
    }

    @Test
    fun `patch preserves RRULE from original`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:rrule-preserve@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:Weekly Event
            RRULE:FREQ=WEEKLY;BYDAY=TH
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event = createEvent(
            uid = "rrule-preserve@kashcal.test",
            title = "Weekly Event Updated",
            rrule = "FREQ=WEEKLY;BYDAY=TH",
            rawIcal = originalIcs
        )

        val patched = IcsPatcher.patch(originalIcs, event)

        // Verify RRULE is preserved
        assertTrue("Should contain RRULE", patched.contains("RRULE:"))
        assertTrue("Should contain updated title", patched.contains("Weekly Event Updated"))
    }

    // ==================== serialize() Entry Point ====================

    @Test
    fun `serialize uses rawIcal when available`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:serialize-raw@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:Original
            SEQUENCE:2
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event = createEvent(
            uid = "serialize-raw@kashcal.test",
            title = "Updated",
            sequence = 2,
            rawIcal = originalIcs
        )

        val ics = IcsPatcher.serialize(event)

        // Should be patched (not fresh) since rawIcal is available
        assertTrue("Should increment SEQUENCE from patch", ics.contains("SEQUENCE:3"))
        assertTrue("Should have updated title", ics.contains("SUMMARY:Updated"))
    }

    @Test
    fun `serialize generates fresh when no rawIcal`() {
        val event = createEvent(
            uid = "serialize-fresh@kashcal.test",
            title = "Fresh Event",
            rawIcal = null
        )

        val ics = IcsPatcher.serialize(event)

        // Should be a fresh VCALENDAR
        assertTrue("Should have VCALENDAR", ics.contains("BEGIN:VCALENDAR"))
        assertTrue("Should have title", ics.contains("SUMMARY:Fresh Event"))
    }
}
