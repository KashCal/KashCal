package org.onekash.kashcal.domain.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.toEventForShareCard
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher

/**
 * Contract test: when [singleOccurrenceForShare] feeds a synthesized event
 * through [IcsPatcher.serialize] — the SAME path used by IcsExporter at
 * runtime — the resulting VCALENDAR body must be a single standalone event
 * with no series-membership data and no leaked-from-master attendee or
 * organizer information.
 *
 * Why IcsPatcher.serialize and not the mapper directly: any event synced
 * from a CalDAV server (iCloud, Nextcloud, Radicale, etc.) carries the
 * server-provided ICS body in `Event.rawIcal`. `IcsExporter.exportEvent`
 * delegates to `IcsPatcher.serialize`, which patches that raw body in
 * place — preserving ATTENDEE, ORGANIZER, and X-* properties from the
 * server. If `singleOccurrenceForShare` doesn't clear `rawIcal`, the
 * recipient who taps the share-card .ics gets every attendee's email and
 * RSVP. Testing only `EventToICalEventMapper.toICalEvent(...)` (as the
 * v23.7.71 version of this test did) bypasses that path entirely and
 * silently passes while the production path leaks PII.
 *
 * Pure JVM — IcsPatcher has no Android dependencies.
 */
class SingleOccurrenceIcsContentTest {

    /** Realistic CalDAV-fetched master event with attendees + organizer. */
    private val recurringMasterWithAttendees: Event = run {
        val rawIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//Mac OS X 10.16//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:America/New_York
            BEGIN:STANDARD
            DTSTART:19701101T020000
            TZOFFSETFROM:-0400
            TZOFFSETTO:-0500
            RRULE:FREQ=YEARLY;BYDAY=1SU;BYMONTH=11
            END:STANDARD
            BEGIN:DAYLIGHT
            DTSTART:19700308T020000
            TZOFFSETFROM:-0500
            TZOFFSETTO:-0400
            RRULE:FREQ=YEARLY;BYDAY=2SU;BYMONTH=3
            END:DAYLIGHT
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:master-uid-original
            DTSTAMP:20240101T000000Z
            CREATED:20240101T000000Z
            DTSTART;TZID=America/New_York:20260531T113000
            DTEND;TZID=America/New_York:20260531T130000
            SUMMARY:Brunch at Sam's
            LOCATION:Sam's Cafe
            RRULE:FREQ=WEEKLY;BYDAY=SA
            ORGANIZER;CN=Alice Sender:mailto:alice.sender@example.com
            ATTENDEE;CN=Alice Sender;PARTSTAT=ACCEPTED;ROLE=CHAIR:mailto:alice.sender@example.com
            ATTENDEE;CN=Bob Friend;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT:mailto:bob.friend@example.com
            ATTENDEE;CN=Carol Coworker;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT:mailto:carol.coworker@example.com
            X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC
            X-APPLE-CREATOR-IDENTITY:com.apple.mobilecal
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        Event(
            id = 42L,
            uid = "master-uid-original",
            calendarId = 1L,
            title = "Brunch at Sam's",
            startTs = 1748706600000L,
            endTs = 1748712000000L,
            timezone = "America/New_York",
            isAllDay = false,
            location = "Sam's Cafe",
            rrule = "FREQ=WEEKLY;BYDAY=SA",
            dtstamp = 1704067200000L, // 2024-01-01
            createdAt = 1704067200000L,
            updatedAt = 1704067200000L,
            rawIcal = rawIcal,
            organizerEmail = "alice.sender@example.com",
            organizerName = "Alice Sender",
            caldavUrl = "https://caldav.icloud.com/12345/calendars/home/master.ics",
            etag = "\"abc123\"",
        )
    }

    /**
     * Extract just the VEVENT body. VTIMEZONE blocks legitimately contain
     * RRULE for DST transitions per RFC 5545 — we only care that the VEVENT
     * itself isn't recurring.
     */
    private fun extractVeventBody(ics: String): String {
        val begin = ics.indexOf("BEGIN:VEVENT")
        val end = ics.indexOf("END:VEVENT", begin)
        require(begin >= 0 && end > begin) { "no VEVENT block in: $ics" }
        return ics.substring(begin, end)
    }

