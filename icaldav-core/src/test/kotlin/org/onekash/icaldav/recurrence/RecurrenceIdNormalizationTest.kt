package org.onekash.icaldav.recurrence

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.parser.ICalParser
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RECURRENCE-ID value-type / timezone normalization.
 *
 * Per RFC 5545 §3.8.4.4 a RECURRENCE-ID identifies the *instant* of the
 * original occurrence it overrides — not a calendar day. The prior expander
 * matched overrides by a "day code" (YYYYMMDD) derived from the RECURRENCE-ID.
 * For a UTC (`Z`-form) or floating RECURRENCE-ID the day code was computed in
 * the JVM's *default* timezone (ICalDateTime.toLocalDate falls back to
 * ZoneId.systemDefault() when there is no TZID), which can disagree with the
 * calendar day the master's RRULE expansion assigns the occurrence in the
 * master's own zone. The override was then dropped or applied to the wrong day
 * depending on where the process happened to run.
 *
 * These tests pin the corrected behaviour:
 *  - matching is by normalized instant, independent of the machine timezone;
 *  - a value-type mismatch (DATE RECURRENCE-ID vs timed master, or DATE-TIME
 *    vs all-day master) is reconciled to the master's value type/zone before
 *    matching.
 */
@DisplayName("RECURRENCE-ID normalization")
class RecurrenceIdNormalizationTest {

    private val parser = ICalParser()
    private val expander = RRuleExpander()
    private val nyZone = ZoneId.of("America/New_York")

