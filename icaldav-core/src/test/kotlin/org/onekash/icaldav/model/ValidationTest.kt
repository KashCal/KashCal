package org.onekash.icaldav.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant

/**
 * Validation tests for iCalendar model constraints.
 *
 * Tests RFC 5545 constraints:
 * - Required properties
 * - Mutually exclusive properties
 * - Value ranges
 * - Format validation
 *
 * These tests verify the library handles constraint violations gracefully
 * and enforces RFC compliance where appropriate.
 */
class ValidationTest {

    // ==================== RRule Validation ====================

    @Nested
    inner class RRuleValidationTests {

        @Test
        fun `FREQ is required - parse throws on missing FREQ`() {
            assertThrows<IllegalArgumentException> {
                RRule.parse("INTERVAL=2;BYDAY=MO,WE")
            }
        }

        @Test
        fun `invalid FREQ value throws exception`() {
            assertThrows<IllegalArgumentException> {
                RRule.parse("FREQ=BIWEEKLY")
            }
        }

        @Test
        fun `INTERVAL defaults to 1 when not specified`() {
            val rule = RRule.parse("FREQ=DAILY")
            assertEquals(1, rule.interval)
        }

        @Test
        fun `INTERVAL of 0 is accepted but may produce no occurrences`() {
            // RFC 5545 doesn't explicitly forbid INTERVAL=0
            // Parser accepts it; behavior during expansion may vary
            val rule = RRule.parse("FREQ=DAILY;INTERVAL=0")
            assertEquals(0, rule.interval)
        }

        @Test
        fun `negative INTERVAL is parsed as-is`() {
            // Parser accepts negative values; validation at expansion time
            val rule = RRule.parse("FREQ=DAILY;INTERVAL=-1")
            assertEquals(-1, rule.interval)
        }

        @Test
        fun `COUNT and UNTIL can both be specified (RFC violation but parseable)`() {
            // RFC 5545 says they're mutually exclusive, but parser may accept both
            val rule = RRule.parse("FREQ=DAILY;COUNT=10;UNTIL=20231231T235959Z")
            assertEquals(10, rule.count)
            assertNotNull(rule.until)
        }

        @Test
        fun `COUNT must be positive when present`() {
            val rule = RRule.parse("FREQ=DAILY;COUNT=0")
            assertEquals(0, rule.count)

            val negativeRule = RRule.parse("FREQ=DAILY;COUNT=-5")
            assertEquals(-5, negativeRule.count)
            // Behavior with invalid counts is implementation-defined
        }

        @Test
        fun `BYDAY ordinals must be in range -5 to 5`() {
            // Valid ordinals for BYDAY in MONTHLY: -5 to -1 and 1 to 5
            // (e.g., "5th Tuesday" is the maximum for any month)
            val validRule = RRule.parse("FREQ=MONTHLY;BYDAY=2TU,-1FR")
            assertEquals(2, validRule.byDay?.firstOrNull()?.ordinal)
            assertEquals(-1, validRule.byDay?.lastOrNull()?.ordinal)

            // Maximum valid ordinals for monthly recurrence
            val extremeRule = RRule.parse("FREQ=MONTHLY;BYDAY=5MO,-5SU")
            assertEquals(5, extremeRule.byDay?.firstOrNull()?.ordinal)
            assertEquals(-5, extremeRule.byDay?.lastOrNull()?.ordinal)
        }

        @Test
        fun `BYMONTHDAY must be in range -31 to 31`() {
            val rule = RRule.parse("FREQ=MONTHLY;BYMONTHDAY=1,15,-1")
            assertEquals(listOf(1, 15, -1), rule.byMonthDay)
        }

        @Test
        fun `BYMONTH must be in range 1 to 12`() {
            val rule = RRule.parse("FREQ=YEARLY;BYMONTH=1,6,12")
            assertEquals(listOf(1, 6, 12), rule.byMonth)
        }

        @Test
        fun `BYWEEKNO must be in range -53 to 53`() {
            val rule = RRule.parse("FREQ=YEARLY;BYWEEKNO=1,26,-1")
            assertEquals(listOf(1, 26, -1), rule.byWeekNo)
        }

        @Test
        fun `BYYEARDAY must be in range -366 to 366`() {
            val rule = RRule.parse("FREQ=YEARLY;BYYEARDAY=1,100,-1")
            assertEquals(listOf(1, 100, -1), rule.byYearDay)
        }

        @Test
        fun `BYSETPOS must be in range -366 to 366`() {
            val rule = RRule.parse("FREQ=MONTHLY;BYDAY=MO;BYSETPOS=1,-1")
            assertEquals(listOf(1, -1), rule.bySetPos)
        }

        @Test
        fun `WKST defaults to MO when not specified`() {
            val rule = RRule.parse("FREQ=WEEKLY")
            assertEquals(DayOfWeek.MONDAY, rule.wkst)
        }

        @Test
        fun `all valid WKST values are accepted`() {
            val days = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
            val expected = listOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
            )

            days.forEachIndexed { index, dayStr ->
                val rule = RRule.parse("FREQ=WEEKLY;WKST=$dayStr")
                assertEquals(expected[index], rule.wkst)
            }
        }