    private fun shareIcs(event: Event): String {
        val occurrence = singleOccurrenceForShare(
            event,
            occurrenceStartTs = 1749311400000L,
            occurrenceEndTs = 1749316800000L,
            nowMs = 1748736000000L, // 2026-05-31T12:00:00Z, deterministic
        )
        return IcsPatcher.serialize(occurrence)
    }

    // =================== Privacy / no-leak ===================

    @Test
    fun `share-card ics does NOT contain attendee emails from master`() {
        val ics = shareIcs(recurringMasterWithAttendees)
        assertFalse(
            "ATTENDEE alice.sender@example.com leaked into share-card",
            ics.contains("alice.sender@example.com")
        )
        assertFalse(
            "ATTENDEE bob.friend@example.com leaked into share-card",
            ics.contains("bob.friend@example.com")
        )
        assertFalse(
            "ATTENDEE carol.coworker@example.com leaked into share-card",
            ics.contains("carol.coworker@example.com")
        )
    }

    @Test
    fun `share-card ics does NOT contain ATTENDEE property`() {
        val ics = shareIcs(recurringMasterWithAttendees)
        assertFalse(
            "share-card .ics must not contain any ATTENDEE lines (PII)",
            ics.contains("ATTENDEE")
        )
    }

    @Test
    fun `share-card ics does NOT contain ORGANIZER property`() {
        val ics = shareIcs(recurringMasterWithAttendees)
        // ORGANIZER on a share-card .ics could trigger iTIP routing on
        // the recipient's calendar. Strip it.
        assertFalse(
            "share-card .ics must not contain ORGANIZER (iTIP-routing risk)",
            ics.contains("ORGANIZER")
        )
    }

    @Test
    fun `share-card ics does NOT contain X- proprietary properties from master`() {
        val ics = shareIcs(recurringMasterWithAttendees)
        assertFalse(
            "X-APPLE-TRAVEL-ADVISORY-BEHAVIOR leaked into share-card",
            ics.contains("X-APPLE-TRAVEL-ADVISORY-BEHAVIOR")
        )
        assertFalse(
            "X-APPLE-CREATOR-IDENTITY leaked into share-card",
            ics.contains("X-APPLE-CREATOR-IDENTITY")
        )
    }

    // =================== Series-membership stripping ===================

    @Test
    fun `share-card VEVENT does NOT contain RRULE`() {
        val ics = shareIcs(recurringMasterWithAttendees)
        val veventBody = extractVeventBody(ics)
        assertFalse("share-card VEVENT must NOT contain RRULE", veventBody.contains("RRULE"))
    }

    @Test
    fun `share-card VEVENT does NOT contain RECURRENCE-ID`() {
        val ics = shareIcs(recurringMasterWithAttendees)
        val veventBody = extractVeventBody(ics)
        assertFalse(
            "share-card VEVENT must NOT contain RECURRENCE-ID",
            veventBody.contains("RECURRENCE-ID")
        )
    }

    @Test
    fun `share-card ics has fresh UID, not master UID`() {
        val ics = shareIcs(recurringMasterWithAttendees)
        assertFalse(
            "master UID must not appear in share-card .ics",
            ics.contains("master-uid-original")
        )
    }

    @Test
    fun `share-card ics is a valid VCALENDAR with exactly one VEVENT`() {
        val ics = shareIcs(recurringMasterWithAttendees)
        assertTrue(ics.contains("BEGIN:VCALENDAR"))
        assertTrue(ics.contains("END:VCALENDAR"))
        val veventCount = "BEGIN:VEVENT".toRegex().findAll(ics).count()
        assertTrue("expected exactly 1 VEVENT, got $veventCount", veventCount == 1)
    }

    // =================== Content preserved ===================

