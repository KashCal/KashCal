package org.onekash.icaldav.recurrence

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.Frequency
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.model.Transparency
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.icaldav.parser.ICalParser
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive tests for recurring event modifications (RECURRENCE-ID).
 *
 * Based on production-tested patterns:
 *
 * KEY CONCEPTS:
 * 1. When user edits "This event only" on a recurring event:
 *    - Master event keeps its RRULE
 *    - A new VEVENT with same UID + RECURRENCE-ID is created
 *    - Modified instance has its own DTSTART/DTEND
 *    - RECURRENCE-ID = original datetime of the occurrence being modified
 *
 * 2. importId Strategy (critical for database uniqueness):
 *    - Master event: importId = "abc123" (just UID)
 *    - Modified instance: importId = "abc123:RECID:20241220T100000Z"
 *
 * 3. iCal Structure:
 *    - One .ics file can contain MULTIPLE VEVENTs (master + modified instances)
 *    - All share same UID, modified ones have RECURRENCE-ID
 *    - Modified instance should NOT have RRULE
 */
@DisplayName("Recurring Event Modification Tests")
class RecurringEventModificationTest {

    private val parser = ICalParser()
    private val generator = ICalGenerator()
    private val expander = RRuleExpander()
    private val zone = ZoneId.of("America/New_York")

    @Nested
    @DisplayName("Parsing Modified Instances")
    inner class ParsingTests {

        @Test
        fun `parses iCal with master and modified instance`() {
            // This is what iCloud sends: master + modified instance in one file
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:daily-standup-123
                DTSTART:20241201T100000Z
                DTEND:20241201T103000Z
                RRULE:FREQ=DAILY;COUNT=10
                SUMMARY:Daily Standup
                STATUS:CONFIRMED
                SEQUENCE:0
                END:VEVENT
                BEGIN:VEVENT
                UID:daily-standup-123
                RECURRENCE-ID:20241205T100000Z
                DTSTART:20241205T140000Z
                DTEND:20241205T143000Z
                SUMMARY:Daily Standup (Moved to 2pm)
                STATUS:CONFIRMED
                SEQUENCE:1
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()

            assertNotNull(events)
            assertEquals(2, events.size, "Should parse both master and modified instance")

            // Find master (no RECURRENCE-ID)
            val master = events.find { it.recurrenceId == null }
            assertNotNull(master, "Master event should have no RECURRENCE-ID")
            assertEquals("daily-standup-123", master.uid)
            assertNotNull(master.rrule, "Master should have RRULE")
            assertEquals("Daily Standup", master.summary)

            // Find modified instance (has RECURRENCE-ID)
            val modified = events.find { it.recurrenceId != null }
            assertNotNull(modified, "Modified instance should have RECURRENCE-ID")
            assertEquals("daily-standup-123", modified.uid, "Same UID as master")
            assertNull(modified.rrule, "Modified instance should NOT have RRULE")
            assertEquals("Daily Standup (Moved to 2pm)", modified.summary)
        }

        @Test
        fun `parses RECURRENCE-ID with timezone`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-event
                RECURRENCE-ID;TZID=America/New_York:20241215T100000
                DTSTART;TZID=America/New_York:20241215T140000
                DTEND;TZID=America/New_York:20241215T150000
                SUMMARY:Modified Instance with TZ
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()

            assertNotNull(events)
            assertEquals(1, events.size)

            val event = events[0]
            assertNotNull(event.recurrenceId, "Should parse RECURRENCE-ID with TZID")
        }

        @Test
        fun `parses RECURRENCE-ID as DATE for all-day events`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:vacation
                RECURRENCE-ID;VALUE=DATE:20241225
                DTSTART;VALUE=DATE:20241226
                DTEND;VALUE=DATE:20241227
                SUMMARY:Christmas (Moved to Dec 26)
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()

