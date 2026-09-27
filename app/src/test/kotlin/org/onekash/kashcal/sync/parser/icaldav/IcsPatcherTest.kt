package org.onekash.kashcal.sync.parser.icaldav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [IcsPatcher]: what patching a stored server body keeps and changes, fresh generation,
 * [RawIcsParser], series with exceptions, and invite replies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsPatcherTest {

    private lateinit var parser: ICalParser

    @Before
    fun setup() {
        parser = ICalParser()
    }

    // ========== Patch Tests - Preserve Original Properties ==========

    @Test
    fun `patch preserves genuinely-hidden alarms beyond MAX_DISPLAYED but honors deletions within it`() {
        // Original ICS with 6 alarms. The first 5 (indices 0-4) are the displayed set the user
        // sees in the form; index 5 (-P1W) is hidden, never shown. The user's reminders keep
        // only the first 3: indices 3-4 were displayed and deleted, so they must not survive,
        // and hidden index 5 must.
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:multi-alarm@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Original Title
            BEGIN:VALARM
            UID:alarm-0
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min
            END:VALARM
            BEGIN:VALARM
            UID:alarm-1
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:30 min
            END:VALARM
            BEGIN:VALARM
            UID:alarm-2
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:1 hour
            END:VALARM
            BEGIN:VALARM
            UID:alarm-3
            ACTION:DISPLAY
            TRIGGER:-PT2H
            DESCRIPTION:2 hour
            END:VALARM
            BEGIN:VALARM
            UID:alarm-4
            ACTION:DISPLAY
            TRIGGER:-PT3H
            DESCRIPTION:3 hour
            END:VALARM
            BEGIN:VALARM
            UID:alarm-5
            ACTION:DISPLAY
            TRIGGER:-P1W
            DESCRIPTION:1 week
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val originalEvent = parser.parseAllEvents(originalIcs).getOrNull()!!.first()

        // User kept only the first 3 displayed reminders, deleting displayed indices 3-4
        val entity = createTestEvent(
            uid = "multi-alarm@kashcal.test",
            title = "Updated Title",
            startTs = originalEvent.dtStart.timestamp,
            endTs = originalEvent.effectiveEnd().timestamp,
            reminders = listOf("-PT15M", "-PT30M", "-PT1H")
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvent = parser.parseAllEvents(patched).getOrNull()!!.first()

        assertEquals("Updated Title", patchedEvent.summary)

        // 3 kept displayed + 1 hidden (index 5) = 4; deleted displayed indices 3-4 dropped.
        val triggers = patchedEvent.alarms.mapNotNull { it.trigger?.let { d -> org.onekash.icaldav.model.ICalAlarm.formatDuration(d) } }
        assertEquals("Kept 3 displayed + 1 hidden = 4 alarms", 4, patchedEvent.alarms.size)
        assertTrue("displayed -PT15M kept", triggers.contains("-PT15M"))
        assertTrue("displayed -PT30M kept", triggers.contains("-PT30M"))
        assertTrue("displayed -PT1H kept", triggers.contains("-PT1H"))
        assertFalse("deleted displayed -PT2H dropped", triggers.contains("-PT2H"))
        assertFalse("deleted displayed -PT3H dropped", triggers.contains("-PT3H"))
        // -P1W normalizes to -P7D via DurationUtils.format (same instant).
        assertTrue("hidden 1-week (index 5) preserved", triggers.contains("-P7D"))
    }

    @Test
    fun `patch preserves an END-relative alarm through a START-relative reminder edit`() {
        // One START-relative alarm (-PT15M, shown in the form) and one END-relative alarm
        // (5 min before end). The reminders list holds only START offsets, since the pull path
        // doesn't surface END-relative alarms, so an unrelated title edit must not erase the
        // END-relative alarm from the server copy.
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:end-relative-edit@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Original Title
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min before start
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER;RELATED=END:-PT5M
            DESCRIPTION:5 min before end
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = createTestEvent(
            uid = "end-relative-edit@kashcal.test",
            title = "Renamed Title",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = listOf("-PT15M")
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvent = parser.parseAllEvents(patched).getOrNull()!!.first()

        assertEquals("Renamed Title", patchedEvent.summary)
        assertTrue(
            "END-relative alarm must survive a cosmetic edit",
            patchedEvent.alarms.any { it.triggerRelatedToEnd }
        )
        assertTrue(
            "START-relative reminder must survive too",
            patchedEvent.alarms.any { !it.triggerRelatedToEnd && it.trigger?.toMinutes() == -15L }
        )
    }

    @Test
    fun `patch preserves an END-relative alarm when there are no displayed reminders`() {
        // The event's only alarm is END-relative, so the pull path stored no reminders
        // (reminders == null). A save must not silently wipe it: the null-reminders branch
        // clears every START-relative alarm but keeps the never-shown END-relative ones.
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:end-relative-only@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Meeting
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER;RELATED=END:-PT10M
            DESCRIPTION:10 min before end
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = createTestEvent(
            uid = "end-relative-only@kashcal.test",
            title = "Meeting",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = null
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvent = parser.parseAllEvents(patched).getOrNull()!!.first()

        assertEquals("END-relative alarm must not be erased", 1, patchedEvent.alarms.size)
        assertTrue(
            "preserved alarm must still be END-relative",
            patchedEvent.alarms.first().triggerRelatedToEnd
        )
    }

    @Test
    fun `patch does not turn a START reminder into an END-relative alarm on a shared offset`() {
        // Reminders are matched to original alarms by trigger value, so if END-relative alarms
        // took part, a START reminder of -15m could consume an END-relative -15m alarm and be
        // re-emitted as END-relative, firing 15m before the end instead of the start.
        // mergeAlarms partitions END-relative alarms out of the reconciliation.
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:shared-offset@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Meeting
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:30 min before start
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER;RELATED=END:-PT15M
            DESCRIPTION:15 min before end
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // User keeps the -30m start reminder and adds a -15m START reminder.
        val entity = createTestEvent(
            uid = "shared-offset@kashcal.test",
            title = "Meeting",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = listOf("-PT30M", "-PT15M")
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvent = parser.parseAllEvents(patched).getOrNull()!!.first()

        assertTrue(
            "user's -15m must be a START-relative reminder",
            patchedEvent.alarms.any { !it.triggerRelatedToEnd && it.trigger?.toMinutes() == -15L }
        )
        assertTrue(
            "original END-relative -15m must survive as END-relative",
            patchedEvent.alarms.any { it.triggerRelatedToEnd && it.trigger?.toMinutes() == -15L }
        )
    }

    @Test
    fun `patch normalizes a non-NONE absolute-trigger alarm to a single relative alarm`() {
        // A non-NONE absolute-trigger VALARM in the displayed window. The pull path converts
        // absolute triggers to relative offsets (instant - dtStart) in event.reminders, so the
        // entity carries a relative string for it. The patch must emit one relative alarm,
        // not keep the absolute one verbatim and append a relative twin.
        // DTSTART 10:00Z, trigger 09:00Z = -PT1H.
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Server//Test//EN
            BEGIN:VEVENT
            UID:abs-trigger@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Abs Trigger Event
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:Reminder
            TRIGGER;VALUE=DATE-TIME:20251225T090000Z
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val originalEvent = parser.parseAllEvents(originalIcs).getOrNull()!!.first()
        // Pull path converts the absolute trigger to -PT1H; that's what the entity stores.
        val entity = createTestEvent(
            uid = "abs-trigger@kashcal.test",
            title = "Abs Trigger Event",
            startTs = originalEvent.dtStart.timestamp,
            endTs = originalEvent.effectiveEnd().timestamp,
            reminders = listOf("-PT1H")
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvent = parser.parseAllEvents(patched).getOrNull()!!.first()

        // Exactly one alarm, relative, no leftover absolute trigger, no duplicate.
        assertEquals("Single normalized alarm (no verbatim-abs + relative-twin)", 1, patchedEvent.alarms.size)
        val alarm = patchedEvent.alarms.first()
        assertNull("Absolute trigger cleared on overwrite", alarm.triggerAbsolute)
        assertEquals("Trigger is the relative offset", -60L, alarm.trigger?.toMinutes())
        assertFalse("No absolute DATE-TIME trigger in output ICS", patched.contains("VALUE=DATE-TIME"))
    }

    @Test
    fun `patch drops ACTION_NONE sentinel and deleted displayed alarm (real iCloud case)`() {
        // Real-world shape from an iCloud 'test alert' event: 3 DISPLAY alarms and Apple's
        // ACTION:NONE sentinel (1976 absolute trigger). The user kept 2 reminders, deleting
        // the 1-week -P6DT15H. Expected PUT: exactly 2 VALARMs, no NONE or phantom.
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iPhone OS 26.1//EN
            BEGIN:VEVENT
            UID:test-alert@kashcal.test
            DTSTAMP:20260603T120000Z
            DTSTART;VALUE=DATE:20260605
            DTEND;VALUE=DATE:20260606
            SUMMARY:test alert
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:Reminder
            TRIGGER:PT9H
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:Reminder
            TRIGGER:-P1DT15H
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:Reminder
            TRIGGER:-P6DT15H
            END:VALARM
            BEGIN:VALARM
            ACTION:NONE
            TRIGGER;VALUE=DATE-TIME:19760401T005545Z
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val originalEvent = parser.parseAllEvents(originalIcs).getOrNull()!!.first()

        val entity = createTestEvent(
            uid = "test-alert@kashcal.test",
            title = "test alert",
            startTs = originalEvent.dtStart.timestamp,
            endTs = originalEvent.effectiveEnd().timestamp,
            isAllDay = true,
            reminders = listOf("PT9H", "-P1DT15H")
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvent = parser.parseAllEvents(patched).getOrNull()!!.first()

        assertEquals("Exactly the 2 kept reminders", 2, patchedEvent.alarms.size)
        assertFalse("No ACTION:NONE in output", patched.contains("ACTION:NONE"))
        assertFalse("No 1976 phantom absolute trigger", patched.contains("19760401"))
        assertFalse("No deleted 1-week -P6DT15H", patched.contains("-P6DT15H"))
        val triggers = patchedEvent.alarms.mapNotNull { it.trigger?.let { d -> org.onekash.icaldav.model.ICalAlarm.formatDuration(d) } }
        assertTrue("9 AM day-of kept", triggers.contains("PT9H"))
        // -P1DT15H normalizes to -PT39H (same instant); accept either encoding
        assertTrue("2-days-before kept", triggers.any { it == "-P1DT15H" || it == "-PT39H" })
    }

    @Test
    fun `patch drops ACTION_NONE even when it is the only original alarm`() {
        // NONE-only original and one user reminder: NONE is dropped and the reminder becomes
        // a new DISPLAY alarm (the no-match branch in mergeAlarms).
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iPhone OS 26.1//EN
            BEGIN:VEVENT
            UID:none-only@kashcal.test
            DTSTAMP:20260603T120000Z
            DTSTART;VALUE=DATE:20260605
            DTEND;VALUE=DATE:20260606
            SUMMARY:none only
            BEGIN:VALARM
            ACTION:NONE
            TRIGGER;VALUE=DATE-TIME:19760401T005545Z
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val originalEvent = parser.parseAllEvents(originalIcs).getOrNull()!!.first()
        val entity = createTestEvent(
            uid = "none-only@kashcal.test",
            title = "none only",
            startTs = originalEvent.dtStart.timestamp,
            endTs = originalEvent.effectiveEnd().timestamp,
            isAllDay = true,
            reminders = listOf("PT9H")
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvent = parser.parseAllEvents(patched).getOrNull()!!.first()

        assertEquals("Only the user's fresh DISPLAY alarm", 1, patchedEvent.alarms.size)
        assertFalse("No NONE", patched.contains("ACTION:NONE"))
        assertFalse("No 1976 phantom", patched.contains("19760401"))
        assertEquals(org.onekash.icaldav.model.AlarmAction.DISPLAY, patchedEvent.alarms.first().action)
    }

    @Test
    fun `patch preserves attendees`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:attendee-test@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Meeting with Attendees
            ORGANIZER;CN=John Doe:mailto:john@example.com
            ATTENDEE;CN=Jane Smith;PARTSTAT=ACCEPTED:mailto:jane@example.com
            ATTENDEE;CN=Bob Wilson;PARTSTAT=TENTATIVE:mailto:bob@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = createTestEvent(
            uid = "attendee-test@kashcal.test",
            title = "Updated Meeting Title",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600000
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        val patchedEvent = patchedEvents.first()

        assertEquals("Updated Meeting Title", patchedEvent.summary)
        assertEquals("Should preserve organizer", "john@example.com", patchedEvent.organizer?.email)
        assertEquals("Should preserve 2 attendees", 2, patchedEvent.attendees.size)
    }

    @Test
    fun `patch with explicit attendees REPLACES the rawIcal attendee set`() {
        // Editing attendees on a server-synced event (which has rawIcal) must reach the wire:
        // a non-null attendee set from the caller replaces the original ATTENDEE block.
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:edit-attendees@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Meeting
            ORGANIZER;CN=John Doe:mailto:john@example.com
            ATTENDEE;CN=Jane;PARTSTAT=ACCEPTED:mailto:jane@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = createTestEvent(
            uid = "edit-attendees@kashcal.test",
            title = "Meeting",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600000,
        )
        // User added Carl and removed Jane in the picker; this is the table set.
        val newAttendees = listOf(
            Attendee(eventId = 1, address = "mailto:carl@example.com", displayName = "Carl", partstat = "NEEDS-ACTION")
        )

        val patched = IcsPatcher.patch(originalIcs, entity, newAttendees)
        val patchedEvent = parser.parseAllEvents(patched).getOrNull()!!.first()

        val emails = patchedEvent.attendees.map { it.email }.toSet()
        assertEquals("explicit set replaces original", setOf("carl@example.com"), emails)
        assertFalse("removed attendee must not survive", emails.contains("jane@example.com"))
    }

    @Test
    fun `patch with empty attendees clears the rawIcal attendee set`() {
        // An explicit empty list clears every attendee, unlike null, which keeps them. The
        // remove-all path reaches the wire.
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:clear-attendees@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Meeting
            ORGANIZER;CN=John:mailto:john@example.com
            ATTENDEE;CN=Jane;PARTSTAT=ACCEPTED:mailto:jane@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        // Organized, since only an organized event takes the caller's attendee set.
        val entity = createTestEvent(
            uid = "clear-attendees@kashcal.test", title = "Meeting",
            startTs = System.currentTimeMillis(), endTs = System.currentTimeMillis() + 3600000,
            organizerEmail = "john@example.com",
        )

        val patched = IcsPatcher.patch(originalIcs, entity, emptyList())
        val patchedEvent = parser.parseAllEvents(patched).getOrNull()!!.first()

        assertEquals("empty list clears attendees", 0, patchedEvent.attendees.size)
    }

    @Test
    fun `patch emits organizer from event when rawIcal had none and attendees are added`() {
        // An event created without invitees synced to the server, so its stored rawIcal has
        // no ORGANIZER. Later the user adds an attendee and the coordinator stamps
        // Event.organizerEmail. The patch must put that organizer on the wire; otherwise the
        // server has no ORGANIZER to schedule with (RFC 6638 §3) and no invite is delivered.
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:no-organizer@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Solo event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = createTestEvent(
            uid = "no-organizer@kashcal.test",
            title = "Solo event",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600000,
            organizerEmail = "me@example.com",
            organizerName = "Me Myself",
        )
        val added = listOf(
            Attendee(eventId = 1, address = "mailto:guest@example.com", displayName = "Guest", partstat = "NEEDS-ACTION")
        )

        val patched = IcsPatcher.patch(originalIcs, entity, added)
        val patchedEvent = parser.parseAllEvents(patched).getOrNull()!!.first()

        assertEquals("organizer must be emitted from the event", "me@example.com", patchedEvent.organizer?.email)
        assertEquals("added attendee must reach the wire", setOf("guest@example.com"),
            patchedEvent.attendees.map { it.email }.toSet())
    }

    @Test
    fun `patch does not synthesize an organizer on a cosmetic edit with no attendees`() {
        // A non-push edit (attendees == null), such as a title change or the export and share
        // path, must not invent an ORGANIZER from a lingering organizerEmail. That would leak
        // the user's address into a body that never had one, and into shared .ics files.
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:cosmetic@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Personal event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = createTestEvent(
            uid = "cosmetic@kashcal.test",
            title = "Renamed",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600000,
            organizerEmail = "me@example.com",
        )

        // attendees == null keeps the original, so no ORGANIZER appears.
        val patched = IcsPatcher.patch(originalIcs, entity, attendees = null)
        val patchedEvent = parser.parseAllEvents(patched).getOrNull()!!.first()

        assertNull("must not synthesize an organizer on a non-push edit", patchedEvent.organizer)
    }

    @Test
    fun `patch keeps the original organizer over the event organizer`() {
        // The server's ORGANIZER wins, in its mailto/urn-uuid/CN shape; a stamped
        // Event.organizerEmail must not replace it.
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:has-organizer@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Meeting
            ORGANIZER;CN=Boss:mailto:boss@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = createTestEvent(
            uid = "has-organizer@kashcal.test",
            title = "Meeting",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600000,
            organizerEmail = "me@example.com",
        )
        val added = listOf(
            Attendee(eventId = 1, address = "mailto:guest@example.com", displayName = "Guest", partstat = "NEEDS-ACTION")
        )

        val patched = IcsPatcher.patch(originalIcs, entity, added)
        val patchedEvent = parser.parseAllEvents(patched).getOrNull()!!.first()

        assertEquals("original organizer wins", "boss@example.com", patchedEvent.organizer?.email)
    }

    @Test
    fun `patch preserves rawProperties`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:raw-props@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event with Custom Props
            X-CUSTOM-PROP:custom value
            X-APPLE-STRUCTURED-LOCATION;VALUE=URI:geo:37.33,-122.03
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = createTestEvent(
            uid = "raw-props@kashcal.test",
            title = "Updated Title",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600000
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        val patchedEvent = patchedEvents.first()

        assertFalse("Should preserve raw properties", patchedEvent.rawProperties.isEmpty())
        assertTrue(
            "Should have X-CUSTOM-PROP",
            patchedEvent.rawProperties.any { it.key.contains("X-CUSTOM-PROP") }
        )
    }

    @Test
    fun `patch serializes stored sequence verbatim`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:seq-test@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Original Event
            SEQUENCE:5
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = createTestEvent(
            uid = "seq-test@kashcal.test",
            title = "Updated Event",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600000,
            sequence = 5
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        val patchedEvent = patchedEvents.first()

        // The patcher writes the stored SEQUENCE verbatim and compares nothing; EventWriter
        // decides the bump (SequenceBumper). Though this fixture's startTs differs from the
        // original DTSTART, the patcher emits the stored 5.
        assertEquals("Sequence should be serialized verbatim", 5, patchedEvent.sequence)
    }

    @Test
    fun `patch updates EXDATE when changed`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:exdate-test@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            RRULE:FREQ=DAILY;COUNT=10
            EXDATE:20251226T100000Z
            SUMMARY:Daily Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Add EXDATEs
        val entity = createTestEvent(
            uid = "exdate-test@kashcal.test",
            title = "Daily Event",
            startTs = 1735120800000L, // Dec 25, 2024 10:00 UTC
            endTs = 1735124400000L,
            rrule = "FREQ=DAILY;COUNT=10",
            exdate = "1735207200000,1735293600000"  // Dec 26 and Dec 27 in ms
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        val patchedEvent = patchedEvents.first()

        assertEquals("Should have 2 EXDATEs", 2, patchedEvent.exdates.size)
    }

    @Test
    fun `patch uses Event entity UID even when rawIcal has different UID`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:server-uid-123
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Original Title
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = createTestEvent(
            uid = "local-uid-456",
            title = "Updated Title",
            startTs = 1735120800000L,
            endTs = 1735124400000L
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        assertTrue("Patched ICS should contain entity UID", patched.contains("UID:local-uid-456"))
        assertFalse("Patched ICS should not contain original UID", patched.contains("UID:server-uid-123"))
    }

    @Test
    fun `patch uses Event entity UID when rawIcal has no UID`() {
        // rawIcal from a non-compliant server has no UID. ICalParser gives it a random UUID on
        // every parse, so IcsPatcher must write the entity's UID, stable since the first pull.
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:No UID Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = createTestEvent(
            uid = "stable-room-uid-789",
            title = "Updated Title",
            startTs = 1735120800000L,
            endTs = 1735124400000L
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        assertTrue("Patched ICS should contain entity UID", patched.contains("UID:stable-room-uid-789"))
    }

    // ========== Generate Fresh Tests ==========

    @Test
    fun `generateFresh creates valid ICS for new event`() {
        val entity = createTestEvent(
            uid = "new-event@kashcal.test",
            title = "New Event",
            description = "A brand new event",
            location = "Meeting Room",
            startTs = 1735120800000L, // Dec 25, 2024 10:00 UTC
            endTs = 1735124400000L,   // Dec 25, 2024 11:00 UTC
            timezone = "America/New_York",
            reminders = listOf("-PT15M", "-PT1H")
        )

        val generated = IcsPatcher.generateFresh(entity)

        // The output parses
        val events = parser.parseAllEvents(generated).getOrNull()!!
        assertEquals("Should have 1 event", 1, events.size)

        val event = events.first()
        assertEquals("new-event@kashcal.test", event.uid)
        assertEquals("New Event", event.summary)
        assertEquals("A brand new event", event.description)
        assertEquals("Meeting Room", event.location)
        assertEquals(2, event.alarms.size)
    }

    @Test
    fun `generateFresh creates all-day event correctly`() {
        val entity = createTestEvent(
            uid = "allday-new@kashcal.test",
            title = "All Day Event",
            startTs = 1735084800000L, // Dec 25, 2024 00:00 UTC
            endTs = 1735171199999L,   // Dec 25, 2024 23:59:59.999 UTC
            isAllDay = true
        )

        val generated = IcsPatcher.generateFresh(entity)

        val events = parser.parseAllEvents(generated).getOrNull()!!
        val event = events.first()

        assertTrue("Should be all-day", event.isAllDay)
    }

    @Test
    fun `generateFresh creates recurring event correctly`() {
        val entity = createTestEvent(
            uid = "recurring-new@kashcal.test",
            title = "Weekly Meeting",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR"
        )

        val generated = IcsPatcher.generateFresh(entity)

        val events = parser.parseAllEvents(generated).getOrNull()!!
        val event = events.first()

        assertNotNull("Should have RRULE", event.rrule)
        assertTrue(event.rrule!!.toICalString().contains("FREQ=WEEKLY"))
    }

    @Test
    fun `generateFresh includes organizer when present`() {
        val entity = createTestEvent(
            uid = "org-new@kashcal.test",
            title = "Meeting",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            organizerEmail = "john@example.com",
            organizerName = "John Doe"
        )

        val generated = IcsPatcher.generateFresh(entity)

        val events = parser.parseAllEvents(generated).getOrNull()!!
        val event = events.first()

        assertNotNull("Should have organizer", event.organizer)
        assertEquals("john@example.com", event.organizer?.email)
        assertEquals("John Doe", event.organizer?.name)
    }

    // ========== Fallback Tests ==========

    @Test
    fun `patch falls back to generateFresh when rawIcal is null`() {
        val entity = createTestEvent(
            uid = "fallback-null@kashcal.test",
            title = "Fallback Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L
        )

        val result = IcsPatcher.patch(null, entity)

        // Should generate valid ICS
        val events = parser.parseAllEvents(result).getOrNull()!!
        assertEquals("fallback-null@kashcal.test", events.first().uid)
    }

    @Test
    fun `patch falls back to generateFresh when rawIcal is invalid`() {
        val entity = createTestEvent(
            uid = "fallback-invalid@kashcal.test",
            title = "Fallback Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L
        )

        val result = IcsPatcher.patch("not valid ical data", entity)

        // Should generate valid ICS
        val events = parser.parseAllEvents(result).getOrNull()!!
        assertEquals("fallback-invalid@kashcal.test", events.first().uid)
    }

    // ========== RawIcsParser Tests ==========

    @Test
    fun `RawIcsParser extracts all alarms`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:raw-alarms@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:1 hour
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            DESCRIPTION:1 day
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1W
            DESCRIPTION:1 week
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val alarms = RawIcsParser.getAllAlarms(ics)

        assertEquals("Should have 4 alarms", 4, alarms.size)
    }

    @Test
    fun `RawIcsParser excludes ACTION_NONE sentinels`() {
        // ReminderScheduler reads alarms through RawIcsParser when an event has more than 3.
        // An ACTION:NONE sentinel must be excluded there too, as ICalEventMapper excludes it,
        // so it can never schedule a phantom reminder.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iPhone OS 26.1//EN
            BEGIN:VEVENT
            UID:raw-none@kashcal.test
            DTSTAMP:20260603T120000Z
            DTSTART;VALUE=DATE:20260605
            DTEND;VALUE=DATE:20260606
            SUMMARY:raw none
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:PT9H
            DESCRIPTION:Reminder
            END:VALARM
            BEGIN:VALARM
            ACTION:NONE
            TRIGGER;VALUE=DATE-TIME:19760401T005545Z
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val alarms = RawIcsParser.getAllAlarms(ics)
        assertEquals("NONE sentinel excluded", 1, alarms.size)
        assertEquals("Only the real DISPLAY alarm remains", AlarmAction.DISPLAY, alarms.first().action)
        assertEquals("getAlarmCount excludes NONE", 1, RawIcsParser.getAlarmCount(ics))
    }

    @Test
    fun `RawIcsParser getAlarmCount works correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarm-count@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:Alarm 1
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:Alarm 2
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        assertEquals(2, RawIcsParser.getAlarmCount(ics))
    }

    @Test
    fun `RawIcsParser handles null and invalid input gracefully`() {
        assertEquals(0, RawIcsParser.getAlarmCount(null))
        assertEquals(0, RawIcsParser.getAlarmCount(""))
        assertEquals(0, RawIcsParser.getAlarmCount("invalid"))
        assertTrue(RawIcsParser.getAllAlarms(null).isEmpty())
    }

    // ========== serializeWithExceptions Round-Trip Tests ==========

    @Test
    fun `serializeWithExceptions with 3 exceptions round-trips correctly`() {
        // Master recurring event
        val masterStartTs = 1735099200000L // Dec 25, 2024 04:00 UTC
        val master = createTestEvent(
            uid = "weekly-standup@kashcal.test",
            title = "Weekly Standup",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000, // 1 hour
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            reminders = listOf("-PT15M", "-PT30M")
        )

        // 3 different exceptions
        val exception1 = createExceptionEvent(
            masterId = 1L,
            masterUid = "weekly-standup@kashcal.test",
            originalInstanceTime = masterStartTs + (7 * 24 * 3600000L), // Week 2
            title = "Weekly Standup - Extended",
            startTs = masterStartTs + (7 * 24 * 3600000L) + (2 * 3600000L), // Moved 2 hours later
            endTs = masterStartTs + (7 * 24 * 3600000L) + (4 * 3600000L), // 2 hours long
            reminders = listOf("-PT30M") // Different alarm
        )

        val exception2 = createExceptionEvent(
            masterId = 1L,
            masterUid = "weekly-standup@kashcal.test",
            originalInstanceTime = masterStartTs + (14 * 24 * 3600000L), // Week 3
            title = "Weekly Standup - Room Change",
            startTs = masterStartTs + (14 * 24 * 3600000L),
            endTs = masterStartTs + (14 * 24 * 3600000L) + 3600000,
            location = "Conference Room B" // Added location
        )

        val exception3 = createExceptionEvent(
            masterId = 1L,
            masterUid = "weekly-standup@kashcal.test",
            originalInstanceTime = masterStartTs + (21 * 24 * 3600000L), // Week 4
            title = "Weekly Standup - Quarterly Review",
            startTs = masterStartTs + (21 * 24 * 3600000L),
            endTs = masterStartTs + (21 * 24 * 3600000L) + (2 * 3600000L), // 2 hours
            description = "Year-end quarterly review" // Added description
        )

        // Serialize with every exception
        val serialized = IcsPatcher.serializeWithExceptions(
            master,
            listOf(exception1, exception2, exception3)
        )

        // Parse the result
        val parsed = parser.parseAllEvents(serialized).getOrNull()!!

        // 4 events: 1 master and 3 exceptions
        assertEquals("Should have 4 events", 4, parsed.size)

        // Find master and exceptions
        val parsedMaster = parsed.find { it.recurrenceId == null }!!
        val parsedExceptions = parsed.filter { it.recurrenceId != null }

        // Master
        assertEquals("weekly-standup@kashcal.test", parsedMaster.uid)
        assertEquals("Weekly Standup", parsedMaster.summary)
        assertNotNull("Master should have RRULE", parsedMaster.rrule)

        // All 3 exceptions
        assertEquals("Should have 3 exceptions", 3, parsedExceptions.size)

        // All exceptions share master's UID
        parsedExceptions.forEach { exc ->
            assertEquals("Exception should share master UID", "weekly-standup@kashcal.test", exc.uid)
            assertNull("Exception should NOT have RRULE", exc.rrule)
            assertNotNull("Exception should have RECURRENCE-ID", exc.recurrenceId)
        }

        // Each exception's own properties
        val exc1 = parsedExceptions.find { it.summary == "Weekly Standup - Extended" }!!
        assertEquals("Extended exception should have different alarm", 1, exc1.alarms.size)

        val exc2 = parsedExceptions.find { it.summary == "Weekly Standup - Room Change" }!!
        assertEquals("Room change exception should have location", "Conference Room B", exc2.location)

        val exc3 = parsedExceptions.find { it.summary == "Weekly Standup - Quarterly Review" }!!
        assertEquals("Quarterly review should have description", "Year-end quarterly review", exc3.description)
    }

    @Test
    fun `serializeWithExceptions with cancelled exception (deletion)`() {
        // Master recurring event
        val masterStartTs = 1735099200000L
        val master = createTestEvent(
            uid = "daily-meeting@kashcal.test",
            title = "Daily Sync",
            startTs = masterStartTs,
            endTs = masterStartTs + 1800000, // 30 min
            rrule = "FREQ=DAILY"
        )

        // A cancelled exception (deleted occurrence)
        val cancelledException = createExceptionEvent(
            masterId = 1L,
            masterUid = "daily-meeting@kashcal.test",
            originalInstanceTime = masterStartTs + (3 * 24 * 3600000L), // Day 4
            title = "Daily Sync", // original title
            startTs = masterStartTs + (3 * 24 * 3600000L),
            endTs = masterStartTs + (3 * 24 * 3600000L) + 1800000,
            status = "CANCELLED"
        )

        // Serialize
        val serialized = IcsPatcher.serializeWithExceptions(master, listOf(cancelledException))

        // Parse
        val parsed = parser.parseAllEvents(serialized).getOrNull()!!

        assertEquals("Should have 2 events", 2, parsed.size)

        val parsedCancelled = parsed.find { it.recurrenceId != null }!!
        assertEquals("Cancelled exception should have CANCELLED status",
            "CANCELLED", parsedCancelled.status?.name)
    }

    @Test
    fun `serializeWithExceptions VTIMEZONE blocks dedupe across master and exceptions for shared TZID`() {
        val masterStartTs = 1735099200000L
        val master = createTestEvent(
            uid = "weekly-ny@kashcal.test",
            title = "NY Standup",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000,
            timezone = "America/New_York",
            rrule = "FREQ=WEEKLY;BYDAY=MO"
        )
        val ex1 = createExceptionEvent(
            masterId = 1L,
            masterUid = "weekly-ny@kashcal.test",
            originalInstanceTime = masterStartTs + (7 * 24 * 3600000L),
            title = "NY Standup - Moved",
            startTs = masterStartTs + (7 * 24 * 3600000L) + 3600000,
            endTs = masterStartTs + (7 * 24 * 3600000L) + 7200000,
            timezone = "America/New_York"
        )

        val ics = IcsPatcher.serializeWithExceptions(master, listOf(ex1))

        val vtimezoneBlockCount = Regex("BEGIN:VTIMEZONE").findAll(ics).count()
        assertEquals("Expected exactly 1 VTIMEZONE block (deduped), got:\n$ics", 1, vtimezoneBlockCount)
    }

    @Test
    fun `serializeWithExceptions emits VTIMEZONE for zone referenced only by exception`() {
        // The master has no TZID (floating) and an exception uses a non-UTC TZID, so only the
        // exception references the zone; its VTIMEZONE must still be emitted.
        val masterStartTs = 1735099200000L
        val master = createTestEvent(
            uid = "floating@kashcal.test",
            title = "Floating Master",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000,
            timezone = null, // floating, no TZID
            rrule = "FREQ=WEEKLY"
        )
        val exceptionInTokyo = createExceptionEvent(
            masterId = 1L,
            masterUid = "floating@kashcal.test",
            originalInstanceTime = masterStartTs + (7 * 24 * 3600000L),
            title = "Moved to Tokyo",
            startTs = masterStartTs + (7 * 24 * 3600000L),
            endTs = masterStartTs + (7 * 24 * 3600000L) + 3600000,
            timezone = "Asia/Tokyo"
        )

        val ics = IcsPatcher.serializeWithExceptions(master, listOf(exceptionInTokyo))

        assertTrue(
            "Expected BEGIN:VTIMEZONE for Asia/Tokyo but output had none:\n$ics",
            ics.contains("BEGIN:VTIMEZONE") && ics.contains("TZID:Asia/Tokyo")
        )
    }

    @Test
    fun `serializeWithExceptions with mixed cancelled and modified exceptions`() {
        val masterStartTs = 1735099200000L
        val master = createTestEvent(
            uid = "team-call@kashcal.test",
            title = "Team Call",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000,
            rrule = "FREQ=WEEKLY;COUNT=10"
        )

        // Week 2: modified, moved to the afternoon
        val modifiedException = createExceptionEvent(
            masterId = 1L,
            masterUid = "team-call@kashcal.test",
            originalInstanceTime = masterStartTs + (7 * 24 * 3600000L),
            title = "Team Call - Afternoon",
            startTs = masterStartTs + (7 * 24 * 3600000L) + (6 * 3600000L), // 6 hours later
            endTs = masterStartTs + (7 * 24 * 3600000L) + (7 * 3600000L)
        )

        // Week 3: cancelled
        val cancelledException = createExceptionEvent(
            masterId = 1L,
            masterUid = "team-call@kashcal.test",
            originalInstanceTime = masterStartTs + (14 * 24 * 3600000L),
            title = "Team Call",
            startTs = masterStartTs + (14 * 24 * 3600000L),
            endTs = masterStartTs + (14 * 24 * 3600000L) + 3600000,
            status = "CANCELLED"
        )

        // Week 4: modified with a location
        val locationException = createExceptionEvent(
            masterId = 1L,
            masterUid = "team-call@kashcal.test",
            originalInstanceTime = masterStartTs + (21 * 24 * 3600000L),
            title = "Team Call - Offsite",
            startTs = masterStartTs + (21 * 24 * 3600000L),
            endTs = masterStartTs + (21 * 24 * 3600000L) + 3600000,
            location = "Building C, Room 101"
        )

        val serialized = IcsPatcher.serializeWithExceptions(
            master,
            listOf(modifiedException, cancelledException, locationException)
        )

        val parsed = parser.parseAllEvents(serialized).getOrNull()!!

        assertEquals("Should have 4 events", 4, parsed.size)

        val cancelled = parsed.filter { it.status?.name == "CANCELLED" }
        val confirmed = parsed.filter { it.status?.name != "CANCELLED" }

        assertEquals("Should have 1 cancelled", 1, cancelled.size)
        assertEquals("Should have 3 confirmed (master + 2 modified)", 3, confirmed.size)
    }

    @Test
    fun `serializeWithExceptions re-edit same occurrence preserves only latest`() {
        // The user edits the week 2 occurrence, then edits it again; only the final edit is
        // serialized.

        val masterStartTs = 1735099200000L
        val master = createTestEvent(
            uid = "evolving-meeting@kashcal.test",
            title = "Planning Session",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000,
            rrule = "FREQ=WEEKLY"
        )

        // Final version of the week 2 exception after several edits.
        // Title changed twice: "Planning Session", "Extended Planning", "Final Planning".
        // Time changed: morning, afternoon, evening.
        val finalException = createExceptionEvent(
            masterId = 1L,
            masterUid = "evolving-meeting@kashcal.test",
            originalInstanceTime = masterStartTs + (7 * 24 * 3600000L), // Week 2
            title = "Final Planning Session", // after several edits
            startTs = masterStartTs + (7 * 24 * 3600000L) + (10 * 3600000L), // evening
            endTs = masterStartTs + (7 * 24 * 3600000L) + (12 * 3600000L), // 2 hour meeting
            description = "Final version after re-edits",
            sequence = 3 // higher sequence from several edits
        )

        val serialized = IcsPatcher.serializeWithExceptions(master, listOf(finalException))

        val parsed = parser.parseAllEvents(serialized).getOrNull()!!

        assertEquals("Should have 2 events", 2, parsed.size)

        val exception = parsed.find { it.recurrenceId != null }!!
        assertEquals("Should have final title", "Final Planning Session", exception.summary)
        assertEquals("Should have final description", "Final version after re-edits", exception.description)
        assertEquals("Sequence should reflect edits", 3, exception.sequence)
    }

    @Test
    fun `serializeWithExceptions with 5 exceptions and varied modifications`() {
        val masterStartTs = 1735099200000L
        val master = createTestEvent(
            uid = "big-series@kashcal.test",
            title = "Sprint Review",
            startTs = masterStartTs,
            endTs = masterStartTs + (2 * 3600000L), // 2 hours
            rrule = "FREQ=WEEKLY;BYDAY=FR",
            reminders = listOf("-PT1H", "-P1D"),
            location = "Main Conference Room"
        )

        val exceptions = listOf(
            // Exception 1: Time change only
            createExceptionEvent(
                masterId = 1L,
                masterUid = "big-series@kashcal.test",
                originalInstanceTime = masterStartTs + (7 * 24 * 3600000L),
                title = "Sprint Review",
                startTs = masterStartTs + (7 * 24 * 3600000L) + (3 * 3600000L), // 3 hours later
                endTs = masterStartTs + (7 * 24 * 3600000L) + (5 * 3600000L)
            ),
            // Exception 2: Title + location change
            createExceptionEvent(
                masterId = 1L,
                masterUid = "big-series@kashcal.test",
                originalInstanceTime = masterStartTs + (14 * 24 * 3600000L),
                title = "Sprint Review - Remote",
                startTs = masterStartTs + (14 * 24 * 3600000L),
                endTs = masterStartTs + (14 * 24 * 3600000L) + (2 * 3600000L),
                location = "Zoom Meeting"
            ),
            // Exception 3: Cancelled
            createExceptionEvent(
                masterId = 1L,
                masterUid = "big-series@kashcal.test",
                originalInstanceTime = masterStartTs + (21 * 24 * 3600000L),
                title = "Sprint Review",
                startTs = masterStartTs + (21 * 24 * 3600000L),
                endTs = masterStartTs + (21 * 24 * 3600000L) + (2 * 3600000L),
                status = "CANCELLED"
            ),
            // Exception 4: Different alarms
            createExceptionEvent(
                masterId = 1L,
                masterUid = "big-series@kashcal.test",
                originalInstanceTime = masterStartTs + (28 * 24 * 3600000L),
                title = "Sprint Review - Important",
                startTs = masterStartTs + (28 * 24 * 3600000L),
                endTs = masterStartTs + (28 * 24 * 3600000L) + (2 * 3600000L),
                reminders = listOf("-PT30M", "-PT1H", "-PT2H") // More reminders
            ),
            // Exception 5: All properties changed
            createExceptionEvent(
                masterId = 1L,
                masterUid = "big-series@kashcal.test",
                originalInstanceTime = masterStartTs + (35 * 24 * 3600000L),
                title = "Year-End Sprint Review",
                startTs = masterStartTs + (35 * 24 * 3600000L) + (2 * 3600000L),
                endTs = masterStartTs + (35 * 24 * 3600000L) + (5 * 3600000L), // 3 hours
                location = "Executive Boardroom",
                description = "Year-end review with stakeholders",
                reminders = listOf("-P1D", "-PT2H")
            )
        )

        val serialized = IcsPatcher.serializeWithExceptions(master, exceptions)

        val parsed = parser.parseAllEvents(serialized).getOrNull()!!

        // 6 events in total
        assertEquals("Should have 6 events", 6, parsed.size)

        val parsedMaster = parsed.find { it.recurrenceId == null }!!
        val parsedExceptions = parsed.filter { it.recurrenceId != null }

        // Master is intact
        assertEquals("Sprint Review", parsedMaster.summary)
        assertEquals("Main Conference Room", parsedMaster.location)
        assertNotNull(parsedMaster.rrule)

        // Exception count
        assertEquals("Should have 5 exceptions", 5, parsedExceptions.size)

        // Every RECURRENCE-ID is distinct
        val recurrenceIds = parsedExceptions.map { it.recurrenceId!!.timestamp }.toSet()
        assertEquals("All RECURRENCE-IDs should be unique", 5, recurrenceIds.size)

        // The cancelled one
        val cancelled = parsedExceptions.filter { it.status?.name == "CANCELLED" }
        assertEquals("Should have 1 cancelled", 1, cancelled.size)

        // The year-end exception has every change
        val yearEnd = parsedExceptions.find { it.summary == "Year-End Sprint Review" }!!
        assertEquals("Executive Boardroom", yearEnd.location)
        assertEquals("Year-end review with stakeholders", yearEnd.description)
    }

    @Test
    fun `serializeWithExceptions preserves RECURRENCE-ID timestamps accurately`() {
        val masterStartTs = 1735099200000L // Dec 25, 2024 04:00 UTC
        val master = createTestEvent(
            uid = "timestamp-test@kashcal.test",
            title = "Timestamp Test",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000,
            rrule = "FREQ=DAILY"
        )

        // Exceptions on specific dates
        val day3Ts = masterStartTs + (2 * 24 * 3600000L) // Dec 27
        val day7Ts = masterStartTs + (6 * 24 * 3600000L) // Dec 31
        val day10Ts = masterStartTs + (9 * 24 * 3600000L) // Jan 3

        val exceptions = listOf(
            createExceptionEvent(
                masterId = 1L,
                masterUid = "timestamp-test@kashcal.test",
                originalInstanceTime = day3Ts,
                title = "Day 3 Modified",
                startTs = day3Ts + 3600000, // Moved 1 hour
                endTs = day3Ts + (2 * 3600000L)
            ),
            createExceptionEvent(
                masterId = 1L,
                masterUid = "timestamp-test@kashcal.test",
                originalInstanceTime = day7Ts,
                title = "Day 7 Modified",
                startTs = day7Ts,
                endTs = day7Ts + 3600000
            ),
            createExceptionEvent(
                masterId = 1L,
                masterUid = "timestamp-test@kashcal.test",
                originalInstanceTime = day10Ts,
                title = "Day 10 Modified",
                startTs = day10Ts + (2 * 3600000L),
                endTs = day10Ts + (3 * 3600000L)
            )
        )

        val serialized = IcsPatcher.serializeWithExceptions(master, exceptions)
        val parsed = parser.parseAllEvents(serialized).getOrNull()!!

        val parsedExceptions = parsed.filter { it.recurrenceId != null }

        // Each RECURRENCE-ID matches its originalInstanceTime
        val recIdTimestamps = parsedExceptions.map { it.recurrenceId!!.timestamp }.sorted()
        val expectedTimestamps = listOf(day3Ts, day7Ts, day10Ts).sorted()

        assertEquals("RECURRENCE-ID timestamps should match original instance times",
            expectedTimestamps, recIdTimestamps)
    }

    @Test
    fun `serializeWithExceptions handles exception with different timezone than master`() {
        val masterStartTs = 1735099200000L
        val master = createTestEvent(
            uid = "tz-test@kashcal.test",
            title = "Cross-TZ Meeting",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000,
            rrule = "FREQ=WEEKLY"
            // No timezone - use UTC to avoid timezone loading issues in test env
        )

        // Exception moved to different time (no explicit timezone to avoid test env issues)
        val exception = createExceptionEvent(
            masterId = 1L,
            masterUid = "tz-test@kashcal.test",
            originalInstanceTime = masterStartTs + (7 * 24 * 3600000L),
            title = "Cross-TZ Meeting - Rescheduled",
            startTs = masterStartTs + (7 * 24 * 3600000L) + (14 * 3600000L), // Different time
            endTs = masterStartTs + (7 * 24 * 3600000L) + (15 * 3600000L)
            // No timezone - test the time change aspect without timezone complexity
        )

        val serialized = IcsPatcher.serializeWithExceptions(master, listOf(exception))
        val parsed = parser.parseAllEvents(serialized).getOrNull()!!

        assertEquals("Should have 2 events", 2, parsed.size)

        val parsedException = parsed.find { it.recurrenceId != null }!!
        assertEquals("Cross-TZ Meeting - Rescheduled", parsedException.summary)
        // The exception is serialized with its new time
        assertNotNull(parsedException.dtStart)
        assertNotEquals("Exception should have different start time than original occurrence",
            masterStartTs + (7 * 24 * 3600000L), parsedException.dtStart.timestamp)
    }

    @Test
    fun `serializeWithExceptions empty exceptions list returns master only`() {
        val master = createTestEvent(
            uid = "solo@kashcal.test",
            title = "Solo Event",
            startTs = 1735099200000L,
            endTs = 1735102800000L,
            rrule = "FREQ=MONTHLY"
        )

        val serialized = IcsPatcher.serializeWithExceptions(master, emptyList())
        val parsed = parser.parseAllEvents(serialized).getOrNull()!!

        assertEquals("Should have only master", 1, parsed.size)
        assertNull("Should have no RECURRENCE-ID", parsed.first().recurrenceId)
        assertEquals("Solo Event", parsed.first().summary)
    }

    // ========== User reminder edits reach the patched body ==========

    @Test
    fun `BUG - patch should sync user reminder edits but currently ignores them`() {
        // Original ICS with 3 alarms
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:reminder-edit-bug@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Original Title
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:30 min
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:1 hour
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // User changes reminder 1 from 15m to 45m and keeps reminder 2 at 30m
        val entity = createTestEvent(
            uid = "reminder-edit-bug@kashcal.test",
            title = "Original Title",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = listOf("-PT45M", "-PT30M")  // user's edit: 15m to 45m
        )

        // Patch the ICS
        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        val patchedEvent = patchedEvents.first()

        // The first alarm is 45 minutes (the user's edit), not 15 minutes (the original)
        val alarmTriggers = patchedEvent.alarms.map { alarm ->
            alarm.trigger?.let { duration ->
                duration.toMinutes()
            }
        }

        assertEquals(
            "First alarm should be user's edit (45 min), but bug preserves original (15 min)",
            -45L,
            alarmTriggers[0]
        )
    }

    @Test
    fun `patch syncs user edits and drops deleted displayed alarms`() {
        // Original ICS with 5 alarms, all within the displayed window (index < 5).
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:five-alarm-edit@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Meeting
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT2H
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // User edits: changes first alarm to 45m and keeps only two reminders.
        val entity = createTestEvent(
            uid = "five-alarm-edit@kashcal.test",
            title = "Meeting",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = listOf("-PT45M", "-PT30M")
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        val patchedEvent = patchedEvents.first()

        val alarmTriggers = patchedEvent.alarms.mapNotNull { it.trigger?.toMinutes() }

        // All 5 originals were displayed; the user kept 2, so the other 3 are deleted.
        assertEquals("Only the user's 2 reminders remain", 2, patchedEvent.alarms.size)
        // First alarm reflects the user's edit (-45), not the original -15.
        assertEquals("First alarm is the user's edit (-45 min)", -45L, alarmTriggers[0])
        assertEquals("Second alarm (-30 min)", -30L, alarmTriggers[1])
        // Deleted displayed alarms are gone.
        assertFalse("deleted -PT1H", alarmTriggers.contains(-60L))
        assertFalse("deleted -PT2H", alarmTriggers.contains(-120L))
        assertFalse("deleted -P1D", alarmTriggers.contains(-1440L))
    }

    @Test
    fun `BUG - patch should clear all alarms when user removes reminders`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:clear-alarms@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // User cleared every reminder (both set to "No reminder", stored as null)
        val entity = createTestEvent(
            uid = "clear-alarms@kashcal.test",
            title = "Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = null  // user wants no reminders
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        val patchedEvent = patchedEvents.first()

        // Neither of the original 2 alarms remains
        assertEquals(
            "Should have 0 alarms when user clears reminders, but bug preserves original",
            0,
            patchedEvent.alarms.size
        )
    }

    // ========== Sorted Reminders Round-Trip Tests ==========
    // ICalEventMapper stores reminders sorted by magnitude (v21.5.6), while the original alarms
    // stay in document order; IcsPatcher must pair them by trigger.

    @Test
    fun `patch applies sorted reminders to unsorted rawIcal alarms`() {
        // Server sent alarms in the order 1 day, 1 hour, 15 min; ICalEventMapper stored them
        // as 15 min, 1 hour, 1 day.
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:sorted-roundtrip@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event with Unsorted Alarms
            BEGIN:VALARM
            ACTION:AUDIO
            TRIGGER:-P1D
            DESCRIPTION:1 day before
            END:VALARM
            BEGIN:VALARM
            ACTION:EMAIL
            TRIGGER:-PT1H
            DESCRIPTION:1 hour before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min before
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Entity has sorted reminders, as ICalEventMapper stores them
        val entity = createTestEvent(
            uid = "sorted-roundtrip@kashcal.test",
            title = "Event with Unsorted Alarms",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = listOf("-PT15M", "-PT1H", "-P1D")  // sorted order from pull
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        val patchedEvent = patchedEvents.first()

        // 3 alarms
        assertEquals("Should have 3 alarms", 3, patchedEvent.alarms.size)

        // Each reminder is reconciled with the original alarm that has the same trigger, so
        // each alarm keeps its own ACTION at its own time. Pairing by position would scramble
        // actions: the AUDIO alarm would fire at -15m.
        val byTrigger = patchedEvent.alarms.associateBy { it.trigger?.toMinutes() }
        assertEquals("AUDIO stays at -1 day", AlarmAction.AUDIO, byTrigger[-1440L]?.action)
        assertEquals("EMAIL stays at -1 hour", AlarmAction.EMAIL, byTrigger[-60L]?.action)
        assertEquals("DISPLAY stays at -15 min", AlarmAction.DISPLAY, byTrigger[-15L]?.action)
    }

    @Test
    fun `patch preserves ACTION types from original alarms with sorted reminders`() {
        // ACTION types (AUDIO, EMAIL, DISPLAY) are kept from the original alarms even when
        // sorting reorders the triggers
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:action-preserve@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event
            BEGIN:VALARM
            ACTION:AUDIO
            TRIGGER:-P1D
            END:VALARM
            BEGIN:VALARM
            ACTION:EMAIL
            TRIGGER:-PT1H
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Original alarms: AUDIO@-1d, EMAIL@-1h. User reminders: -15m and -1d.
        val entity = createTestEvent(
            uid = "action-preserve@kashcal.test",
            title = "Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            reminders = listOf("-PT15M", "-P1D")
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        val patchedEvent = patchedEvents.first()

        assertEquals("Should have 2 alarms", 2, patchedEvent.alarms.size)

        // Reconciled by trigger: -1d matches the AUDIO alarm and keeps its ACTION; -15m matches
        // no original alarm (EMAIL@-1h was deleted in the form), so it becomes a new DISPLAY
        // alarm. Pairing by position would stamp the EMAIL action onto -1d.
        val byTrigger = patchedEvent.alarms.associateBy { it.trigger?.toMinutes() }
        assertEquals("AUDIO preserved at its own -1 day offset", AlarmAction.AUDIO, byTrigger[-1440L]?.action)
        assertEquals("new -15m reminder is a fresh DISPLAY alarm", AlarmAction.DISPLAY, byTrigger[-15L]?.action)
        assertFalse("the unmatched EMAIL alarm (-1h) was not re-stamped onto another time",
            patchedEvent.alarms.any { it.action == AlarmAction.EMAIL })
    }

    // ========== RFC 5545/7986 Extended Properties Tests ==========

    @Test
    fun `generateFresh includes priority field`() {
        val entity = createTestEvent(
            uid = "priority-fresh@kashcal.test",
            title = "High Priority Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            priority = 1
        )

        val generated = IcsPatcher.generateFresh(entity)
        val events = parser.parseAllEvents(generated).getOrNull()!!
        val event = events.first()

        assertEquals(1, event.priority)
    }

    @Test
    fun `generateFresh includes geo coordinates`() {
        val entity = createTestEvent(
            uid = "geo-fresh@kashcal.test",
            title = "Event at Apple Park",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            geoLat = 37.334722,
            geoLon = -122.008889
        )

        val generated = IcsPatcher.generateFresh(entity)
        val events = parser.parseAllEvents(generated).getOrNull()!!
        val event = events.first()

        assertNotNull("Should have GEO", event.geo)
        assertTrue("GEO should contain latitude", event.geo!!.contains("37.334722"))
        assertTrue("GEO should contain longitude", event.geo!!.contains("-122.008889"))
    }

    @Test
    fun `generateFresh includes color field`() {
        val entity = createTestEvent(
            uid = "color-fresh@kashcal.test",
            title = "Red Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            color = 0xFFFF0000.toInt() // red
        )

        val generated = IcsPatcher.generateFresh(entity)
        val events = parser.parseAllEvents(generated).getOrNull()!!
        val event = events.first()

        assertNotNull("Should have COLOR", event.color)
        // Pure red is CSS3 "red" in the wheel palette, so the name is emitted
        assertEquals("red", event.color)
    }

    @Test
    fun `generateFresh includes url field`() {
        val entity = createTestEvent(
            uid = "url-fresh@kashcal.test",
            title = "Event with Link",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            url = "https://example.com/event"
        )

        val generated = IcsPatcher.generateFresh(entity)
        val events = parser.parseAllEvents(generated).getOrNull()!!
        val event = events.first()

        assertEquals("https://example.com/event", event.url)
    }

    @Test
    fun `generateFresh includes categories field`() {
        val entity = createTestEvent(
            uid = "categories-fresh@kashcal.test",
            title = "Categorized Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            categories = listOf("MEETING", "WORK", "IMPORTANT")
        )

        val generated = IcsPatcher.generateFresh(entity)
        val events = parser.parseAllEvents(generated).getOrNull()!!
        val event = events.first()

        assertEquals(3, event.categories.size)
        assertTrue(event.categories.contains("MEETING"))
        assertTrue(event.categories.contains("WORK"))
        assertTrue(event.categories.contains("IMPORTANT"))
    }

    @Test
    fun `patch updates RFC 5545 extended properties`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:rfc-update@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Original Event
            PRIORITY:5
            GEO:37.0;-122.0
            COLOR:#0000FF
            URL:https://old.example.com
            CATEGORIES:OLD
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val entity = createTestEvent(
            uid = "rfc-update@kashcal.test",
            title = "Updated Event",
            startTs = 1735120800000L,
            endTs = 1735124400000L,
            priority = 1,
            geoLat = 40.7128,
            geoLon = -74.0060,
            color = 0xFFFF0000.toInt(),
            url = "https://new.example.com",
            categories = listOf("NEW", "UPDATED")
        )

        val patched = IcsPatcher.patch(originalIcs, entity)
        val events = parser.parseAllEvents(patched).getOrNull()!!
        val event = events.first()

        assertEquals("Updated Event", event.summary)
        assertEquals(1, event.priority)
        assertTrue("GEO should be updated", event.geo?.contains("40.7128") == true)
        assertEquals("red", event.color)
        assertEquals("https://new.example.com", event.url)
        assertEquals(2, event.categories.size)
        assertTrue(event.categories.contains("NEW"))
    }

    @Test
    fun `generateException includes RFC 5545 extended properties`() {
        val masterStartTs = 1735099200000L
        val master = createTestEvent(
            uid = "exc-rfc@kashcal.test",
            title = "Weekly Meeting",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000,
            rrule = "FREQ=WEEKLY"
        )

        val exception = createExceptionEvent(
            masterId = 1L,
            masterUid = "exc-rfc@kashcal.test",
            originalInstanceTime = masterStartTs + (7 * 24 * 3600000L),
            title = "Weekly Meeting - Special",
            startTs = masterStartTs + (7 * 24 * 3600000L),
            endTs = masterStartTs + (7 * 24 * 3600000L) + 3600000,
            priority = 2,
            geoLat = 51.5074,
            geoLon = -0.1278,
            color = 0xFF00FF00.toInt(),
            url = "https://special.example.com",
            categories = listOf("SPECIAL", "MEETING")
        )

        val serialized = IcsPatcher.serializeWithExceptions(master, listOf(exception))
        val parsed = parser.parseAllEvents(serialized).getOrNull()!!
        val parsedException = parsed.find { it.recurrenceId != null }!!

        assertEquals(2, parsedException.priority)
        assertNotNull("Exception should have GEO", parsedException.geo)
        // 0xFF00FF00 is CSS3 "lime" in the wheel palette, so the name is emitted
        assertEquals("lime", parsedException.color)
        assertEquals("https://special.example.com", parsedException.url)
        assertEquals(2, parsedException.categories.size)
    }

    // ========== Helper ==========

    private fun createExceptionEvent(
        masterId: Long,
        masterUid: String,
        originalInstanceTime: Long,
        title: String,
        startTs: Long,
        endTs: Long,
        description: String? = null,
        location: String? = null,
        timezone: String? = null,
        status: String = "CONFIRMED",
        reminders: List<String>? = null,
        sequence: Int = 1,
        priority: Int = 0,
        geoLat: Double? = null,
        geoLon: Double? = null,
        color: Int? = null,
        url: String? = null,
        categories: List<String>? = null,
        organizerEmail: String? = null
    ): Event {
        return Event(
            id = 100L + (originalInstanceTime % 1000), // 100 for whole-second instance times
            uid = masterUid, // same UID as master
            importId = "$masterUid:RECID:$originalInstanceTime",
            calendarId = 1L,
            title = title,
            location = location,
            description = description,
            startTs = startTs,
            endTs = endTs,
            timezone = timezone,
            isAllDay = false,
            status = status,
            transp = "OPAQUE",
            classification = "PUBLIC",
            organizerEmail = organizerEmail,
            organizerName = null,
            rrule = null, // exceptions have no RRULE
            rdate = null,
            exdate = null,
            duration = null,
            originalEventId = masterId, // link to master
            originalInstanceTime = originalInstanceTime, // which occurrence is modified
            originalSyncId = null,
            reminders = reminders,
            extraProperties = null,
            dtstamp = System.currentTimeMillis(),
            caldavUrl = null,
            etag = null,
            sequence = sequence,
            syncStatus = SyncStatus.SYNCED,
            lastSyncError = null,
            syncRetryCount = 0,
            localModifiedAt = null,
            serverModifiedAt = null,
            priority = priority,
            geoLat = geoLat,
            geoLon = geoLon,
            color = color,
            url = url,
            categories = categories,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    // ========== Helper ==========

    private fun createTestEvent(
        uid: String,
        title: String,
        startTs: Long,
        endTs: Long,
        description: String? = null,
        location: String? = null,
        timezone: String? = null,
        isAllDay: Boolean = false,
        rrule: String? = null,
        exdate: String? = null,
        reminders: List<String>? = null,
        sequence: Int = 0,
        organizerEmail: String? = null,
        organizerName: String? = null,
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
            location = location,
            description = description,
            startTs = startTs,
            endTs = endTs,
            timezone = timezone,
            isAllDay = isAllDay,
            status = "CONFIRMED",
            transp = "OPAQUE",
            classification = "PUBLIC",
            organizerEmail = organizerEmail,
            organizerName = organizerName,
            rrule = rrule,
            rdate = null,
            exdate = exdate,
            duration = null,
            originalEventId = null,
            originalInstanceTime = null,
            originalSyncId = null,
            reminders = reminders,
            extraProperties = null,
            dtstamp = System.currentTimeMillis(),
            caldavUrl = null,
            etag = null,
            sequence = sequence,
            syncStatus = SyncStatus.SYNCED,
            lastSyncError = null,
            syncRetryCount = 0,
            localModifiedAt = null,
            serverModifiedAt = null,
            priority = priority,
            geoLat = geoLat,
            geoLon = geoLon,
            color = color,
            url = url,
            categories = categories,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    // ========== patchAttendeeReply (RSVP write path) ==========

    private val multiAttendeeIcs = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//RSVP//EN
        BEGIN:VEVENT
        UID:rsvp-test@kashcal.test
        DTSTAMP:20260101T100000Z
        DTSTART:20260615T100000Z
        DTEND:20260615T110000Z
        SUMMARY:Quarterly review
        DESCRIPTION:Bring your slides
        SEQUENCE:3
        ORGANIZER;CN=The Boss:mailto:boss@example.test
        ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT:mailto:alice@example.test
        ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT;RSVP=TRUE:mailto:self@example.test
        ATTENDEE;CN=Bob;PARTSTAT=DECLINED;ROLE=REQ-PARTICIPANT:mailto:bob@example.test
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    private fun selfAccount(addresses: List<String> = listOf("mailto:self@example.test")) =
        org.onekash.kashcal.data.db.entity.Account(
            id = 1L,
            provider = org.onekash.kashcal.domain.model.AccountProvider.CALDAV,
            email = "self@example.test",
            calendarUserAddresses = addresses
        )

    @Test
    fun `patchAttendeeReply updates only self PARTSTAT`() {
        val patched = IcsPatcher.patchAttendeeReply(
            rawIcal = multiAttendeeIcs,
            account = selfAccount(),
            partstat = "ACCEPTED"
        )
        assertNotNull("patch should succeed", patched)
        val parsed = parser.parseAllEvents(patched!!).getOrNull()!!.first()

        // Three attendees survive.
        assertEquals(3, parsed.attendees.size)

        // Self's PARTSTAT updated to ACCEPTED.
        val self = parsed.attendees.first { it.email == "self@example.test" }
        assertEquals(org.onekash.icaldav.model.PartStat.ACCEPTED, self.partStat)

        // Others' PARTSTAT untouched.
        val alice = parsed.attendees.first { it.email == "alice@example.test" }
        assertEquals(org.onekash.icaldav.model.PartStat.ACCEPTED, alice.partStat)
        val bob = parsed.attendees.first { it.email == "bob@example.test" }
        assertEquals(org.onekash.icaldav.model.PartStat.DECLINED, bob.partStat)
    }

    @Test
    fun `patchAttendeeReply preserves SUMMARY DESCRIPTION ORGANIZER`() {
        val patched = IcsPatcher.patchAttendeeReply(
            rawIcal = multiAttendeeIcs,
            account = selfAccount(),
            partstat = "TENTATIVE"
        )!!
        val parsed = parser.parseAllEvents(patched).getOrNull()!!.first()
        assertEquals("Quarterly review", parsed.summary)
        assertEquals("Bring your slides", parsed.description)
        assertEquals("boss@example.test", parsed.organizer?.email)
    }

    @Test
    fun `patchAttendeeReply preserves SEQUENCE verbatim`() {
        // RFC 5546 §2.1.4: an attendee's PARTSTAT-only PUT must not bump SEQUENCE. iCloud
        // bumps it on the wire, which is tolerated; the client never bumps it.
        val patched = IcsPatcher.patchAttendeeReply(
            rawIcal = multiAttendeeIcs,
            account = selfAccount(),
            partstat = "ACCEPTED"
        )!!
        val parsed = parser.parseAllEvents(patched).getOrNull()!!.first()
        assertEquals(3, parsed.sequence)
    }

    @Test
    fun `patchAttendeeReply canonicalizes lowercase PARTSTAT to uppercase`() {
        // The RSVP UI may pass any case; the patcher canonicalizes.
        val patched = IcsPatcher.patchAttendeeReply(
            rawIcal = multiAttendeeIcs,
            account = selfAccount(),
            partstat = "accepted"
        )!!
        val parsed = parser.parseAllEvents(patched).getOrNull()!!.first()
        val self = parsed.attendees.first { it.email == "self@example.test" }
        assertEquals(org.onekash.icaldav.model.PartStat.ACCEPTED, self.partStat)
        // The wire form is uppercase too.
        assertTrue(
            "wire form must use uppercase ACCEPTED",
            patched.contains("PARTSTAT=ACCEPTED")
        )
    }

    @Test
    fun `patchAttendeeReply matches self via multi-alias account`() {
        // Account has both me.com and icloud.com aliases; the wire ATTENDEE
        // is the .icloud one.
        val icloudAliasIcs = multiAttendeeIcs.replace(
            "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT;RSVP=TRUE:mailto:self@example.test",
            "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT;RSVP=TRUE:mailto:self@icloud.example"
        )
        val account = selfAccount(
            addresses = listOf("mailto:self@me.example", "mailto:self@icloud.example")
        )

        val patched = IcsPatcher.patchAttendeeReply(
            rawIcal = icloudAliasIcs,
            account = account,
            partstat = "ACCEPTED"
        )!!
        val parsed = parser.parseAllEvents(patched).getOrNull()!!.first()
        val self = parsed.attendees.first { it.email == "self@icloud.example" }
        assertEquals(org.onekash.icaldav.model.PartStat.ACCEPTED, self.partStat)
    }

    @Test
    fun `patchAttendeeReply matches self when the address differs only in case and keeps its casing`() {
        val mixedCaseIcs = multiAttendeeIcs.replace(
            "RSVP=TRUE:mailto:self@example.test",
            "RSVP=TRUE:mailto:Self@Example.TEST"
        )

        val patched = IcsPatcher.patchAttendeeReply(
            rawIcal = mixedCaseIcs,
            account = selfAccount(),
            partstat = "ACCEPTED"
        )
        assertNotNull("case-only difference must still find the self attendee", patched)

        val unfolded = patched!!.replace(Regex("""\r?\n[ \t]"""), "")
        val selfLine = unfolded.lines().single { it.startsWith("ATTENDEE") && "CN=Self" in it }
        assertTrue("PARTSTAT updated: $selfLine", "PARTSTAT=ACCEPTED" in selfLine)
        assertTrue("original casing kept on the wire: $selfLine", selfLine.endsWith(":mailto:Self@Example.TEST"))
    }

    @Test
    fun `patchAttendeeReply returns null when self attendee not present`() {
        // The server's body doesn't list this account, so the caller shows an error ("can't
        // RSVP without an attendee row for you") instead of silently adding an attendee.
        val account = org.onekash.kashcal.data.db.entity.Account(
            id = 1L,
            provider = org.onekash.kashcal.domain.model.AccountProvider.CALDAV,
            email = "stranger@example.test",
            calendarUserAddresses = listOf("mailto:stranger@example.test")
        )

        val patched = IcsPatcher.patchAttendeeReply(
            rawIcal = multiAttendeeIcs,
            account = account,
            partstat = "ACCEPTED"
        )
        assertNull(patched)
    }

    @Test
    fun `patchAttendeeReply returns null when rawIcal is malformed`() {
        val patched = IcsPatcher.patchAttendeeReply(
            rawIcal = "this is not ICS",
            account = selfAccount(),
            partstat = "ACCEPTED"
        )
        assertNull(patched)
    }

    @Test
    fun `patchAttendeeReply preserves recurring RRULE`() {
        // Series-level RSVP only: the master's RRULE must survive.
        val recurringIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//RSVP-recur//EN
            BEGIN:VEVENT
            UID:weekly@kashcal.test
            DTSTAMP:20260101T100000Z
            DTSTART:20260615T100000Z
            DTEND:20260615T110000Z
            RRULE:FREQ=WEEKLY;COUNT=4
            SUMMARY:Weekly sync
            ORGANIZER;CN=Boss:mailto:boss@example.test
            ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION:mailto:self@example.test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = IcsPatcher.patchAttendeeReply(
            rawIcal = recurringIcs,
            account = selfAccount(),
            partstat = "ACCEPTED"
        )!!
        assertTrue("RRULE must survive RSVP patch", patched.contains("RRULE:FREQ=WEEKLY"))
        val parsed = parser.parseAllEvents(patched).getOrNull()!!.first()
        assertEquals(
            org.onekash.icaldav.model.PartStat.ACCEPTED,
            parsed.attendees.first { it.email == "self@example.test" }.partStat
        )
    }

    // ==================== Organizer-side ATTENDEE emission ====================

    private fun attendee(eventId: Long, address: String, partstat: String = "NEEDS-ACTION") =
        Attendee(eventId = eventId, address = address, partstat = partstat)

    @Test
    fun `generateFresh with attendees emits ATTENDEE lines`() {
        val event = createTestEvent(
            uid = "fresh-attendees@example.test",
            title = "Planning",
            startTs = 1_700_000_000_000L,
            endTs = 1_700_003_600_000L,
            // Without an ORGANIZER the fresh path emits no attendees (RFC 6638 §3.1); a real
            // invite has one.
            organizerEmail = "host@example.test"
        )
        val ics = IcsPatcher.generateFresh(
            event,
            attendees = listOf(
                attendee(event.id, "mailto:alice@example.test", "ACCEPTED"),
                attendee(event.id, "mailto:bob@example.test")
            )
        )
        assertTrue("alice must round-trip", ics.contains("alice@example.test"))
        assertTrue("bob must round-trip", ics.contains("bob@example.test"))
    }

    @Test
    fun `generateFresh without attendees emits no ATTENDEE - share-card PII guard`() {
        // The share-card path nulls rawIcal and passes no attendees so the recipient's .ics
        // never leaks the master's attendee list. The default empty list must keep
        // generateFresh attendee-free.
        val event = createTestEvent(
            uid = "share-card@example.test",
            title = "Private",
            startTs = 1_700_000_000_000L,
            endTs = 1_700_003_600_000L
        )
        val ics = IcsPatcher.generateFresh(event)
        assertFalse("share-card must not emit ATTENDEE", ics.contains("ATTENDEE"))
    }

    @Test
    fun `serializeWithExceptions emits attendees on master AND each exception VEVENT`() {
        val master = createTestEvent(
            uid = "recurring-attendees@example.test",
            title = "Weekly Sync",
            startTs = 1_700_000_000_000L,
            endTs = 1_700_003_600_000L,
            rrule = "FREQ=WEEKLY",
            organizerEmail = "host@example.test"
        )
        val exception = createExceptionEvent(
            masterId = master.id,
            masterUid = master.uid,
            originalInstanceTime = 1_700_086_400_000L,
            title = "Weekly Sync (moved)",
            startTs = 1_700_086_400_000L,
            endTs = 1_700_090_000_000L,
            organizerEmail = "host@example.test"
        )
        val masterAttendees = listOf(attendee(master.id, "mailto:alice@example.test", "ACCEPTED"))
        val exceptionAttendees = listOf(
            attendee(exception.id, "mailto:alice@example.test", "ACCEPTED"),
            attendee(exception.id, "mailto:carol@example.test")
        )

        val ics = IcsPatcher.serializeWithExceptions(
            master = master,
            masterAttendees = masterAttendees,
            exceptionsWithAttendees = listOf(exception to exceptionAttendees)
        )

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val masterVevent = events.first { it.recurrenceId == null }
        val exceptionVevent = events.first { it.recurrenceId != null }

        assertTrue(
            "master VEVENT must carry its attendee",
            masterVevent.attendees.any { it.email == "alice@example.test" }
        )
        // Each exception VEVENT carries its own attendees.
        assertTrue(
            "exception VEVENT must carry carol (previously dropped on push)",
            exceptionVevent.attendees.any { it.email == "carol@example.test" }
        )
    }

    // ==================== VEVENT order within one resource ====================
    //
    // A series with a changed occurrence is one resource holding two VEVENTs,
    // and RFC 5545 puts no order on them. A file may list the changed
    // occurrence (RECURRENCE-ID) before the series; a series edit or an invite
    // reply must still patch the series VEVENT.

    private val orderUid = "order-series@example.test"
    private val orderSeriesStart = 1_792_490_400_000L // 2026-10-20 10:00 UTC
    private val orderChangedInstance = 1_792_663_200_000L // 2026-10-22 10:00 UTC, third occurrence
    private val orderChangedStart = 1_792_670_400_000L // moved to 12:00

    private fun orderSeriesVevent(selfInvited: Boolean) = listOfNotNull(
        "BEGIN:VEVENT",
        "UID:$orderUid",
        "DTSTAMP:20261001T100000Z",
        "DTSTART:20261020T100000Z",
        "DTEND:20261020T110000Z",
        "RRULE:FREQ=DAILY;COUNT=5",
        "SUMMARY:Standup",
        "ORGANIZER;CN=Boss:mailto:boss@example.test",
        if (selfInvited) "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:self@example.test" else null,
        "ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.test",
        "END:VEVENT",
    )

    private fun orderChangeVevent(recurrenceId: String, start: String, end: String, title: String) = listOf(
        "BEGIN:VEVENT",
        "UID:$orderUid",
        "DTSTAMP:20261001T100000Z",
        "RECURRENCE-ID:$recurrenceId",
        "DTSTART:$start",
        "DTEND:$end",
        "SUMMARY:$title",
        "ORGANIZER;CN=Boss:mailto:boss@example.test",
        "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:self@example.test",
        "ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.test",
        "END:VEVENT",
    )

    private val orderChange = orderChangeVevent(
        "20261022T100000Z", "20261022T120000Z", "20261022T130000Z", "Standup moved"
    )

    private fun orderCalendar(vararg vevents: List<String>): String =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Order//EN") +
            vevents.toList().flatten() + listOf("END:VCALENDAR")).joinToString("\r\n")

    /** The changed occurrence is listed before the series. */
    private fun changeFirstIcs(selfOnSeries: Boolean = true) =
        orderCalendar(orderChange, orderSeriesVevent(selfInvited = selfOnSeries))

    /** Only changed occurrences, no series VEVENT (RFC 4791 section 4.1 allows it). */
    private val changesOnlyIcs get() = orderCalendar(
        orderChange,
        orderChangeVevent("20261023T100000Z", "20261023T120000Z", "20261023T130000Z", "Standup moved again"),
    )

    private fun renamedAndMovedSeries(rawIcal: String): Event = createTestEvent(
        uid = orderUid,
        title = "Standup renamed",
        startTs = orderSeriesStart + 3_600_000L, // series moved one hour later
        endTs = orderSeriesStart + 7_200_000L,
        rrule = "FREQ=DAILY;COUNT=5",
    ).copy(id = 1L, rawIcal = rawIcal)

    @Test
    fun `series edit on a file listing the changed occurrence first keeps one series with its repeat rule`() {
        val master = renamedAndMovedSeries(changeFirstIcs())
        val exception = createExceptionEvent(
            masterId = master.id,
            masterUid = orderUid,
            originalInstanceTime = orderChangedInstance,
            title = "Standup moved",
            startTs = orderChangedStart,
            endTs = orderChangedStart + 3_600_000L,
        ).copy(rawIcal = master.rawIcal)

        val ics = IcsPatcher.serializeWithExceptions(
            master = master,
            masterAttendees = null,
            exceptionsWithAttendees = listOf(exception to null),
        )

        val events = parser.parseAllEvents(ics).getOrNull()!!
        assertEquals("series plus one changed occurrence", 2, events.size)
        val series = events.filter { it.recurrenceId == null }
        assertEquals("exactly one series VEVENT", 1, series.size)
        val rule = series.single().rrule
        assertNotNull("series keeps its repeat rule", rule)
        assertEquals(org.onekash.icaldav.model.Frequency.DAILY, rule!!.freq)
        assertEquals(5, rule.count)
        assertEquals("Standup renamed", series.single().summary)
        assertEquals("series carries the new start", orderSeriesStart + 3_600_000L, series.single().dtStart.timestamp)

        val changes = events.filter { it.recurrenceId != null }
        assertEquals("exactly one changed occurrence", 1, changes.size)
        assertEquals(orderChangedInstance, changes.single().recurrenceId!!.timestamp)
        assertEquals("Standup moved", changes.single().summary)
        assertNull("changed occurrence has no repeat rule", changes.single().rrule)
    }

    @Test
    fun `series patch without exception rows on a file listing the changed occurrence first emits the series`() {
        val master = renamedAndMovedSeries(changeFirstIcs())

        val only = parser.parseAllEvents(IcsPatcher.serialize(master)).getOrNull()!!.single()

        assertNull("patched VEVENT is the series, not the changed occurrence", only.recurrenceId)
        assertNotNull("series keeps its repeat rule", only.rrule)
        assertEquals("Standup renamed", only.summary)
    }

    @Test
    fun `invite reply on a file listing the changed occurrence first answers the series`() {
        val patched = IcsPatcher.patchAttendeeReply(
            rawIcal = changeFirstIcs(),
            account = selfAccount(),
            partstat = "ACCEPTED",
        )

        assertNotNull("reply patch should succeed", patched)
        val events = parser.parseAllEvents(patched!!).getOrNull()!!
        assertEquals("the changed occurrence is sent back too", 2, events.size)
        val series = events.single { it.recurrenceId == null }
        assertNotNull("series keeps its repeat rule", series.rrule)
        assertEquals("Standup", series.summary)
        assertEquals(
            org.onekash.icaldav.model.PartStat.ACCEPTED,
            series.attendees.first { it.email == "self@example.test" }.partStat
        )
        val change = events.single { it.recurrenceId != null }
        assertEquals("Standup moved", change.summary)
        assertEquals(
            "the changed occurrence keeps its own answer",
            org.onekash.icaldav.model.PartStat.NEEDS_ACTION,
            change.attendees.first { it.email == "self@example.test" }.partStat
        )
    }

    @Test
    fun `invite reply is refused when the account is a guest only on a changed occurrence listed first`() {
        // Same answer as when the series is listed first: the reply patches the
        // series, and the account isn't a guest there.
        val seriesFirst = IcsPatcher.patchAttendeeReply(
            orderCalendar(orderSeriesVevent(selfInvited = false), orderChange), selfAccount(), "ACCEPTED"
        )
        val changeFirst = IcsPatcher.patchAttendeeReply(
            changeFirstIcs(selfOnSeries = false), selfAccount(), "ACCEPTED"
        )

        assertNull("series-first file: no reply", seriesFirst)
        assertNull("changed-occurrence-first file: no reply either", changeFirst)
    }

    @Test
    fun `file holding only changed occurrences still patches its first VEVENT`() {
        val reply = IcsPatcher.patchAttendeeReply(changesOnlyIcs, selfAccount(), "ACCEPTED")
        assertNotNull("reply patch still succeeds", reply)
        val events = parser.parseAllEvents(reply!!).getOrNull()!!
        assertEquals("both changed occurrences are sent back", 2, events.size)
        val replied = events.first()
        assertEquals(orderChangedInstance, replied.recurrenceId?.timestamp)
        assertEquals(
            org.onekash.icaldav.model.PartStat.ACCEPTED,
            replied.attendees.first { it.email == "self@example.test" }.partStat
        )

        val seriesRow = createTestEvent(
            uid = orderUid,
            title = "Standup renamed",
            startTs = orderChangedStart,
            endTs = orderChangedStart + 3_600_000L,
        ).copy(id = 1L, rawIcal = changesOnlyIcs)
        val patched = parser.parseAllEvents(IcsPatcher.serialize(seriesRow)).getOrNull()!!.single()
        assertEquals("first VEVENT is patched", orderChangedInstance, patched.recurrenceId?.timestamp)
        assertTrue(
            "patch path kept, not fresh generation (attendees survive)",
            patched.attendees.any { it.email == "alice@example.test" }
        )
    }

    @Test
    fun `exception row patch still takes the first VEVENT of the file`() {
        val exception = createExceptionEvent(
            masterId = 1L,
            masterUid = orderUid,
            originalInstanceTime = orderChangedInstance,
            title = "Standup moved",
            startTs = orderChangedStart,
            endTs = orderChangedStart + 3_600_000L,
        ).copy(rawIcal = changeFirstIcs())

        val patched = parser.parseAllEvents(IcsPatcher.serialize(exception)).getOrNull()!!.single()

        assertEquals(orderChangedInstance, patched.recurrenceId?.timestamp)
    }

    // ========== Unknown properties are sent back as the server sent them ==========

    private val unknownUid = "unknown-props@example.test"

    /** Shapes seen on real events; each was damaged when rebuilt from decoded values. */
    private val seriesUnknownLines = listOf(
        "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-ADDRESS=\"10600 N Tantau Ave\\nCupertino, CA 95014\";X-TITLE=\"Apple Park: Visitor Center\":geo:37.332,-122.005",
        "X-APPLE-SUGGESTION-INFO-UNIQUE-KEY:mail\\,msg-1234\\;part\\=2",
        "COMMENT:line one\\nline two\\, with comma",
        "RESOURCES:Projector",
        "RESOURCES:Whiteboard",
    )
    private val occurrenceUnknownLines = listOf(
        "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-TITLE=\"Cafe, Main St\":geo:1.0,2.0",
        "COMMENT:moved\\nthis week only",
    )
    private val unknownSeriesStart = 1_796_306_400_000L // 2026-12-03T14:00:00Z
    private val unknownChangedInstance = unknownSeriesStart + 7 * 86_400_000L

    private fun unknownResource(
        occurrenceRecurrenceId: String = "RECURRENCE-ID:20261210T140000Z",
        occurrenceLines: List<String> = occurrenceUnknownLines,
        withAttendee: Boolean = false,
    ): String = (
        listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN",
            "BEGIN:VEVENT", "UID:$unknownUid", "DTSTAMP:20260101T100000Z",
            "DTSTART:20261203T140000Z", "DTEND:20261203T150000Z", "RRULE:FREQ=WEEKLY;COUNT=5",
            "SUMMARY:Series",
        ) + (if (withAttendee) listOf(
            "ORGANIZER:mailto:organizer@example.test",
            "ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:reader@example.test"
        ) else emptyList()) + seriesUnknownLines + listOf(
            "END:VEVENT",
            "BEGIN:VEVENT", "UID:$unknownUid", "DTSTAMP:20260101T100000Z",
            occurrenceRecurrenceId, "DTSTART:20261210T160000Z", "DTEND:20261210T170000Z",
            "SUMMARY:Changed occurrence",
        ) + occurrenceLines + listOf("END:VEVENT", "END:VCALENDAR", "")
        ).joinToString("\r\n")

    /** Rows as pull stores them: every row carries the whole resource body. */
    private fun pulledRows(resource: String): Pair<Event, Event> {
        val parsed = parser.parseAllEvents(resource).getOrNull()!!
        val seriesParsed = parsed.single { it.recurrenceId == null }
        val series = ICalEventMapper.toEntity(seriesParsed, resource, 1L, "https://example.test/cal/u.ics", "e1")
            .event.copy(id = 1L)
        val exception = ICalEventMapper.toEntity(
            parsed.single { it.recurrenceId != null }, resource, 1L, "https://example.test/cal/u.ics", "e1",
            masterDtStart = seriesParsed.dtStart
        ).event.copy(id = 2L, originalEventId = 1L)
        return series to exception
    }

    /** An occurrence row the way the writer builds it: a copy of the series row. */
    private fun rowDerivedFromSeries(series: Event, occurrenceMs: Long, title: String) =
        series.copy(
            id = 3L, title = title, rrule = null, originalEventId = series.id, originalInstanceTime = occurrenceMs,
            startTs = occurrenceMs, endTs = occurrenceMs + 3_600_000L
        )

    private fun unfoldIcs(ics: String) = ics.replace(Regex("\r?\n[ \t]"), "")

    private fun veventBlocks(ics: String): List<List<String>> {
        val blocks = mutableListOf<List<String>>()
        var current: MutableList<String>? = null
        var depth = 0
        for (line in unfoldIcs(ics).split("\r\n", "\n")) {
            when {
                current == null && line == "BEGIN:VEVENT" -> { current = mutableListOf(); depth = 0 }
                current == null -> Unit
                line == "END:VEVENT" && depth == 0 -> { blocks += current; current = null }
                line.startsWith("BEGIN:") -> depth++
                line.startsWith("END:") -> depth--
                depth == 0 -> current += line
            }
        }
        return blocks
    }

    private fun blockTitled(ics: String, title: String) =
        veventBlocks(ics).firstOrNull { "SUMMARY:$title" in it } ?: throw AssertionError("no '$title' in $ics")

    private fun unknownIn(block: List<String>) =
        block.filter { it in seriesUnknownLines || it in occurrenceUnknownLines }

    @Test
    fun `editing a pulled event sends every unknown line back unchanged`() {
        val (series, _) = pulledRows(unknownResource())
        val out = IcsPatcher.serialize(series.copy(title = "Series edited", startTs = series.startTs + 3_600_000L))
        assertEquals(seriesUnknownLines, unknownIn(blockTitled(out, "Series edited")))
    }

    @Test
    fun `replying to an invitation sends every unknown line back unchanged`() {
        val resource = unknownResource(withAttendee = true)
        val reader = org.onekash.kashcal.data.db.entity.Account(
            provider = org.onekash.kashcal.domain.model.AccountProvider.CALDAV, email = "reader@example.test"
        )
        val out = IcsPatcher.patchAttendeeReply(resource, reader, "ACCEPTED")!!
        val block = blockTitled(out, "Series")
        assertEquals(seriesUnknownLines, unknownIn(block))
        assertTrue(block.any { it.startsWith("ATTENDEE") && "PARTSTAT=ACCEPTED" in it })
    }

    @Test
    fun `a pulled changed occurrence is sent with its own unknown lines`() {
        val (series, exception) = pulledRows(unknownResource())
        val out = IcsPatcher.serializeWithExceptions(series, listOf(exception))
        assertEquals(seriesUnknownLines, unknownIn(blockTitled(out, "Series")))
        assertEquals(occurrenceUnknownLines, unknownIn(blockTitled(out, "Changed occurrence")))
    }

    @Test
    fun `a re-edited changed occurrence built from the series row still sends its own lines`() {
        val (series, _) = pulledRows(unknownResource())
        val reEdited = rowDerivedFromSeries(series, unknownChangedInstance, "Changed again")
        val out = IcsPatcher.serializeWithExceptions(series, listOf(reEdited))
        assertEquals(occurrenceUnknownLines, unknownIn(blockTitled(out, "Changed again")))
    }

    @Test
    fun `a newly changed occurrence is sent with the series' lines unchanged`() {
        val (series, exception) = pulledRows(unknownResource())
        val fresh = rowDerivedFromSeries(series, unknownChangedInstance + 7 * 86_400_000L, "New occurrence")
        val out = IcsPatcher.serializeWithExceptions(series, listOf(exception, fresh))
        assertEquals(seriesUnknownLines, unknownIn(blockTitled(out, "New occurrence")))
    }

    @Test
    fun `a changed occurrence with no unknown lines sends none, not the series' copy`() {
        val (series, _) = pulledRows(unknownResource(occurrenceLines = emptyList()))
        val reEdited = rowDerivedFromSeries(series, unknownChangedInstance, "Changed again")
        val out = IcsPatcher.serializeWithExceptions(series, listOf(reEdited))
        val block = blockTitled(out, "Changed again")
        assertEquals(emptyList<String>(), unknownIn(block))
        assertTrue(block.none { it.startsWith("X-APPLE-SUGGESTION-INFO-UNIQUE-KEY") || it.startsWith("RESOURCES") })
    }

    @Test
    fun `an occurrence that matches nothing and is not a series copy keeps today's map output`() {
        val (series, exception) = pulledRows(unknownResource())
        val stray = exception.copy(
            id = 4L, importId = "$unknownUid:RECID:other", title = "Stray",
            originalInstanceTime = unknownChangedInstance + 21 * 86_400_000L,
            extraProperties = mapOf("X-STRAY" to "kept")
        )
        val block = blockTitled(IcsPatcher.serializeWithExceptions(series, listOf(stray)), "Stray")
        assertTrue(block.contains("X-STRAY:kept"))
    }

    @Test
    fun `a series without a stored body sends its occurrences from the map as before`() {
        val (series, exception) = pulledRows(unknownResource())
        val local = series.copy(rawIcal = null, extraProperties = mapOf("X-LOCAL" to "series"))
        val localException = exception.copy(rawIcal = null, extraProperties = mapOf("X-LOCAL" to "occurrence"))
        val out = IcsPatcher.serializeWithExceptions(local, listOf(localException))
        assertTrue(blockTitled(out, "Changed occurrence").contains("X-LOCAL:occurrence"))
    }

    @Test
    fun `an unreadable stored body falls back to today's output without throwing`() {
        val (series, exception) = pulledRows(unknownResource())
        val broken = "not an icalendar body"
        val out = IcsPatcher.serializeWithExceptions(
            series.copy(rawIcal = broken, extraProperties = mapOf("X-MAP" to "series")),
            listOf(exception.copy(rawIcal = broken, extraProperties = mapOf("X-MAP" to "occurrence")))
        )
        assertTrue(blockTitled(out, "Series").contains("X-MAP:series"))
        assertTrue(blockTitled(out, "Changed occurrence").contains("X-MAP:occurrence"))
    }

    @Test
    fun `a body holding only changed occurrences still gives each its own lines`() {
        // RFC 4791 §4.1 allows a resource with only overridden instances; the
        // series row is then a placeholder and the body has no series VEVENT.
        val resource = (listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN",
            "BEGIN:VEVENT", "UID:$unknownUid", "DTSTAMP:20260101T100000Z",
            "RECURRENCE-ID:20261210T140000Z", "DTSTART:20261210T160000Z", "DTEND:20261210T170000Z",
            "SUMMARY:Changed occurrence",
        ) + occurrenceUnknownLines + listOf("END:VEVENT", "END:VCALENDAR", "")).joinToString("\r\n")
        val parsed = parser.parseAllEvents(resource).getOrNull()!!.single()
        val placeholder = createTestEvent(
            uid = unknownUid, title = "Placeholder", startTs = unknownSeriesStart,
            endTs = unknownSeriesStart + 3_600_000L, rrule = "FREQ=WEEKLY;COUNT=5"
        ).copy(id = 1L, rawIcal = resource)
        val exception = ICalEventMapper.toEntity(parsed, resource, 1L, "https://example.test/cal/u.ics", "e1")
            .event.copy(id = 2L, originalEventId = 1L)

        val out = IcsPatcher.serializeWithExceptions(placeholder, listOf(exception))
        assertEquals(occurrenceUnknownLines, unknownIn(blockTitled(out, "Changed occurrence")))
    }

    @Test
    fun `an occurrence of a split-off series never takes the old series' changed occurrence`() {
        val (series, _) = pulledRows(unknownResource())
        // A this-and-future split copies the series row (stored body included)
        // under a new UID.
        val splitOff = series.copy(uid = "split-off@example.test")
        val fresh = rowDerivedFromSeries(splitOff, unknownChangedInstance, "Edited after split")
        val block = blockTitled(IcsPatcher.serializeWithExceptions(splitOff, listOf(fresh)), "Edited after split")
        assertTrue(block.none { it in occurrenceUnknownLines })
        assertEquals(seriesUnknownLines, unknownIn(block))
    }

    @Test
    fun `a date-form occurrence id still matches after the series time was changed locally`() {
        val resource = unknownResource(occurrenceRecurrenceId = "RECURRENCE-ID;VALUE=DATE:20261210")
        val (series, _) = pulledRows(resource)
        // The user moved the series an hour later; the stored body still holds 14:00.
        val shifted = series.copy(startTs = series.startTs + 3_600_000L, endTs = series.endTs + 3_600_000L)
        val reEdited = rowDerivedFromSeries(shifted, unknownChangedInstance, "Changed again")
        val out = IcsPatcher.serializeWithExceptions(shifted, listOf(reEdited))
        assertEquals(occurrenceUnknownLines, unknownIn(blockTitled(out, "Changed again")))
    }

    // ==================== Invite reply on a series with changed occurrences ====================
    //
    // A PUT replaces the whole resource, so a reply must send every VEVENT back
    // as the server holds it; only this account's answer on one VEVENT changes.

    private val replyUid = "reply-series@example.test"
    private val berlin = java.time.ZoneId.of("Europe/Berlin")

    private fun berlinMs(date: String) =
        java.time.LocalDateTime.parse(date).atZone(berlin).toInstant().toEpochMilli()

    /** Instance times of the moved, the retitled and the already-answered occurrence. */
    private val movedInstance get() = berlinMs("2026-11-04T10:00")
    private val retitledInstance get() = berlinMs("2026-11-05T10:00")
    private val answeredInstance get() = berlinMs("2026-11-06T10:00")

    /** Folds one logical line into RFC 5545 physical lines of at most 75 octets. */
    private fun fold75(line: String): List<String> {
        val out = mutableListOf<String>()
        var current = StringBuilder()
        var octets = 0
        line.codePoints().forEach { cp ->
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (octets + size > 75) {
                out += current.toString()
                current = StringBuilder(" ")
                octets = 1
            }
            current.appendCodePoint(cp)
            octets += size
        }
        out += current.toString()
        return out
    }

    private val selfSeriesLine =
        "ATTENDEE;CN=\"Self, the attendee with a long name: Zoë\";ROLE=REQ-PARTICIPANT;" +
            "PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:self@example.test"

    private fun replyResource(eol: String = "\r\n"): String = (listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Organizer//Client 9.1//EN",
        "BEGIN:VTIMEZONE", "TZID:Europe/Berlin",
        "BEGIN:STANDARD", "DTSTART:19701025T030000", "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU",
        "TZOFFSETFROM:+0200", "TZOFFSETTO:+0100", "TZNAME:CET", "END:STANDARD",
        "BEGIN:DAYLIGHT", "DTSTART:19700329T020000", "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU",
        "TZOFFSETFROM:+0100", "TZOFFSETTO:+0200", "TZNAME:CEST", "END:DAYLIGHT",
        "END:VTIMEZONE",
        // Series
        "BEGIN:VEVENT", "UID:$replyUid", "DTSTAMP:20261001T100000Z",
        "DTSTART;TZID=Europe/Berlin:20261103T100000", "DTEND;TZID=Europe/Berlin:20261103T110000",
        "RRULE:FREQ=DAILY;COUNT=6", "SUMMARY:Planning", "SEQUENCE:2",
    ) + fold75(
        "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-ADDRESS=\"Main St 1\\nBerlin\";X-TITLE=\"Room: 4; west\":geo:52.5,13.4"
    ) + listOf(
        "ORGANIZER;CN=Boss:mailto:boss@example.test",
    ) + fold75(selfSeriesLine) + listOf(
        "ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.test",
        "BEGIN:VALARM", "ACTION:EMAIL", "TRIGGER:-PT30M", "SUMMARY:Reminder", "DESCRIPTION:Planning soon",
        "ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:self@example.test", "END:VALARM",
        "END:VEVENT",
        // Moved occurrence
        "BEGIN:VEVENT", "UID:$replyUid", "DTSTAMP:20261001T100000Z",
        "RECURRENCE-ID;TZID=Europe/Berlin:20261104T100000",
        "DTSTART;TZID=Europe/Berlin:20261104T140000", "DTEND;TZID=Europe/Berlin:20261104T150000",
        "SUMMARY:Planning", "SEQUENCE:2", "X-MOVED-BY:organizer",
        "ORGANIZER;CN=Boss:mailto:boss@example.test",
        "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:self@example.test",
        "ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.test",
        "END:VEVENT",
        // Retitled occurrence with an extra guest
        "BEGIN:VEVENT", "UID:$replyUid", "DTSTAMP:20261001T100000Z",
        "RECURRENCE-ID;TZID=Europe/Berlin:20261105T100000",
        "DTSTART;TZID=Europe/Berlin:20261105T100000", "DTEND;TZID=Europe/Berlin:20261105T110000",
        "SUMMARY:Planning with finance", "SEQUENCE:2",
        "ORGANIZER;CN=Boss:mailto:boss@example.test",
        "ATTENDEE;CN=Self;RSVP=TRUE;PARTSTAT=NEEDS-ACTION:mailto:self@example.test",
        "ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.test",
        "ATTENDEE;CN=Finance;PARTSTAT=NEEDS-ACTION:mailto:finance@example.test",
        "END:VEVENT",
        // Occurrence this account already answered
        "BEGIN:VEVENT", "UID:$replyUid", "DTSTAMP:20261001T100000Z",
        "RECURRENCE-ID;TZID=Europe/Berlin:20261106T100000",
        "DTSTART;TZID=Europe/Berlin:20261106T100000", "DTEND;TZID=Europe/Berlin:20261106T113000",
        "SUMMARY:Planning (long)", "SEQUENCE:2",
        "ORGANIZER;CN=Boss:mailto:boss@example.test",
        "ATTENDEE;CN=Self;PARTSTAT=TENTATIVE:mailto:self@example.test",
        "END:VEVENT",
        "END:VCALENDAR", "",
    )).joinToString(eol)

    private fun unfoldedLines(ics: String) = unfoldIcs(ics).split(Regex("""\r?\n"""))

    /** Returns the indices of logical lines that differ; both files must have as many lines. */
    private fun changedLines(before: String, after: String): List<Int> {
        val a = unfoldedLines(before)
        val b = unfoldedLines(after)
        assertEquals("same number of logical lines", a.size, b.size)
        return a.indices.filter { a[it] != b[it] }
    }

    private fun veventTexts(ics: String) =
        Regex("""BEGIN:VEVENT.*?END:VEVENT""", RegexOption.DOT_MATCHES_ALL).findAll(ics).map { it.value }.toList()

    @Test
    fun `series reply sends every changed occurrence back byte for byte`() {
        val before = replyResource()
        val after = IcsPatcher.patchAttendeeReply(before, selfAccount(), "ACCEPTED")!!

        val blocksBefore = veventTexts(before)
        val blocksAfter = veventTexts(after)
        assertEquals("series and three changed occurrences", 4, blocksAfter.size)
        for (k in 1..3) assertEquals("changed occurrence $k unchanged", blocksBefore[k], blocksAfter[k])
        assertTrue("series keeps its repeat rule", blocksAfter[0].contains("RRULE:FREQ=DAILY;COUNT=6"))
    }

    @Test
    fun `series reply changes only this account's answer on the series`() {
        val before = replyResource()
        val after = IcsPatcher.patchAttendeeReply(before, selfAccount(), "TENTATIVE")!!

        val changed = changedLines(before, after)
        assertEquals("exactly one logical line changes", 1, changed.size)
        assertEquals(
            selfSeriesLine.replace("PARTSTAT=NEEDS-ACTION", "PARTSTAT=TENTATIVE"),
            unfoldedLines(after)[changed.single()]
        )
    }

    @Test
    fun `series reply leaves the email alarm's attendee alone`() {
        val after = IcsPatcher.patchAttendeeReply(replyResource(), selfAccount(), "DECLINED")!!
        assertTrue(unfoldedLines(after).contains("ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:self@example.test"))
    }

    @Test
    fun `reply refolds the edited line to at most 75 octets`() {
        for (answer in listOf("ACCEPTED", "DECLINED", "TENTATIVE")) {
            val after = IcsPatcher.patchAttendeeReply(replyResource(), selfAccount(), answer)!!
            after.split("\r\n").forEach { line ->
                assertTrue("line over 75 octets: $line", line.toByteArray(Charsets.UTF_8).size <= 75)
            }
            val parsed = parser.parseAllEvents(after).getOrNull()!!
            val self = parsed.first { it.recurrenceId == null }.attendees.first { it.email == "self@example.test" }
            assertEquals(answer, self.partStat.toICalString())
            assertEquals("Self, the attendee with a long name: Zoë", self.name)
        }
    }

    @Test
    fun `reply to one changed occurrence changes only that occurrence`() {
        val before = replyResource()
        val after = IcsPatcher.patchAttendeeReply(before, selfAccount(), "ACCEPTED", occurrence = retitledInstance)!!

        val changed = changedLines(before, after)
        assertEquals("exactly one logical line changes", 1, changed.size)
        assertEquals(
            "ATTENDEE;CN=Self;RSVP=TRUE;PARTSTAT=ACCEPTED:mailto:self@example.test",
            unfoldedLines(after)[changed.single()]
        )
        val blocks = veventTexts(after)
        assertTrue("the edit is in the retitled occurrence", blocks[2].contains("PARTSTAT=ACCEPTED:mailto:self@"))
    }

    @Test
    fun `reply to an occurrence already answered replaces that answer`() {
        val after = IcsPatcher.patchAttendeeReply(replyResource(), selfAccount(), "DECLINED", occurrence = answeredInstance)!!
        assertTrue(veventTexts(after)[3].contains("ATTENDEE;CN=Self;PARTSTAT=DECLINED:mailto:self@example.test"))
        assertEquals(veventTexts(replyResource()).take(3), veventTexts(after).take(3))
    }

    @Test
    fun `reply to an occurrence the file does not hold is refused`() {
        val notChanged = berlinMs("2026-11-07T10:00")
        assertNull(IcsPatcher.patchAttendeeReply(replyResource(), selfAccount(), "ACCEPTED", occurrence = notChanged))
    }

    @Test
    fun `reply to an occurrence this account is not invited to is refused`() {
        val resource = replyResource().replace(
            "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:self@example.test\r\n", ""
        )
        assertNull(IcsPatcher.patchAttendeeReply(resource, selfAccount(), "ACCEPTED", occurrence = movedInstance))
    }

    @Test
    fun `reply to an unreadable file is refused`() {
        assertNull(IcsPatcher.patchAttendeeReply("BEGIN:VCALENDAR\r\nnot a calendar", selfAccount(), "ACCEPTED"))
    }

    @Test
    fun `reply adds PARTSTAT when the attendee line has none and keeps a lower-case one's name`() {
        val noPartstat = replyResource().replace(
            "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:self@example.test",
            "ATTENDEE;CN=Self;RSVP=TRUE:MAILTO:Self@Example.test"
        )
        val added = IcsPatcher.patchAttendeeReply(noPartstat, selfAccount(), "accepted", occurrence = movedInstance)!!
        assertTrue(veventTexts(added)[1].contains("ATTENDEE;CN=Self;RSVP=TRUE;PARTSTAT=ACCEPTED:MAILTO:Self@Example.test"))

        val lowerCase = replyResource().replace(
            "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:self@example.test",
            "ATTENDEE;CN=Self;partstat=needs-action;RSVP=TRUE:mailto:self@example.test"
        )
        val replaced = IcsPatcher.patchAttendeeReply(lowerCase, selfAccount(), "ACCEPTED", occurrence = movedInstance)!!
        assertTrue(veventTexts(replaced)[1].contains("ATTENDEE;CN=Self;partstat=ACCEPTED;RSVP=TRUE:mailto:self@example.test"))
    }

    @Test
    fun `reply patches only the first line when the account is listed twice`() {
        val twice = replyResource().replace(
            "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:self@example.test",
            "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:self@example.test\r\n" +
                "ATTENDEE;CN=Alias;PARTSTAT=NEEDS-ACTION:mailto:alias@example.test"
        )
        val account = selfAccount(listOf("mailto:self@example.test", "mailto:alias@example.test"))
        val after = IcsPatcher.patchAttendeeReply(twice, account, "ACCEPTED", occurrence = movedInstance)!!
        val block = veventTexts(after)[1]
        assertTrue(block.contains("ATTENDEE;CN=Self;PARTSTAT=ACCEPTED;RSVP=TRUE:mailto:self@example.test"))
        assertTrue(block.contains("ATTENDEE;CN=Alias;PARTSTAT=NEEDS-ACTION:mailto:alias@example.test"))
    }

    @Test
    fun `reply keeps a file's plain line feeds`() {
        val before = replyResource(eol = "\n")
        val after = IcsPatcher.patchAttendeeReply(before, selfAccount(), "ACCEPTED")!!
        assertFalse("no carriage return introduced", after.contains('\r'))
        assertEquals(1, changedLines(before, after).size)
    }

    @Test
    fun `reply to an occurrence of a file with no series is keyed by its own occurrence time`() {
        val before = changesOnlyIcs
        val second = 1_792_749_600_000L // 2026-10-23 10:00 UTC
        val after = IcsPatcher.patchAttendeeReply(before, selfAccount(), "ACCEPTED", occurrence = second)!!
        val blocks = veventTexts(after)
        assertEquals(veventTexts(before)[0], blocks[0])
        assertTrue(blocks[1].contains("PARTSTAT=ACCEPTED;RSVP=TRUE:mailto:self@example.test"))
    }

    @Test
    fun `a date-form occurrence id against a timed series still finds the occurrence`() {
        val dateForm = replyResource().replace(
            "RECURRENCE-ID;TZID=Europe/Berlin:20261105T100000", "RECURRENCE-ID;VALUE=DATE:20261105"
        )
        val after = IcsPatcher.patchAttendeeReply(dateForm, selfAccount(), "ACCEPTED", occurrence = retitledInstance)
        assertNotNull(after)
        assertTrue(veventTexts(after!!)[2].contains("PARTSTAT=ACCEPTED:mailto:self@example.test"))
    }


    @Test
    fun `reply finds this account when the fold splits its address`() {
        val line = "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:self@example.test"
        val cut = line.indexOf("self@") + 3 // the fold falls inside the address
        val folded = replyResource().replace(line, line.substring(0, cut) + "\r\n " + line.substring(cut))
        val after = IcsPatcher.patchAttendeeReply(folded, selfAccount(), "ACCEPTED", occurrence = movedInstance)!!
        assertTrue(veventTexts(unfoldIcs(after))[1].contains("PARTSTAT=ACCEPTED;RSVP=TRUE:mailto:self@example.test"))
        assertEquals(1, changedLines(folded, after).size)
    }

    @Test
    fun `reply finds an occurrence whose id is written in another time zone`() {
        // 10:00 in Berlin is 04:00 in New York on 2026-11-05.
        val otherZone = replyResource().replace(
            "RECURRENCE-ID;TZID=Europe/Berlin:20261105T100000", "RECURRENCE-ID;TZID=America/New_York:20261105T040000"
        )
        val after = IcsPatcher.patchAttendeeReply(otherZone, selfAccount(), "ACCEPTED", occurrence = retitledInstance)
        assertNotNull(after)
        assertTrue(veventTexts(after!!)[2].contains("PARTSTAT=ACCEPTED:mailto:self@example.test"))
    }

    @Test
    fun `reply with no stored file is refused`() {
        assertNull(IcsPatcher.patchAttendeeReply(null, selfAccount(), "ACCEPTED"))
    }

    @Test
    fun `reply to a file starting with a byte order mark still answers`() {
        val before = "\uFEFF" + replyResource()
        val after = IcsPatcher.patchAttendeeReply(before, selfAccount(), "ACCEPTED")
        assertNotNull(after)
        assertTrue("the mark is kept", after!!.startsWith("\uFEFF"))
        assertEquals(1, changedLines(before, after).size)
    }

    @Test
    fun `reply finds an attendee written as a principal URL with an EMAIL parameter`() {
        // Some servers rewrite an attendee who has answered to their principal
        // URL, keeping the address in EMAIL; the parser reads it from there.
        val principal = replyResource().replace(
            "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:self@example.test",
            "ATTENDEE;CN=Self;PARTSTAT=ACCEPTED;EMAIL=self@example.test:/123456/principal/"
        )
        val after = IcsPatcher.patchAttendeeReply(principal, selfAccount(), "DECLINED", occurrence = movedInstance)
        assertNotNull(after)
        val block = veventTexts(unfoldIcs(after!!))[1]
        assertTrue(block, block.contains("ATTENDEE;CN=Self;PARTSTAT=DECLINED;EMAIL=self@example.test:/123456/principal/"))
    }

    @Test
    fun `reply writes the canonical answer whatever case it was given in`() {
        val after = IcsPatcher.patchAttendeeReply(replyResource(), selfAccount(), " tentative ", occurrence = movedInstance)!!
        assertTrue(veventTexts(after)[1].contains("PARTSTAT=TENTATIVE;RSVP=TRUE:mailto:self@example.test"))
    }
}