    @Test
    fun `share-card ics preserves title in SUMMARY`() {
        val ics = shareIcs(recurringMasterWithAttendees)
        assertTrue("SUMMARY must contain event title", ics.contains("Brunch at Sam"))
    }

    @Test
    fun `share-card ics preserves location`() {
        val ics = shareIcs(recurringMasterWithAttendees)
        assertTrue("LOCATION must contain location text", ics.contains("Sam's Cafe"))
    }

    // =================== Exception event share ===================

    /** Modified-occurrence (exception) event. */
    private val exceptionEvent: Event = run {
        val rawIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//Mac OS X 10.16//EN
            BEGIN:VTIMEZONE
            TZID:America/New_York
            BEGIN:STANDARD
            DTSTART:19701101T020000
            TZOFFSETFROM:-0400
            TZOFFSETTO:-0500
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:master-uid-original
            DTSTAMP:20240115T000000Z
            DTSTART;TZID=America/New_York:20260607T120000
            DTEND;TZID=America/New_York:20260607T133000
            SUMMARY:Brunch at Sam's (rescheduled)
            RECURRENCE-ID;TZID=America/New_York:20260607T113000
            ORGANIZER;CN=Alice Sender:mailto:alice.sender@example.com
            ATTENDEE;CN=Bob Friend:mailto:bob.friend@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        Event(
            id = 43L,
            uid = "master-uid-original",
            calendarId = 1L,
            title = "Brunch at Sam's (rescheduled)",
            startTs = 1749312000000L,
            endTs = 1749317400000L,
            timezone = "America/New_York",
            isAllDay = false,
            originalEventId = 42L,
            originalInstanceTime = 1749311400000L,
            dtstamp = 1705276800000L,
            rawIcal = rawIcal,
            organizerEmail = "alice.sender@example.com",
            organizerName = "Alice Sender",
        )
    }

    // =================== Multi-day all-day events ===================

    /**
     * Reproduces the v23.7.76 bug: a 4-day all-day event starting May 31
     * 2026 in America/Los_Angeles serializes with wrong dates / collapsed
     * duration in the share-card .ics. The user reported "just sends
     * May 31, no matter which day I click" + "date shows a day before".
     *
     * KashCal stores all-day events as "local midnight in event TZ":
     *   May 31 00:00:00 PDT = 1748674800000 ms epoch
     *   Jun 03 23:59:59.999 PDT = 1749023999999 ms epoch (inclusive end)
     */
    private fun multiDayAllDayEvent(): Event = Event(
        id = 50L,
        uid = "multi-day-allday-uid",
        calendarId = 1L,
        title = "Vacation",
        // 2026-05-31T00:00:00 America/Los_Angeles (UTC-7 PDT)
        startTs = 1780210800000L,
        // 2026-06-03T23:59:59.999 America/Los_Angeles (last day of 4-day inclusive span)
        endTs = 1780556399999L,
        timezone = "America/Los_Angeles",
        isAllDay = true,
        dtstamp = 1780210800000L,
    )

    @Test
    fun `multi-day all-day share emits DTSTART date matching event start day`() {
        val event = multiDayAllDayEvent()
        val occurrence = singleOccurrenceForShare(
            event,
            occurrenceStartTs = event.startTs,
            occurrenceEndTs = event.endTs,
            nowMs = 1748736000000L,
        )
        val ics = IcsPatcher.serialize(occurrence)
        val veventBody = extractVeventBody(ics)

        // DTSTART must be 20260531 (May 31 in event TZ), NOT 20260530.
        // Use VALUE=DATE marker to find the line.
        val dtstartMatch = Regex("""DTSTART(?:;[^:]*)?:(\d{8})""")
            .find(veventBody)
        assertTrue(
            "DTSTART line must exist in VEVENT body: $veventBody",
            dtstartMatch != null
        )
        assertEquals(
            "DTSTART date should be May 31, 2026 (event start in event TZ)",
            "20260531",
            dtstartMatch!!.groupValues[1]
        )
    }