    /** Run [block] with the JVM default timezone forced to [zoneId], then restore. */
    private fun <T> withDefaultZone(zoneId: String, block: () -> T): T {
        val saved = TimeZone.getDefault()
        return try {
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
            block()
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    private fun expandParsed(ics: String, zone: ZoneId, from: ZonedDateTime, to: ZonedDateTime) =
        run {
            val events = parser.parseAllEvents(ics).getOrThrow()
            val master = events.first { it.recurrenceId == null }
            val overrides = events.filter { it.recurrenceId != null }
            expander.expand(master, TimeRange(from.toInstant(), to.toInstant()), overrides)
        }

    @Nested
    @DisplayName("normalizeToMasterValueType")
    inner class NormalizationUnit {

        @Test
        fun `promotes a DATE recurrence-id to the master time-of-day in the master zone`() {
            // Master: daily at 10:00 America/New_York.
            val masterDtStart = ICalDateTime.parse("20231201T100000", tzid = "America/New_York")
            // RECURRENCE-ID delivered as a bare DATE (VALUE=DATE:20231203).
            val recurrenceId = ICalDateTime.parse("20231203")

            val normalized = RRuleExpander.normalizeToMasterValueType(recurrenceId, masterDtStart)

            assertFalse(normalized.isDate, "promoted value must be a DATE-TIME")
            // Must land on Dec 3 10:00 in New York (== 15:00Z), the instant the
            // master's expansion produces for that calendar day.
            val expected = ZonedDateTime.of(2023, 12, 3, 10, 0, 0, 0, nyZone).toInstant().toEpochMilli()
            assertEquals(expected, normalized.timestamp)
        }

        @Test
        fun `demotes a DATE-TIME recurrence-id to a DATE for an all-day master`() {
            // Master: all-day (DATE) event.
            val masterDtStart = ICalDateTime.parse("20231203")
            // RECURRENCE-ID delivered as a UTC DATE-TIME.
            val recurrenceId = ICalDateTime.parse("20231203T120000Z")

            val normalized = RRuleExpander.normalizeToMasterValueType(recurrenceId, masterDtStart)

            assertTrue(normalized.isDate, "demoted value must be a DATE")
            // Dec 3 UTC-midnight, matching how DATE values are stored.
            val expected = ICalDateTime.parse("20231203").timestamp
            assertEquals(expected, normalized.timestamp)
        }

        @Test
        fun `returns the value unchanged when value types already match`() {
            val masterDtStart = ICalDateTime.parse("20231201T100000Z")
            val recurrenceId = ICalDateTime.parse("20231203T100000Z")

            val normalized = RRuleExpander.normalizeToMasterValueType(recurrenceId, masterDtStart)

            assertEquals(recurrenceId, normalized)
        }

        @Test
        fun `demotion is independent of the machine timezone`() {
            val masterDtStart = ICalDateTime.parse("20231203")
            val recurrenceId = ICalDateTime.parse("20231203T120000Z")

            val underKiritimati = withDefaultZone("Pacific/Kiritimati") { // UTC+14
                RRuleExpander.normalizeToMasterValueType(recurrenceId, masterDtStart).timestamp
            }
            val underHonolulu = withDefaultZone("Pacific/Honolulu") { // UTC-10
                RRuleExpander.normalizeToMasterValueType(recurrenceId, masterDtStart).timestamp
            }
            assertEquals(underKiritimati, underHonolulu)
            assertEquals(ICalDateTime.parse("20231203").timestamp, underKiritimati)
        }
    }

    @Nested
    @DisplayName("Expansion matches overrides by instant")
    inner class ExpansionMatching {

        // The canonical reproduction: a Z-form RECURRENCE-ID against a
        // TZID master. The correct occurrence must be overridden regardless of
        // the machine timezone. Under the old day-code logic the override
        // landed on the wrong day (or was dropped) when the default zone pushed
        // the UTC instant across a calendar boundary.
        private val zFormOverrideIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:standup
            DTSTART;TZID=America/New_York:20231201T100000
            DTEND;TZID=America/New_York:20231201T103000
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Standup
            END:VEVENT
            BEGIN:VEVENT
            UID:standup
            RECURRENCE-ID:20231203T150000Z
            DTSTART;TZID=America/New_York:20231203T140000
            DTEND;TZID=America/New_York:20231203T143000
            SUMMARY:Standup (moved to 2pm)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        private fun assertDec3Overridden(zoneName: String) {
            val occ = withDefaultZone(zoneName) {
                expandParsed(
                    zFormOverrideIcs,
                    nyZone,
                    ZonedDateTime.of(2023, 12, 1, 0, 0, 0, 0, nyZone),
                    ZonedDateTime.of(2023, 12, 10, 0, 0, 0, 0, nyZone)
                )
            }
            assertEquals(5, occ.size, "override must replace, not add, an occurrence [$zoneName]")
            val dec3 = occ.first { it.dtStart.toLocalDate().dayOfMonth == 3 }
            assertEquals("Standup (moved to 2pm)", dec3.summary, "Dec 3 must be the override [$zoneName]")
            assertEquals(14, dec3.dtStart.toZonedDateTime().withZoneSameInstant(nyZone).hour, "[$zoneName]")
            // No day was double-covered or shifted.
            assertEquals(
                listOf(1, 2, 3, 4, 5),
                occ.map { it.dtStart.toLocalDate().dayOfMonth }.sorted(),
                "[$zoneName]"
            )
        }

        @Test
        fun `Z-form RECURRENCE-ID overrides the correct day under a UTC+14 default zone`() {
            assertDec3Overridden("Pacific/Kiritimati")
        }

        @Test
        fun `Z-form RECURRENCE-ID overrides the correct day under a UTC-10 default zone`() {
            assertDec3Overridden("Pacific/Honolulu")
        }

        @Test
        fun `Z-form RECURRENCE-ID overrides the correct day under a UTC default zone`() {
            assertDec3Overridden("UTC")
        }

        @Test
        fun `DATE RECURRENCE-ID overrides a timed master occurrence`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:standup
                DTSTART;TZID=America/New_York:20231201T100000
                DTEND;TZID=America/New_York:20231201T103000
                RRULE:FREQ=DAILY;COUNT=5
                SUMMARY:Standup
                END:VEVENT
                BEGIN:VEVENT
                UID:standup
                RECURRENCE-ID;VALUE=DATE:20231203
                DTSTART;TZID=America/New_York:20231203T140000
                DTEND;TZID=America/New_York:20231203T143000
                SUMMARY:Standup (moved to 2pm)
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val occ = withDefaultZone("Pacific/Kiritimati") {
                expandParsed(
                    ics, nyZone,
                    ZonedDateTime.of(2023, 12, 1, 0, 0, 0, 0, nyZone),
                    ZonedDateTime.of(2023, 12, 10, 0, 0, 0, 0, nyZone)
                )
            }
            assertEquals(5, occ.size)
            val dec3 = occ.first { it.dtStart.toLocalDate().dayOfMonth == 3 }
            assertEquals("Standup (moved to 2pm)", dec3.summary)
        }

        @Test
        fun `DATE-TIME RECURRENCE-ID overrides an all-day master occurrence`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:vacation
                DTSTART;VALUE=DATE:20231201
                DTEND;VALUE=DATE:20231202
                RRULE:FREQ=DAILY;COUNT=5
                SUMMARY:Vacation
                END:VEVENT
                BEGIN:VEVENT
                UID:vacation
                RECURRENCE-ID:20231203T000000Z
                DTSTART;VALUE=DATE:20231203
                DTEND;VALUE=DATE:20231204
                SUMMARY:Vacation (special)
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val occ = withDefaultZone("Pacific/Honolulu") {
                expandParsed(
                    ics, ZoneOffset.UTC,
                    ZonedDateTime.of(2023, 12, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                    ZonedDateTime.of(2023, 12, 10, 0, 0, 0, 0, ZoneOffset.UTC)
                )
            }
            assertEquals(5, occ.size)
            val dec3 = occ.first { it.dtStart.toLocalDate().dayOfMonth == 3 }
            assertEquals("Vacation (special)", dec3.summary)
        }

        @Test
        fun `unmatched override is not injected as an extra occurrence`() {
            // RECURRENCE-ID points at a day with no generated occurrence (Dec 20,
            // outside the COUNT=5 run). It must simply not apply — no phantom event.
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:standup
                DTSTART;TZID=America/New_York:20231201T100000
                DTEND;TZID=America/New_York:20231201T103000
                RRULE:FREQ=DAILY;COUNT=5
                SUMMARY:Standup
                END:VEVENT
                BEGIN:VEVENT
                UID:standup
                RECURRENCE-ID:20231220T150000Z
                DTSTART;TZID=America/New_York:20231220T140000
                SUMMARY:Standup (orphan)
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val occ = expandParsed(
                ics, nyZone,
                ZonedDateTime.of(2023, 12, 1, 0, 0, 0, 0, nyZone),
                ZonedDateTime.of(2023, 12, 10, 0, 0, 0, 0, nyZone)
            )
            assertEquals(5, occ.size)
            assertTrue(occ.none { it.summary == "Standup (orphan)" })
        }
    }
}
