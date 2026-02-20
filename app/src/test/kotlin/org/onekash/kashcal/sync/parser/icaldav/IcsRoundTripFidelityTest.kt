package org.onekash.kashcal.sync.parser.icaldav

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Round-trip fidelity tests: Entity → IcsPatcher.generateFresh() → ICalParser → ICalEventMapper → Entity.
 *
 * Complements Rfc7986ExtendedPropertiesTest (COLOR/GEO/URL/CATEGORIES) by testing
 * remaining properties: RRULE, EXDATE, RDATE, alarms, SEQUENCE, STATUS, CLASS,
 * TRANSP, LOCATION, DESCRIPTION, organizer, all-day, timezone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsRoundTripFidelityTest {

    private lateinit var parser: ICalParser

    @Before
    fun setup() {
        parser = ICalParser()
    }

    // ========== RRULE ==========

    @Test
    fun `RRULE round-trip preserves FREQ BYDAY COUNT`() {
        val event = createEvent(rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=10")
        val ics = IcsPatcher.generateFresh(event)
        val reparsed = parseToEntity(ics)

        assertNotNull("RRULE should survive round-trip", reparsed.rrule)
        assertTrue("Should contain FREQ=WEEKLY", reparsed.rrule!!.contains("FREQ=WEEKLY"))
        assertTrue("Should contain BYDAY", reparsed.rrule!!.contains("BYDAY"))
        assertTrue("Should contain COUNT=10", reparsed.rrule!!.contains("COUNT=10"))
    }

    @Test
    fun `RRULE round-trip preserves UNTIL in UTC`() {
        val event = createEvent(rrule = "FREQ=DAILY;UNTIL=20260301T000000Z")
        val ics = IcsPatcher.generateFresh(event)
        val reparsed = parseToEntity(ics)

        assertNotNull("RRULE with UNTIL should survive round-trip", reparsed.rrule)
        assertTrue("Should contain UNTIL", reparsed.rrule!!.contains("UNTIL"))
    }

    // ========== EXDATE ==========

    @Test
    fun `EXDATE round-trip preserves exclusion dates`() {
        // Store as CSV timestamps (how KashCal stores them in Room)
        val ts1 = ZonedDateTime.of(2026, 2, 10, 9, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val ts2 = ZonedDateTime.of(2026, 2, 17, 9, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val event = createEvent(
            rrule = "FREQ=WEEKLY;BYDAY=TU;COUNT=10",
            exdate = "$ts1,$ts2"
        )
        val ics = IcsPatcher.generateFresh(event)
        val reparsed = parseToEntity(ics)

        assertNotNull("EXDATE should survive round-trip", reparsed.exdate)
        val exdateTimestamps = reparsed.exdate!!.split(",").map { it.trim().toLong() }
        assertEquals("Should have 2 exclusion dates", 2, exdateTimestamps.size)
    }

    // ========== RDATE ==========

    @Test
    fun `RDATE round-trip preserves additional dates`() {
        val ts1 = ZonedDateTime.of(2026, 3, 1, 9, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val ts2 = ZonedDateTime.of(2026, 3, 15, 9, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val event = createEvent(rdate = "$ts1,$ts2")
        val ics = IcsPatcher.generateFresh(event)

        // Verify RDATE appears in generated ICS
        val rdateLine = ics.lines().find { it.startsWith("RDATE") }
        assertNotNull("RDATE should appear in generated ICS", rdateLine)

        val reparsed = parseToEntity(ics)
        assertNotNull("RDATE should survive round-trip", reparsed.rdate)
    }

    // ========== Alarms ==========

    @Test
    fun `alarm round-trip preserves trigger duration`() {
        val event = createEvent(reminders = listOf("-PT15M"))
        val ics = IcsPatcher.generateFresh(event)
        val reparsed = parseToEntity(ics)

        assertNotNull("Reminders should survive round-trip", reparsed.reminders)
        assertEquals("Should have 1 reminder", 1, reparsed.reminders!!.size)
        assertEquals("-PT15M", reparsed.reminders!![0])
    }

    @Test
    fun `multiple alarms round-trip preserves all triggers`() {
        val event = createEvent(reminders = listOf("-PT15M", "-PT1H", "-P1D"))
        val ics = IcsPatcher.generateFresh(event)
        val reparsed = parseToEntity(ics)

        assertNotNull("Reminders should survive round-trip", reparsed.reminders)
        assertEquals("Should have 3 reminders", 3, reparsed.reminders!!.size)
    }

    // ========== SEQUENCE ==========

    @Test
    fun `SEQUENCE round-trip preserves value`() {
        val event = createEvent(sequence = 7)
        val ics = IcsPatcher.generateFresh(event)
        val reparsed = parseToEntity(ics)

        assertEquals("SEQUENCE should survive round-trip", 7, reparsed.sequence)
    }

    // ========== STATUS ==========

    @Test
    fun `STATUS round-trip preserves TENTATIVE`() {
        val event = createEvent(status = "TENTATIVE")
        val ics = IcsPatcher.generateFresh(event)
        val reparsed = parseToEntity(ics)

        assertEquals("STATUS should survive round-trip", "TENTATIVE", reparsed.status)
    }

    // ========== CLASS ==========

    @Test
    fun `CLASS round-trip preserves CONFIDENTIAL`() {
        val event = createEvent(classification = "CONFIDENTIAL")
        val ics = IcsPatcher.generateFresh(event)
        val reparsed = parseToEntity(ics)

        assertEquals("CLASS should survive round-trip", "CONFIDENTIAL", reparsed.classification)
    }

    // ========== TRANSP ==========

    @Test
    fun `TRANSP round-trip preserves TRANSPARENT`() {
        val event = createEvent(transp = "TRANSPARENT")
        val ics = IcsPatcher.generateFresh(event)
        val reparsed = parseToEntity(ics)

        assertEquals("TRANSP should survive round-trip", "TRANSPARENT", reparsed.transp)
    }

    // ========== LOCATION ==========

    @Test
    fun `LOCATION round-trip preserves value`() {
        val event = createEvent(location = "123 Main Street, Springfield")
        val ics = IcsPatcher.generateFresh(event)
        val reparsed = parseToEntity(ics)

        assertEquals("LOCATION should survive round-trip", "123 Main Street, Springfield", reparsed.location)
    }

    // ========== DESCRIPTION ==========

    @Test
    fun `DESCRIPTION round-trip preserves multiline text`() {
        val desc = "Line 1\nLine 2\nLine 3 with special chars: <>&"
        val event = createEvent(description = desc)
        val ics = IcsPatcher.generateFresh(event)
        val reparsed = parseToEntity(ics)

        assertNotNull("DESCRIPTION should survive round-trip", reparsed.description)
        assertTrue("Should contain Line 1", reparsed.description!!.contains("Line 1"))
        assertTrue("Should contain Line 3", reparsed.description!!.contains("Line 3"))
    }

    // ========== Organizer ==========

    @Test
    fun `organizer round-trip preserves email and CN`() {
        val event = createEvent(
            organizerEmail = "organizer@example.com",
            organizerName = "Test Organizer"
        )
        val ics = IcsPatcher.generateFresh(event)
        val reparsed = parseToEntity(ics)

        assertEquals("Organizer email should survive", "organizer@example.com", reparsed.organizerEmail)
        assertEquals("Organizer name should survive", "Test Organizer", reparsed.organizerName)
    }

    // ========== All-Day ==========

    @Test
    fun `all-day event round-trip preserves VALUE DATE format`() {
        val startOfDay = ZonedDateTime.of(2026, 2, 15, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        // Inclusive end for all-day = 23:59:59.999 UTC
        val endOfDay = startOfDay + 86400_000L - 1
        val event = createEvent(
            startTs = startOfDay,
            endTs = endOfDay,
            isAllDay = true,
            timezone = null  // All-day events don't have timezone
        )
        val ics = IcsPatcher.generateFresh(event)
        val reparsed = parseToEntity(ics)

        assertTrue("Should be all-day after round-trip", reparsed.isAllDay)
    }

    // ========== Timezone ==========

    @Test
    fun `timezone round-trip preserves TZID`() {
        val event = createEvent(timezone = "Pacific/Auckland")
        val ics = IcsPatcher.generateFresh(event)
        val reparsed = parseToEntity(ics)

        assertNotNull("Timezone should survive round-trip", reparsed.timezone)
        assertTrue(
            "Should reference Pacific/Auckland",
            reparsed.timezone!!.contains("Auckland")
        )
    }

    // ========== Null/Empty Properties ==========

    @Test
    fun `null optional properties omitted in ICS`() {
        val event = createEvent(
            description = null,
            location = null,
            url = null,
            geoLat = null,
            geoLon = null,
            organizerEmail = null
        )
        val ics = IcsPatcher.generateFresh(event)

        assertFalse("DESCRIPTION should not appear", ics.contains("DESCRIPTION:"))
        assertFalse("LOCATION should not appear", ics.contains("LOCATION:"))
        assertFalse("URL should not appear", ics.contains("URL:"))
        assertFalse("GEO should not appear", ics.contains("GEO:"))
        assertFalse("ORGANIZER should not appear", ics.contains("ORGANIZER"))
    }

    @Test
    fun `empty title becomes Untitled on re-parse`() {
        // Generate ICS with empty summary
        val event = createEvent(title = "")
        val ics = IcsPatcher.generateFresh(event)
        val reparsed = parseToEntity(ics)

        // ICalEventMapper maps null/empty summary to "Untitled"
        assertEquals("Empty title should become Untitled", "Untitled", reparsed.title)
    }

    // ========== DTSTAMP ==========

    @Test
    fun `DTSTAMP appears in generated ICS`() {
        val event = createEvent()
        val ics = IcsPatcher.generateFresh(event)

        // DTSTAMP is required by RFC 5545 Section 3.6.1
        assertTrue("DTSTAMP should appear in generated ICS", ics.contains("DTSTAMP:"))
        // Verify it's a valid UTC datetime format (YYYYMMDDTHHMMSSZ)
        val dtstampLine = ics.lines().find { it.startsWith("DTSTAMP:") }
        assertNotNull("Should have DTSTAMP line", dtstampLine)
        assertTrue(
            "DTSTAMP should end with Z (UTC)",
            dtstampLine!!.endsWith("Z")
        )
    }

    // ========== Helpers ==========

    private fun parseToEntity(ics: String): Event {
        val events = parser.parseAllEvents(ics).getOrNull()!!
        return ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)
    }

    private fun createEvent(
        title: String = "Round-Trip Test",
        description: String? = null,
        location: String? = null,
        startTs: Long = ZonedDateTime.of(2026, 1, 20, 9, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(),
        endTs: Long = ZonedDateTime.of(2026, 1, 20, 10, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(),
        isAllDay: Boolean = false,
        timezone: String? = "UTC",
        status: String = "CONFIRMED",
        transp: String = "OPAQUE",
        classification: String = "PUBLIC",
        sequence: Int = 0,
        rrule: String? = null,
        exdate: String? = null,
        rdate: String? = null,
        reminders: List<String>? = null,
        organizerEmail: String? = null,
        organizerName: String? = null,
        url: String? = null,
        geoLat: Double? = null,
        geoLon: Double? = null,
        dtstamp: Long = System.currentTimeMillis()
    ): Event {
        return Event(
            uid = "roundtrip-test@kashcal.test",
            calendarId = 1L,
            title = title,
            description = description,
            location = location,
            startTs = startTs,
            endTs = endTs,
            isAllDay = isAllDay,
            timezone = timezone,
            status = status,
            transp = transp,
            classification = classification,
            sequence = sequence,
            rrule = rrule,
            exdate = exdate,
            rdate = rdate,
            reminders = reminders,
            organizerEmail = organizerEmail,
            organizerName = organizerName,
            url = url,
            geoLat = geoLat,
            geoLon = geoLon,
            priority = 0,
            dtstamp = dtstamp,
            syncStatus = SyncStatus.SYNCED
        )
    }
}