    @Test
    fun `multi-day all-day share emits DTEND date covering full 4-day span`() {
        val event = multiDayAllDayEvent()
        val occurrence = singleOccurrenceForShare(
            event,
            occurrenceStartTs = event.startTs,
            occurrenceEndTs = event.endTs,
            nowMs = 1748736000000L,
        )
        val ics = IcsPatcher.serialize(occurrence)
        val veventBody = extractVeventBody(ics)

        // RFC 5545 DTEND for all-day events is EXCLUSIVE — first day NOT
        // covered. May 31 + 4 days = Jun 4 (exclusive). So the .ics
        // should emit DTEND=20260604 to mean "covers May 31, Jun 1,
        // Jun 2, Jun 3" (4 inclusive days).
        val dtendMatch = Regex("""DTEND(?:;[^:]*)?:(\d{8})""")
            .find(veventBody)
        assertTrue(
            "DTEND line must exist in VEVENT body: $veventBody",
            dtendMatch != null
        )
        assertEquals(
            "DTEND date should be Jun 4, 2026 (RFC 5545 exclusive end of 4-day span)",
            "20260604",
            dtendMatch!!.groupValues[1]
        )
    }

    @Test
    fun `multi-day all-day share preserves duration of more than one day`() {
        val event = multiDayAllDayEvent()
        val occurrence = singleOccurrenceForShare(
            event,
            occurrenceStartTs = event.startTs,
            occurrenceEndTs = event.endTs,
            nowMs = 1748736000000L,
        )
        val ics = IcsPatcher.serialize(occurrence)
        val veventBody = extractVeventBody(ics)

        // The bug: when fromTimestamp uses UTC for an all-day timestamp
        // stored as local-midnight-in-event-TZ, both DTSTART and DTEND
        // can shift by up to a day, sometimes collapsing the visible
        // span to a single day. Verify start and end differ by ≥1 day.
        val dtstart = Regex("""DTSTART(?:;[^:]*)?:(\d{8})""").find(veventBody)?.groupValues?.get(1)
        val dtend = Regex("""DTEND(?:;[^:]*)?:(\d{8})""").find(veventBody)?.groupValues?.get(1)
        assertTrue("Both DTSTART and DTEND must be present: $veventBody", dtstart != null && dtend != null)
        assertTrue(
            "DTEND ($dtend) must be strictly after DTSTART ($dtstart) for a multi-day event",
            dtend!! > dtstart!!
        )
        // 4-day span (May 31..Jun 3 inclusive) → DTEND - DTSTART = 4 days
        val startDay = dtstart.substring(6, 8).toInt()
        val endDay = dtend.substring(6, 8).toInt()
        // Same month (May 31 → Jun 4 crosses months; Jun 4 - May 31 = 4 days difference at month-boundary)
        assertEquals(
            "Day delta between DTSTART (May 31) and DTEND (Jun 4) should be 4 days",
            4,
            (java.time.LocalDate.of(2026, dtend.substring(4, 6).toInt(), endDay).toEpochDay() -
                java.time.LocalDate.of(2026, dtstart.substring(4, 6).toInt(), startDay).toEpochDay()).toInt()
        )
    }