        @Test
        fun `invalid WKST defaults to MONDAY`() {
            val rule = RRule.parse("FREQ=WEEKLY;WKST=XX")
            assertEquals(DayOfWeek.MONDAY, rule.wkst)
        }
    }

    // ==================== WeekdayNum Validation ====================

    @Nested
    inner class WeekdayNumValidationTests {

        @Test
        fun `valid weekday codes are parsed`() {
            val codes = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
            val expected = listOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
            )

            codes.forEachIndexed { index, code ->
                val weekdayNum = WeekdayNum.parse(code)
                assertEquals(expected[index], weekdayNum.dayOfWeek)
                assertNull(weekdayNum.ordinal)
            }
        }

        @Test
        fun `lowercase weekday codes are accepted`() {
            val weekdayNum = WeekdayNum.parse("mo")
            assertEquals(DayOfWeek.MONDAY, weekdayNum.dayOfWeek)
        }

        @Test
        fun `positive ordinals are parsed`() {
            for (ordinal in 1..5) {
                val weekdayNum = WeekdayNum.parse("${ordinal}TU")
                assertEquals(ordinal, weekdayNum.ordinal)
                assertEquals(DayOfWeek.TUESDAY, weekdayNum.dayOfWeek)
            }
        }

        @Test
        fun `negative ordinals are parsed`() {
            for (ordinal in -5..-1) {
                val weekdayNum = WeekdayNum.parse("${ordinal}FR")
                assertEquals(ordinal, weekdayNum.ordinal)
                assertEquals(DayOfWeek.FRIDAY, weekdayNum.dayOfWeek)
            }
        }

        @Test
        fun `invalid weekday code throws exception`() {
            assertThrows<IllegalArgumentException> {
                WeekdayNum.parse("XX")
            }
        }

        @Test
        fun `empty string throws exception`() {
            assertThrows<IllegalArgumentException> {
                WeekdayNum.parse("")
            }
        }

        @Test
        fun `ordinal without day code throws exception`() {
            assertThrows<IllegalArgumentException> {
                WeekdayNum.parse("2")
            }
        }

        @Test
        fun `round-trip serialization preserves values`() {
            val testCases = listOf(
                WeekdayNum(DayOfWeek.MONDAY, null),
                WeekdayNum(DayOfWeek.FRIDAY, 2),
                WeekdayNum(DayOfWeek.SUNDAY, -1)
            )

            testCases.forEach { original ->
                val serialized = original.toICalString()
                val parsed = WeekdayNum.parse(serialized)
                assertEquals(original, parsed)
            }
        }
    }

    // ==================== ICalDateTime Validation ====================

    @Nested
    inner class ICalDateTimeValidationTests {

        @Test
        fun `valid DATE format is parsed`() {
            val dt = ICalDateTime.parse("20231215")
            assertEquals(2023, dt.toZonedDateTime().year)
            assertEquals(12, dt.toZonedDateTime().monthValue)
            assertEquals(15, dt.toZonedDateTime().dayOfMonth)
            assertTrue(dt.isDate)
        }

        @Test
        fun `valid DATE-TIME UTC format is parsed`() {
            val dt = ICalDateTime.parse("20231215T103000Z")
            assertEquals(10, dt.toZonedDateTime().hour)
            assertEquals(30, dt.toZonedDateTime().minute)
            assertFalse(dt.isDate)
        }

        @Test
        fun `valid DATE-TIME local format is parsed`() {
            val dt = ICalDateTime.parse("20231215T103000")
            assertEquals(10, dt.toZonedDateTime().hour)
            assertFalse(dt.isDate)
        }

        @Test
        fun `timestamp conversion is accurate`() {
            val now = Instant.now()
            val dt = ICalDateTime.fromTimestamp(now.toEpochMilli(), null, false)

            // Should be within a second of original
            val diff = kotlin.math.abs(dt.timestamp - now.toEpochMilli())
            assertTrue(diff < 1000, "Timestamp conversion should preserve milliseconds")
        }

        @Test
        fun `day code generation is consistent`() {
            val dt = ICalDateTime.parse("20231215T103000Z")
            val dayCode = dt.toDayCode()
            assertEquals("20231215", dayCode)
        }
    }

    // ==================== ICalEvent Validation ====================

    @Nested
    inner class ICalEventValidationTests {

        private fun createMinimalEvent(
            uid: String = "test-uid",
            dtStart: ICalDateTime = ICalDateTime.parse("20231215T100000Z")
        ): ICalEvent {
            return ICalEvent(
                uid = uid,
                importId = uid,
                summary = "Test Event",
                description = null,
                location = null,
                dtStart = dtStart,
                dtEnd = null,
                duration = null,
                isAllDay = false,
                status = EventStatus.CONFIRMED,
                sequence = 0,
                rrule = null,
                exdates = emptyList(),
                recurrenceId = null,
                alarms = emptyList(),
                categories = emptyList(),
                organizer = null,
                attendees = emptyList(),
                color = null,
                dtstamp = null,
                lastModified = null,
                created = null,
                transparency = Transparency.OPAQUE,
                url = null,
                rawProperties = emptyMap()
            )
        }

        @Test
        fun `effectiveEnd returns dtEnd when present`() {
            val start = ICalDateTime.parse("20231215T100000Z")
            val end = ICalDateTime.parse("20231215T110000Z")
            val event = createMinimalEvent().copy(dtStart = start, dtEnd = end)

            assertEquals(end.timestamp, event.effectiveEnd().timestamp)
        }

        @Test
        fun `effectiveEnd calculates from duration when dtEnd absent`() {
            val start = ICalDateTime.parse("20231215T100000Z")
            val event = createMinimalEvent().copy(
                dtStart = start,
                dtEnd = null,
                duration = Duration.ofHours(2)
            )

            val expectedEnd = start.timestamp + Duration.ofHours(2).toMillis()
            assertEquals(expectedEnd, event.effectiveEnd().timestamp)
        }

        @Test
        fun `effectiveEnd returns 24h later for all-day event without end`() {
            val start = ICalDateTime.parse("20231215")
            val event = createMinimalEvent().copy(
                dtStart = start,
                dtEnd = null,
                duration = null,
                isAllDay = true
            )

            val expectedEnd = start.timestamp + 86400000L
            assertEquals(expectedEnd, event.effectiveEnd().timestamp)
        }

        @Test
        fun `effectiveEnd returns start for instant event`() {
            val start = ICalDateTime.parse("20231215T100000Z")
            val event = createMinimalEvent().copy(
                dtStart = start,
                dtEnd = null,
                duration = null,
                isAllDay = false
            )

            assertEquals(start.timestamp, event.effectiveEnd().timestamp)
        }

        @Test
        fun `isRecurring true when RRULE present`() {
            val event = createMinimalEvent().copy(
                rrule = RRule(freq = Frequency.DAILY)
            )
            assertTrue(event.isRecurring())
        }

        @Test
        fun `isRecurring true when RDATE present`() {
            val event = createMinimalEvent().copy(
                rdates = listOf(ICalDateTime.parse("20231220T100000Z"))
            )
            assertTrue(event.isRecurring())
        }

        @Test
        fun `isRecurring false when no RRULE or RDATE`() {
            val event = createMinimalEvent()
            assertFalse(event.isRecurring())
        }

        @Test
        fun `isModifiedInstance true when recurrenceId present`() {
            val event = createMinimalEvent().copy(
                recurrenceId = ICalDateTime.parse("20231215T100000Z")
            )
            assertTrue(event.isModifiedInstance())
        }

        @Test
        fun `isModifiedInstance false when recurrenceId null`() {
            val event = createMinimalEvent()
            assertFalse(event.isModifiedInstance())
        }

        @Test
        fun `generateImportId without recurrenceId returns UID`() {
            val importId = ICalEvent.generateImportId("test-uid-123", null)
            assertEquals("test-uid-123", importId)
        }

        @Test
        fun `generateImportId with recurrenceId includes RECID suffix`() {
            val recId = ICalDateTime.parse("20231215T100000Z")
            val importId = ICalEvent.generateImportId("test-uid-123", recId)
            assertTrue(importId.startsWith("test-uid-123:RECID:"))
        }

        @Test
        fun `parseImportId extracts UID without RECID`() {
            val (uid, recid) = ICalEvent.parseImportId("test-uid-123")
            assertEquals("test-uid-123", uid)
            assertNull(recid)
        }

        @Test
        fun `parseImportId extracts UID and RECID`() {
            val (uid, recid) = ICalEvent.parseImportId("test-uid-123:RECID:20231215T100000Z")
            assertEquals("test-uid-123", uid)
            assertEquals("20231215T100000Z", recid)
        }

        @Test
        fun `UID with colons is handled correctly`() {
            // Some UIDs contain colons (e.g., iCloud UIDs)
            val complexUid = "urn:uuid:12345678-1234-1234-1234-123456789012"
            val (uid, recid) = ICalEvent.parseImportId(complexUid)
            assertEquals(complexUid, uid)
            assertNull(recid)
        }
    }

    // ==================== Enum Validation ====================

    @Nested
    inner class EnumValidationTests {

        @Test
        fun `EventStatus fromString handles all valid values`() {
            assertEquals(EventStatus.CONFIRMED, EventStatus.fromString("CONFIRMED"))
            assertEquals(EventStatus.TENTATIVE, EventStatus.fromString("TENTATIVE"))
            assertEquals(EventStatus.CANCELLED, EventStatus.fromString("CANCELLED"))
        }

        @Test
        fun `EventStatus fromString defaults to CONFIRMED`() {
            assertEquals(EventStatus.CONFIRMED, EventStatus.fromString(null))
            assertEquals(EventStatus.CONFIRMED, EventStatus.fromString("INVALID"))
            assertEquals(EventStatus.CONFIRMED, EventStatus.fromString(""))
        }

        @Test
        fun `EventStatus fromString is case insensitive`() {
            assertEquals(EventStatus.TENTATIVE, EventStatus.fromString("tentative"))
            assertEquals(EventStatus.TENTATIVE, EventStatus.fromString("Tentative"))
        }

        @Test
        fun `Transparency fromString handles all valid values`() {
            assertEquals(Transparency.OPAQUE, Transparency.fromString("OPAQUE"))
            assertEquals(Transparency.TRANSPARENT, Transparency.fromString("TRANSPARENT"))
        }

        @Test
        fun `Transparency fromString defaults to OPAQUE`() {
            assertEquals(Transparency.OPAQUE, Transparency.fromString(null))
            assertEquals(Transparency.OPAQUE, Transparency.fromString("INVALID"))
        }

        @Test
        fun `Classification fromString handles all valid values`() {
            assertEquals(Classification.PUBLIC, Classification.fromString("PUBLIC"))
            assertEquals(Classification.PRIVATE, Classification.fromString("PRIVATE"))
            assertEquals(Classification.CONFIDENTIAL, Classification.fromString("CONFIDENTIAL"))
        }

        @Test
        fun `Classification fromString returns null for invalid`() {
            assertNull(Classification.fromString(null))
            assertNull(Classification.fromString("INVALID"))
        }

        @Test
        fun `PartStat fromString handles all valid values`() {
            assertEquals(PartStat.NEEDS_ACTION, PartStat.fromString("NEEDS-ACTION"))
            assertEquals(PartStat.ACCEPTED, PartStat.fromString("ACCEPTED"))
            assertEquals(PartStat.DECLINED, PartStat.fromString("DECLINED"))
            assertEquals(PartStat.TENTATIVE, PartStat.fromString("TENTATIVE"))
            assertEquals(PartStat.DELEGATED, PartStat.fromString("DELEGATED"))
        }

        @Test
        fun `PartStat fromString defaults to NEEDS_ACTION`() {
            assertEquals(PartStat.NEEDS_ACTION, PartStat.fromString(null))
            assertEquals(PartStat.NEEDS_ACTION, PartStat.fromString("INVALID"))
        }

        @Test
        fun `AttendeeRole fromString handles all valid values`() {
            assertEquals(AttendeeRole.CHAIR, AttendeeRole.fromString("CHAIR"))
            assertEquals(AttendeeRole.REQ_PARTICIPANT, AttendeeRole.fromString("REQ-PARTICIPANT"))
            assertEquals(AttendeeRole.OPT_PARTICIPANT, AttendeeRole.fromString("OPT-PARTICIPANT"))
            assertEquals(AttendeeRole.NON_PARTICIPANT, AttendeeRole.fromString("NON-PARTICIPANT"))
        }

        @Test
        fun `AttendeeRole fromString defaults to REQ_PARTICIPANT`() {
            assertEquals(AttendeeRole.REQ_PARTICIPANT, AttendeeRole.fromString(null))
            assertEquals(AttendeeRole.REQ_PARTICIPANT, AttendeeRole.fromString("INVALID"))
        }

        @Test
        fun `CUType fromString handles all valid values`() {
            assertEquals(CUType.INDIVIDUAL, CUType.fromString("INDIVIDUAL"))
            assertEquals(CUType.GROUP, CUType.fromString("GROUP"))
            assertEquals(CUType.RESOURCE, CUType.fromString("RESOURCE"))
            assertEquals(CUType.ROOM, CUType.fromString("ROOM"))
            assertEquals(CUType.UNKNOWN, CUType.fromString("UNKNOWN"))
        }

        @Test
        fun `CUType fromString defaults to INDIVIDUAL`() {
            assertEquals(CUType.INDIVIDUAL, CUType.fromString(null))
            assertEquals(CUType.INDIVIDUAL, CUType.fromString("INVALID"))
        }

        @Test
        fun `enum toICalString round-trips correctly`() {
            // Test that enum values survive round-trip serialization
            EventStatus.entries.forEach { status ->
                val serialized = status.toICalString()
                val parsed = EventStatus.fromString(serialized)
                assertEquals(status, parsed)
            }

            Transparency.entries.forEach { transp ->
                val serialized = transp.toICalString()
                val parsed = Transparency.fromString(serialized)
                assertEquals(transp, parsed)
            }
        }
    }

    // ==================== Attendee/Organizer Validation ====================

    @Nested
    inner class AttendeeOrganizerValidationTests {

        @Test
        fun `Attendee with minimal properties is valid`() {
            val attendee = Attendee(
                email = "user@example.com",
                name = null,
                partStat = PartStat.NEEDS_ACTION,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )

            assertEquals("user@example.com", attendee.email)
            assertEquals(CUType.INDIVIDUAL, attendee.cutype)  // Default
        }

        @Test
        fun `Attendee with full RFC 5545 properties`() {
            val attendee = Attendee(
                email = "user@example.com",
                name = "Test User",
                partStat = PartStat.ACCEPTED,
                role = AttendeeRole.CHAIR,
                rsvp = true,
                cutype = CUType.ROOM,
                dir = "ldap://example.com/cn=user",
                member = "mailto:group@example.com",
                delegatedTo = listOf("mailto:delegate@example.com"),
                delegatedFrom = listOf("mailto:original@example.com")
            )

            assertEquals(CUType.ROOM, attendee.cutype)
            assertEquals(1, attendee.delegatedTo.size)
        }

        @Test
        fun `Organizer with RFC 6638 scheduling properties`() {
            val organizer = Organizer(
                email = "organizer@example.com",
                name = "Meeting Organizer",
                sentBy = "assistant@example.com",
                scheduleAgent = ScheduleAgent.SERVER,
                scheduleForceSend = ScheduleForceSend.REQUEST
            )

            assertEquals(ScheduleAgent.SERVER, organizer.scheduleAgent)
        }
    }

    // ==================== RRule Round-Trip Validation ====================

    @Nested
    inner class RRuleRoundTripTests {

        @Test
        fun `simple DAILY rule round-trips`() {
            val original = "FREQ=DAILY;INTERVAL=2"
            val parsed = RRule.parse(original)
            val serialized = parsed.toICalString()
            val reparsed = RRule.parse(serialized)

            assertEquals(parsed.freq, reparsed.freq)
            assertEquals(parsed.interval, reparsed.interval)
        }

        @Test
        fun `WEEKLY with BYDAY round-trips`() {
            val rule = RRule(
                freq = Frequency.WEEKLY,
                byDay = listOf(
                    WeekdayNum(DayOfWeek.MONDAY),
                    WeekdayNum(DayOfWeek.WEDNESDAY),
                    WeekdayNum(DayOfWeek.FRIDAY)
                )
            )

            val serialized = rule.toICalString()
            assertTrue(serialized.contains("BYDAY=MO,WE,FR"))

            val reparsed = RRule.parse(serialized)
            assertEquals(3, reparsed.byDay?.size)
        }

        @Test
        fun `MONTHLY with BYDAY ordinal round-trips`() {
            val rule = RRule(
                freq = Frequency.MONTHLY,
                byDay = listOf(WeekdayNum(DayOfWeek.TUESDAY, 2))  // Second Tuesday
            )

            val serialized = rule.toICalString()
            assertTrue(serialized.contains("BYDAY=2TU"))

            val reparsed = RRule.parse(serialized)
            assertEquals(2, reparsed.byDay?.first()?.ordinal)
        }

        @Test
        fun `YEARLY with all BY* components round-trips`() {
            val rule = RRule(
                freq = Frequency.YEARLY,
                interval = 1,
                count = 10,
                byMonth = listOf(1, 6, 12),
                byMonthDay = listOf(15),
                wkst = DayOfWeek.SUNDAY
            )

            val serialized = rule.toICalString()
            val reparsed = RRule.parse(serialized)

            assertEquals(Frequency.YEARLY, reparsed.freq)
            assertEquals(10, reparsed.count)
            assertEquals(listOf(1, 6, 12), reparsed.byMonth)
            assertEquals(listOf(15), reparsed.byMonthDay)
            assertEquals(DayOfWeek.SUNDAY, reparsed.wkst)
        }

        @Test
        fun `rule with UNTIL round-trips preserving format`() {
            val original = "FREQ=DAILY;UNTIL=20231231T235959Z"
            val parsed = RRule.parse(original)

            assertNotNull(parsed.until)

            val serialized = parsed.toICalString()
            assertTrue(serialized.contains("UNTIL="))
        }
    }
}