            assertNotNull(events)
            val event = events[0]
            assertNotNull(event.recurrenceId)
            assertTrue(event.isAllDay, "Should be all-day event")
        }
    }

    @Nested
    @DisplayName("ImportId Strategy")
    inner class ImportIdTests {

        @Test
        fun `master event importId is just UID`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:event-abc123
                DTSTART:20241201T100000Z
                RRULE:FREQ=WEEKLY;COUNT=5
                SUMMARY:Weekly Meeting
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()

            assertNotNull(events)
            assertEquals("event-abc123", events[0].importId)
        }

        @Test
        fun `modified instance importId includes RECURRENCE-ID`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:event-abc123
                RECURRENCE-ID:20241208T100000Z
                DTSTART:20241208T140000Z
                SUMMARY:Weekly Meeting (Rescheduled)
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()

            assertNotNull(events)
            val event = events[0]

            // importId format: "uid:RECID:recurrence-id-value"
            assertTrue(
                event.importId.contains(event.uid) && event.importId.contains("RECID"),
                "ImportId should contain UID and RECID marker: ${event.importId}"
            )
        }

        @Test
        fun `multiple modified instances have unique importIds`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:daily-event
                DTSTART:20241201T100000Z
                RRULE:FREQ=DAILY;COUNT=10
                SUMMARY:Daily Event
                END:VEVENT
                BEGIN:VEVENT
                UID:daily-event
                RECURRENCE-ID:20241203T100000Z
                DTSTART:20241203T140000Z
                SUMMARY:Daily Event (Dec 3 moved)
                END:VEVENT
                BEGIN:VEVENT
                UID:daily-event
                RECURRENCE-ID:20241205T100000Z
                DTSTART:20241205T160000Z
                SUMMARY:Daily Event (Dec 5 moved)
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()

            assertNotNull(events)
            assertEquals(3, events.size)

            // All importIds should be unique
            val importIds = events.map { it.importId }.toSet()
            assertEquals(3, importIds.size, "All importIds must be unique")

            // All UIDs are the same
            val uids = events.map { it.uid }.toSet()
            assertEquals(1, uids.size, "All UIDs should be the same")
        }
    }

    @Nested
    @DisplayName("RRule Expansion with Overrides")
    inner class ExpansionTests {

        @Test
        fun `expansion replaces original occurrence with modified instance`() {
            val masterStart = ZonedDateTime.of(2024, 12, 1, 10, 0, 0, 0, zone)

            val masterEvent = createEvent(
                uid = "daily-meeting",
                summary = "Daily Meeting",
                dtStart = masterStart,
                rrule = RRule(freq = Frequency.DAILY, count = 5)
            )

            // Dec 3 moved from 10am to 2pm
            val dec3Original = ZonedDateTime.of(2024, 12, 3, 10, 0, 0, 0, zone)
            val dec3Modified = ZonedDateTime.of(2024, 12, 3, 14, 0, 0, 0, zone)

            val modifiedInstance = createEvent(
                uid = "daily-meeting",
                summary = "Daily Meeting (Moved to 2pm)",
                dtStart = dec3Modified,
                recurrenceId = ICalDateTime.fromZonedDateTime(dec3Original, false)
            )

            val overrides = RRuleExpander.buildOverrideMap(listOf(modifiedInstance))

            val range = TimeRange(
                ZonedDateTime.of(2024, 12, 1, 0, 0, 0, 0, zone).toInstant(),
                ZonedDateTime.of(2024, 12, 10, 0, 0, 0, 0, zone).toInstant()
            )

            val occurrences = expander.expand(masterEvent, range, overrides)

            assertEquals(5, occurrences.size, "Should still have 5 occurrences")

            // Find Dec 3 occurrence
            val dec3Occurrence = occurrences.find {
                it.dtStart.toLocalDate().dayOfMonth == 3
            }
            assertNotNull(dec3Occurrence)
            assertEquals("Daily Meeting (Moved to 2pm)", dec3Occurrence.summary)

            // Verify time changed to 2pm
            val hour = dec3Occurrence.dtStart.toZonedDateTime().hour
            assertEquals(14, hour, "Dec 3 should be at 2pm (14:00)")
        }

        @Test
        fun `cancelled occurrence excluded from expansion`() {
            val masterStart = ZonedDateTime.of(2024, 12, 1, 10, 0, 0, 0, zone)

            val masterEvent = createEvent(
                uid = "daily-meeting",
                summary = "Daily Meeting",
                dtStart = masterStart,
                rrule = RRule(freq = Frequency.DAILY, count = 5),
                exdates = listOf(
                    // Dec 3 cancelled (EXDATE)
                    ICalDateTime.fromZonedDateTime(
                        ZonedDateTime.of(2024, 12, 3, 10, 0, 0, 0, zone),
                        false
                    )
                )
            )

            val range = TimeRange(
                ZonedDateTime.of(2024, 12, 1, 0, 0, 0, 0, zone).toInstant(),
                ZonedDateTime.of(2024, 12, 10, 0, 0, 0, 0, zone).toInstant()
            )

            val occurrences = expander.expand(masterEvent, range)

            assertEquals(4, occurrences.size, "Should have 4 occurrences (1 cancelled)")

            // Dec 3 should not be in the list
            val dec3 = occurrences.find {
                it.dtStart.toLocalDate().dayOfMonth == 3
            }
            assertNull(dec3, "Dec 3 should be excluded")
        }
    }

    @Nested
    @DisplayName("iCal Generation")
    inner class GenerationTests {

        @Test
        fun `master event generates with RRULE`() {
            val event = createEvent(
                uid = "weekly-meeting",
                summary = "Weekly Team Sync",
                dtStart = ZonedDateTime.of(2024, 12, 2, 10, 0, 0, 0, zone),
                rrule = RRule(freq = Frequency.WEEKLY, count = 8)
            )

            val ical = generator.generate(event, method = null)

            assertTrue(ical.contains("RRULE:FREQ=WEEKLY"), "Master should have RRULE")
            assertFalse(ical.contains("RECURRENCE-ID"), "Master should NOT have RECURRENCE-ID")
        }

        @Test
        fun `modified instance generates with RECURRENCE-ID, no RRULE`() {
            val originalDt = ZonedDateTime.of(2024, 12, 9, 10, 0, 0, 0, zone)
            val newDt = ZonedDateTime.of(2024, 12, 9, 14, 0, 0, 0, zone)

            val event = createEvent(
                uid = "weekly-meeting",
                summary = "Weekly Team Sync (Moved)",
                dtStart = newDt,
                recurrenceId = ICalDateTime.fromZonedDateTime(originalDt, false)
            )

            val ical = generator.generate(event, method = null)

            assertTrue(ical.contains("RECURRENCE-ID"), "Modified instance should have RECURRENCE-ID")
            // Check that RRULE doesn't appear in VEVENT section (VTIMEZONE may have RRULE for DST)
            val veventSection = ical.substringAfter("BEGIN:VEVENT").substringBefore("END:VEVENT")
            assertFalse(veventSection.contains("RRULE"), "Modified instance VEVENT should NOT have RRULE")
        }

        @Test
        fun `generates correct RECURRENCE-ID format for UTC`() {
            // Use ZoneOffset.UTC (not ZoneId.of("UTC")) for proper UTC detection
            val originalDt = ZonedDateTime.of(2025, 12, 15, 10, 0, 0, 0, ZoneOffset.UTC)

            val event = createEvent(
                uid = "test-event",
                summary = "Test",
                dtStart = ZonedDateTime.now(zone),
                recurrenceId = ICalDateTime.fromZonedDateTime(originalDt, false) // isDate=false
            )

            val ical = generator.generate(event, method = null)

            // Should be formatted as UTC (ends with Z, pattern: YYYYMMDDTHHmmssZ)
            val utcRecIdPattern = Regex("""RECURRENCE-ID:\d{8}T\d{6}Z""")
            assertTrue(
                utcRecIdPattern.containsMatchIn(ical),
                "RECURRENCE-ID should be in UTC format (YYYYMMDDTHHmmssZ). Got: $ical"
            )
            // Should NOT have TZID parameter for UTC
            assertFalse(
                ical.contains("RECURRENCE-ID;TZID="),
                "UTC RECURRENCE-ID should not have TZID parameter"
            )
        }
    }

    @Nested
    @DisplayName("Round-Trip Tests")
    inner class RoundTripTests {

        @Test
        fun `master and modified instance survive round-trip`() {
            val originalIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                CALSCALE:GREGORIAN
                METHOD:PUBLISH
                BEGIN:VEVENT
                UID:roundtrip-test
                DTSTAMP:20241215T100000Z
                DTSTART:20241201T100000Z
                DTEND:20241201T110000Z
                RRULE:FREQ=DAILY;COUNT=5
                SUMMARY:Daily Event
                STATUS:CONFIRMED
                SEQUENCE:0
                END:VEVENT
                BEGIN:VEVENT
                UID:roundtrip-test
                DTSTAMP:20241215T100000Z
                RECURRENCE-ID:20241203T100000Z
                DTSTART:20241203T150000Z
                DTEND:20241203T160000Z
                SUMMARY:Daily Event (Dec 3 Changed)
                STATUS:CONFIRMED
                SEQUENCE:1
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            // Parse
            val parseResult = parser.parseAllEvents(originalIcal)
            val events = parseResult.getOrNull()
            assertNotNull(events)
            assertEquals(2, events.size)

            // Regenerate each event
            val master = events.find { it.recurrenceId == null }!!
            val modified = events.find { it.recurrenceId != null }!!

            val masterIcal = generator.generate(master, method = null)
            val modifiedIcal = generator.generate(modified, method = null)

            // Re-parse
            val reparsedMaster = parser.parseAllEvents(masterIcal).getOrNull()?.firstOrNull()
            val reparsedModified = parser.parseAllEvents(modifiedIcal).getOrNull()?.firstOrNull()

            assertNotNull(reparsedMaster)
            assertNotNull(reparsedModified)

            // Verify properties preserved
            assertEquals(master.uid, reparsedMaster.uid)
            assertNotNull(reparsedMaster.rrule)
            assertNull(reparsedMaster.recurrenceId)

            assertEquals(modified.uid, reparsedModified.uid)
            assertNull(reparsedModified.rrule)
            assertNotNull(reparsedModified.recurrenceId)
        }
    }

    @Nested
    @DisplayName("Production Edge Cases")
    inner class ProductionEdgeCases {

        @Test
        fun `handles VTIMEZONE followed by multiple VEVENTs`() {
            // Real iCloud format: VTIMEZONE, then master VEVENT, then modified VEVENT
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Apple Inc.//iCloud//EN
                BEGIN:VTIMEZONE
                TZID:America/New_York
                BEGIN:STANDARD
                DTSTART:20071104T020000
                TZOFFSETFROM:-0400
                TZOFFSETTO:-0500
                END:STANDARD
                BEGIN:DAYLIGHT
                DTSTART:20070311T020000
                TZOFFSETFROM:-0500
                TZOFFSETTO:-0400
                END:DAYLIGHT
                END:VTIMEZONE
                BEGIN:VEVENT
                UID:icloud-event-123
                DTSTART;TZID=America/New_York:20241201T100000
                DTEND;TZID=America/New_York:20241201T110000
                RRULE:FREQ=DAILY;COUNT=5
                SUMMARY:Team Standup
                END:VEVENT
                BEGIN:VEVENT
                UID:icloud-event-123
                RECURRENCE-ID;TZID=America/New_York:20241203T100000
                DTSTART;TZID=America/New_York:20241203T140000
                DTEND;TZID=America/New_York:20241203T150000
                SUMMARY:Team Standup (Moved)
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()

            assertNotNull(events, "Should parse successfully")
            assertEquals(2, events.size, "Should find master + modified (not VTIMEZONE)")

            // Ensure VTIMEZONE RRULE wasn't picked up
            val master = events.find { it.recurrenceId == null }
            assertNotNull(master)
            assertEquals(Frequency.DAILY, master.rrule?.freq)
        }

        @Test
        fun `modified instance for all-day event`() {
            // All-day events use VALUE=DATE format
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:vacation-2024
                DTSTART;VALUE=DATE:20241223
                DTEND;VALUE=DATE:20241227
                RRULE:FREQ=YEARLY
                SUMMARY:Christmas Vacation
                END:VEVENT
                BEGIN:VEVENT
                UID:vacation-2024
                RECURRENCE-ID;VALUE=DATE:20251223
                DTSTART;VALUE=DATE:20251220
                DTEND;VALUE=DATE:20251228
                SUMMARY:Christmas Vacation (Extended in 2025)
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()

            assertNotNull(events)
            assertEquals(2, events.size)

            val modified = events.find { it.recurrenceId != null }
            assertNotNull(modified)
            assertTrue(modified.isAllDay, "Modified all-day event should stay all-day")
        }

        @Test
        fun `extracting base UID from importId with RECID`() {
            // importId format is "uid:RECID:datetime"
            // Need to extract base UID for server comparison

            val importIdMaster = "abc123-xyz"
            val importIdModified = "abc123-xyz:RECID:20241215T100000Z"

            // Extraction logic for base UID
            fun extractBaseUid(importId: String): String {
                return if (importId.contains(":RECID:")) {
                    importId.substringBefore(":RECID:")
                } else {
                    importId
                }
            }

            assertEquals("abc123-xyz", extractBaseUid(importIdMaster))
            assertEquals("abc123-xyz", extractBaseUid(importIdModified))
        }
    }

    // Helper functions
    private fun createEvent(
        uid: String,
        summary: String,
        dtStart: ZonedDateTime,
        rrule: RRule? = null,
        exdates: List<ICalDateTime> = emptyList(),
        recurrenceId: ICalDateTime? = null
    ): ICalEvent {
        return ICalEvent(
            uid = uid,
            importId = if (recurrenceId != null) {
                "$uid:RECID:${recurrenceId.toICalString()}"
            } else {
                uid
            },
            summary = summary,
            description = null,
            location = null,
            dtStart = ICalDateTime.fromZonedDateTime(dtStart, false),
            dtEnd = ICalDateTime.fromZonedDateTime(dtStart.plusHours(1), false),
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = if (recurrenceId != null) 1 else 0,
            rrule = rrule,
            exdates = exdates,
            recurrenceId = recurrenceId,
            alarms = emptyList(),
            categories = emptyList(),
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = ICalDateTime(System.currentTimeMillis(), null, true, false),
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )
    }
}

// Extension functions for test convenience (using unique names to avoid shadowing)
private fun ICalDateTime.toLocalDateInZone(zone: ZoneId): java.time.LocalDate {
    return java.time.Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
}

private fun ICalDateTime.toZonedDateTimeInZone(zone: ZoneId): ZonedDateTime {
    return java.time.Instant.ofEpochMilli(timestamp).atZone(zone)
}

private fun createICalDateTimeNow(): ICalDateTime {
    return ICalDateTime(System.currentTimeMillis(), null, true, false)
}

private fun formatICalString(dt: ICalDateTime): String {
    val instant = java.time.Instant.ofEpochMilli(dt.timestamp)
    val zdt = instant.atZone(ZoneId.of("UTC"))
    return java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(zdt)
}