    @Test
    fun `multi-day all-day in east-of-UTC TZ does not drift the start date`() {
        // The "day before" bug: a Sydney user (UTC+10) creates an all-day
        // event May 31; the timestamp is May 31 00:00 AEST = May 30 14:00
        // UTC. The CalDAV mapper's UTC-based DATE serializer would show
        // May 30 unless the helper normalizes correctly.
        val sydney = Event(
            id = 51L,
            uid = "sydney-allday",
            calendarId = 1L,
            title = "Vacation Sydney",
            // 2026-05-31T00:00:00 Australia/Sydney (UTC+10 AEST)
            startTs = 1780149600000L,
            // 2026-06-03T23:59:59.999 Australia/Sydney (4-day inclusive)
            endTs = 1780495199999L,
            timezone = "Australia/Sydney",
            isAllDay = true,
            dtstamp = 1780149600000L,
        )
        val occurrence = singleOccurrenceForShare(
            sydney,
            occurrenceStartTs = sydney.startTs,
            occurrenceEndTs = sydney.endTs,
            nowMs = 1780000000000L,
        )
        val ics = IcsPatcher.serialize(occurrence)
        val veventBody = extractVeventBody(ics)
        val dtstart = Regex("""DTSTART(?:;[^:]*)?:(\d{8})""").find(veventBody)?.groupValues?.get(1)
        val dtend = Regex("""DTEND(?:;[^:]*)?:(\d{8})""").find(veventBody)?.groupValues?.get(1)
        assertEquals("Sydney all-day DTSTART must show May 31, not May 30", "20260531", dtstart)
        assertEquals("Sydney all-day DTEND must show Jun 4 (exclusive of 4-day span)", "20260604", dtend)
    }

    @Test
    fun `sharing a recurrence-exception event also strips ATTENDEE and RECURRENCE-ID`() {
        val occurrence = singleOccurrenceForShare(
            exceptionEvent,
            occurrenceStartTs = exceptionEvent.startTs,
            occurrenceEndTs = exceptionEvent.endTs,
            nowMs = 1748736000000L,
        )
        val ics = IcsPatcher.serialize(occurrence)
        val veventBody = extractVeventBody(ics)
        assertFalse(
            "exception share must not leak ATTENDEE",
            ics.contains("ATTENDEE")
        )
        assertFalse(
            "exception share must not contain RECURRENCE-ID (orphan)",
            veventBody.contains("RECURRENCE-ID")
        )
    }

    // =================== Device-event share-card path ===================
    //
    // Mirrors the runtime device-event flow: DisplayEvent.Device →
    // toEventForShareCard() → singleOccurrenceForShare(...) →
    // IcsPatcher.serialize(...). Confirms the synthetic Event survives
    // the existing pipeline and produces a privacy-clean .ics — same
    // contract as Room events.

    private fun deviceInstance(
        title: String = "Project Sync",
        description: String = "Status review",
        location: String = "HQ Boardroom",
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean = false,
        timezone: String? = "America/New_York",
    ): DeviceCalendarInstance = DeviceCalendarInstance(
        instanceId = 7000L,
        eventId = 8000L,
        title = title,
        description = description,
        location = location,
        startTs = startTs,
        endTs = endTs,
        startDay = 0,
        endDay = 0,
        isAllDay = isAllDay,
        hasRrule = false,
        rrule = null,
        reminders = emptyList(),
        calendarId = 4L,
        calendarDisplayName = "Work",
        calendarColor = 0xFF2196F3.toInt(),
        eventColor = null,
        status = 1,
        availability = 0,
        hasAlarm = false,
        selfAttendeeStatus = 0,
        isWritable = true,
        originalId = null,
        originalInstanceTime = null,
        timezone = timezone,
        eventStartTs = startTs,
    )

    private fun shareDeviceIcs(instance: DeviceCalendarInstance): String {
        val synthetic = DisplayEvent.Device(instance).toEventForShareCard()
        val occurrence = singleOccurrenceForShare(
            synthetic,
            occurrenceStartTs = synthetic.startTs,
            occurrenceEndTs = synthetic.endTs,
            nowMs = 1748736000000L,
        )
        return IcsPatcher.serialize(occurrence)
    }

    @Test
    fun `device-event share emits exactly one VEVENT`() {
        val instance = deviceInstance(
            startTs = 1700100000000L,
            endTs = 1700103600000L,
        )
        val ics = shareDeviceIcs(instance)
        assertTrue(ics.contains("BEGIN:VCALENDAR"))
        assertTrue(ics.contains("END:VCALENDAR"))
        val veventCount = "BEGIN:VEVENT".toRegex().findAll(ics).count()
        assertEquals(1, veventCount)
    }

    @Test
    fun `device-event single-day timed share preserves title and location`() {
        val instance = deviceInstance(
            startTs = 1700100000000L,
            endTs = 1700103600000L,
        )
        val ics = shareDeviceIcs(instance)
        assertTrue("SUMMARY must contain device-event title", ics.contains("Project Sync"))
        assertTrue("LOCATION must contain device-event location", ics.contains("HQ Boardroom"))
    }

    @Test
    fun `device-event share carries no ATTENDEE or ORGANIZER`() {
        // Device events from CalendarProvider can have attendee data on
        // separate Attendees rows; the share-card mapper deliberately
        // does not read those, so nothing should appear in the .ics.
        val instance = deviceInstance(
            startTs = 1700100000000L,
            endTs = 1700103600000L,
        )
        val ics = shareDeviceIcs(instance)
        assertFalse("device-event share must not contain ATTENDEE", ics.contains("ATTENDEE"))
        assertFalse("device-event share must not contain ORGANIZER", ics.contains("ORGANIZER"))
    }

    @Test
    fun `device-event share VEVENT contains no RRULE or RECURRENCE-ID`() {
        val instance = deviceInstance(
            startTs = 1700100000000L,
            endTs = 1700103600000L,
        )
        val ics = shareDeviceIcs(instance)
        val veventBody = extractVeventBody(ics)
        assertFalse("device-event share VEVENT must not contain RRULE", veventBody.contains("RRULE"))
        assertFalse(
            "device-event share VEVENT must not contain RECURRENCE-ID",
            veventBody.contains("RECURRENCE-ID")
        )
    }

    @Test
    fun `device-event single-day all-day share emits matching DTSTART date`() {
        // Android's CalendarProvider stores all-day BEGIN as UTC midnight
        // (Events.EVENT_TIMEZONE is conventionally 'UTC'). Mirroring this
        // real shape catches bugs where the share path reinterprets an
        // all-day timestamp in the wrong zone.
        val instance = deviceInstance(
            title = "Holiday",
            description = "",
            location = "",
            startTs = 1780185600000L, // May 31 2026 00:00 UTC
            endTs = 1780271999999L,   // May 31 2026 23:59:59.999 UTC
            isAllDay = true,
            timezone = "UTC",
        )
        val ics = shareDeviceIcs(instance)
        val veventBody = extractVeventBody(ics)
        val dtstart = Regex("""DTSTART(?:;[^:]*)?:(\d{8})""").find(veventBody)?.groupValues?.get(1)
        assertEquals(
            "DTSTART must be May 31 (event TZ), not the day before",
            "20260531",
            dtstart
        )
    }

    @Test
    fun `device-event multi-day all-day share covers full span`() {
        // 4-day all-day event May 31 2026 → Jun 3 2026 (inclusive),
        // stored in CalendarProvider as UTC-anchored ms.
        val instance = deviceInstance(
            title = "Vacation",
            description = "",
            location = "",
            startTs = 1780185600000L, // May 31 2026 00:00 UTC
            endTs = 1780531199999L,   // Jun 3 2026 23:59:59.999 UTC
            isAllDay = true,
            timezone = "UTC",
        )
        val ics = shareDeviceIcs(instance)
        val veventBody = extractVeventBody(ics)
        val dtstart = Regex("""DTSTART(?:;[^:]*)?:(\d{8})""").find(veventBody)?.groupValues?.get(1)
        val dtend = Regex("""DTEND(?:;[^:]*)?:(\d{8})""").find(veventBody)?.groupValues?.get(1)
        assertEquals("DTSTART must be May 31", "20260531", dtstart)
        assertEquals(
            "DTEND must be Jun 4 (RFC 5545 exclusive end of 4-day span)",
            "20260604",
            dtend
        )
    }

    @Test
    fun `all-day device event with non-UTC EVENT_TIMEZONE does not day-shift`() {
        // Some sync adapters (Outlook/Exchange bridges) write a non-UTC
        // EVENT_TIMEZONE on all-day events even though Android's BEGIN
        // value is always UTC-anchored midnight. Without normalization,
        // normalizeAllDay would reinterpret the UTC ms in America/New_York
        // and emit DTSTART=20260530 instead of 20260531. The mapper
        // forces timezone='UTC' for all-day events to defend against this.
        val instance = deviceInstance(
            title = "Holiday",
            description = "",
            location = "",
            startTs = 1780185600000L, // May 31 2026 00:00 UTC
            endTs = 1780271999999L,   // May 31 2026 23:59:59.999 UTC
            isAllDay = true,
            timezone = "America/New_York", // adapter-written, non-UTC
        )
        val ics = shareDeviceIcs(instance)
        val veventBody = extractVeventBody(ics)
        val dtstart = Regex("""DTSTART(?:;[^:]*)?:(\d{8})""").find(veventBody)?.groupValues?.get(1)
        val dtend = Regex("""DTEND(?:;[^:]*)?:(\d{8})""").find(veventBody)?.groupValues?.get(1)
        assertEquals(
            "DTSTART must be May 31, not the day before",
            "20260531",
            dtstart
        )
        assertEquals(
            "DTEND must be Jun 1 (exclusive of single-day span)",
            "20260601",
            dtend
        )
    }

    @Test
    fun `device-event with non-IANA timezone string emits a UTC DTSTART`() {
        // Some sync adapters write Outlook-style timezone names (e.g.,
        // "Pacific Standard Time") into Events.EVENT_TIMEZONE. The mapper
        // round-trips the string verbatim; the downstream resolveZone
        // returns null for non-IANA values, and ICalDateTime serializes
        // a null-zoned timestamp as a UTC instant (DTSTART:...Z, no TZID).
        // Asserting the UTC suffix and absence of TZID locks down what
        // the recipient actually sees, not just that we didn't crash.
        val instance = deviceInstance(
            startTs = 1700100000000L,
            endTs = 1700103600000L,
            timezone = "Pacific Standard Time",
        )
        val ics = shareDeviceIcs(instance)
        val veventBody = extractVeventBody(ics)
        val dtstartLine = veventBody.lines().firstOrNull { it.startsWith("DTSTART") }
        assertTrue("DTSTART must exist: $veventBody", dtstartLine != null)
        // Non-IANA → no TZID, value ends with Z (UTC instant).
        assertFalse(
            "DTSTART must NOT carry a TZID for non-IANA timezone strings",
            dtstartLine!!.contains("TZID=")
        )
        assertTrue(
            "DTSTART must serialize as UTC instant (suffix Z), was: $dtstartLine",
            dtstartLine.trimEnd().endsWith("Z")
        )
    }

    @Test
    fun `device-event with IANA timezone emits DTSTART with matching TZID`() {
        val instance = deviceInstance(
            startTs = 1700100000000L,
            endTs = 1700103600000L,
            timezone = "America/New_York",
        )
        val ics = shareDeviceIcs(instance)
        val veventBody = extractVeventBody(ics)
        val dtstartLine = veventBody.lines().firstOrNull { it.startsWith("DTSTART") }
        assertTrue("DTSTART must exist: $veventBody", dtstartLine != null)
        assertTrue(
            "DTSTART must carry TZID=America/New_York, was: $dtstartLine",
            dtstartLine!!.contains("TZID=America/New_York")
        )
    }

    @Test
    fun `device-event share has fresh UID, not derived from device id`() {
        val instance = deviceInstance(
            startTs = 1700100000000L,
            endTs = 1700103600000L,
        )
        val ics = shareDeviceIcs(instance)
        // UID line shouldn't be the device eventId or instanceId.
        val uidLine = Regex("""UID:(\S+)""").find(ics)?.groupValues?.get(1)
        assertTrue("UID line must exist", uidLine != null)
        assertFalse("UID must not be the device eventId", uidLine == "8000")
        assertFalse("UID must not be the instanceId", uidLine == "7000")
    }
}
