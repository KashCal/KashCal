package org.onekash.icaldav.parser

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.AlarmProximity
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for RFC 9074 VALARM extensions parsing and roundtrip.
 */
class ICalParserRfc9074Test {

    private lateinit var parser: ICalParser
    private lateinit var generator: ICalGenerator

    @BeforeEach
    fun setup() {
        parser = ICalParser()
        generator = ICalGenerator()
    }

    @Nested
    inner class AlarmProximityTests {

        @Test
        fun `fromString parses ARRIVE correctly`() {
            assertEquals(AlarmProximity.ARRIVE, AlarmProximity.fromString("ARRIVE"))
            assertEquals(AlarmProximity.ARRIVE, AlarmProximity.fromString("arrive"))
            assertEquals(AlarmProximity.ARRIVE, AlarmProximity.fromString("Arrive"))
        }

        @Test
        fun `fromString parses DEPART correctly`() {
            assertEquals(AlarmProximity.DEPART, AlarmProximity.fromString("DEPART"))
            assertEquals(AlarmProximity.DEPART, AlarmProximity.fromString("depart"))
        }

        @Test
        fun `fromString returns null for unknown values`() {
            assertNull(AlarmProximity.fromString("UNKNOWN"))
            assertNull(AlarmProximity.fromString(""))
            assertNull(AlarmProximity.fromString(null))
        }

        @Test
        fun `toICalString returns correct value`() {
            assertEquals("ARRIVE", AlarmProximity.ARRIVE.toICalString())
            assertEquals("DEPART", AlarmProximity.DEPART.toICalString())
        }
    }

    @Nested
    inner class AlarmUidParsing {

        @Test
        fun `parse alarm with UID`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:event-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with UID Alarm
                BEGIN:VALARM
                UID:alarm-uid-123
                ACTION:DISPLAY
                TRIGGER:-PT15M
                DESCRIPTION:Reminder
                END:VALARM
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val event = parser.parseAllEvents(ical).getOrNull()!![0]
            assertEquals(1, event.alarms.size)
            assertEquals("alarm-uid-123", event.alarms[0].uid)
        }
    }

    @Nested
    inner class AcknowledgedParsing {

        @Test
        fun `parse alarm with ACKNOWLEDGED timestamp`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:event-2
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Acknowledged Alarm
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER:-PT15M
                DESCRIPTION:Reminder
                ACKNOWLEDGED:20231215T135500Z
                END:VALARM
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val event = parser.parseAllEvents(ical).getOrNull()!![0]
            val alarm = event.alarms[0]

            assertNotNull(alarm.acknowledged)
            assertTrue(alarm.acknowledged!!.toICalString().contains("20231215"))
        }
    }

    @Nested
    inner class RelatedToParsing {

        @Test
        fun `parse alarm with RELATED-TO for snooze`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:event-3
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Snooze Alarm
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER:-PT10M
                DESCRIPTION:Snoozed Reminder
                RELATED-TO:original-alarm-uid
                END:VALARM
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val event = parser.parseAllEvents(ical).getOrNull()!![0]
            assertEquals("original-alarm-uid", event.alarms[0].relatedTo)
        }
    }

    @Nested
    inner class DefaultAlarmParsing {

        @Test
        fun `parse alarm with DEFAULT-ALARM TRUE`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:event-4
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Default Alarm
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER:-PT30M
                DESCRIPTION:Default Reminder
                DEFAULT-ALARM:TRUE
                END:VALARM
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val event = parser.parseAllEvents(ical).getOrNull()!![0]
            assertTrue(event.alarms[0].defaultAlarm)
        }

        @Test
        fun `alarm without DEFAULT-ALARM has false default`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:event-5
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Normal Event
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER:-PT15M
                DESCRIPTION:Reminder
                END:VALARM
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val event = parser.parseAllEvents(ical).getOrNull()!![0]
            assertEquals(false, event.alarms[0].defaultAlarm)
        }
    }

    @Nested
    inner class ProximityParsing {

        @Test
        fun `parse alarm with PROXIMITY ARRIVE`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:event-6
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Location-based Event
                LOCATION:123 Main St
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER:-PT0M
                DESCRIPTION:You're arriving
                PROXIMITY:ARRIVE
                END:VALARM
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val event = parser.parseAllEvents(ical).getOrNull()!![0]
            assertEquals(AlarmProximity.ARRIVE, event.alarms[0].proximity)
        }

        @Test
        fun `parse alarm with PROXIMITY DEPART`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:event-7
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Location-based Event
                LOCATION:123 Main St
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER:-PT0M
                DESCRIPTION:You're leaving
                PROXIMITY:DEPART
                END:VALARM
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val event = parser.parseAllEvents(ical).getOrNull()!![0]
            assertEquals(AlarmProximity.DEPART, event.alarms[0].proximity)
        }
    }

    @Nested
    inner class RoundtripTests {

        @Test
        fun `alarm UID survives roundtrip`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-uid-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Roundtrip Test
                BEGIN:VALARM
                UID:alarm-roundtrip-123
                ACTION:DISPLAY
                TRIGGER:-PT15M
                DESCRIPTION:Reminder
                END:VALARM
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val event1 = parser.parseAllEvents(ical).getOrNull()!![0]
            val generated = generator.generate(event1, method = null)
            val event2 = parser.parseAllEvents(generated).getOrNull()!![0]

            assertEquals(event1.alarms[0].uid, event2.alarms[0].uid)
        }

        @Test
        fun `alarm PROXIMITY survives roundtrip`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-prox-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Proximity Test
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER:-PT0M
                DESCRIPTION:Location alert
                PROXIMITY:ARRIVE
                END:VALARM
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val event1 = parser.parseAllEvents(ical).getOrNull()!![0]
            val generated = generator.generate(event1, method = null)
            val event2 = parser.parseAllEvents(generated).getOrNull()!![0]

            assertEquals(event1.alarms[0].proximity, event2.alarms[0].proximity)
        }

        @Test
        fun `all RFC 9074 properties survive roundtrip`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-all-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Full RFC 9074 Test
                BEGIN:VALARM
                UID:full-alarm-uid
                ACTION:DISPLAY
                TRIGGER:-PT30M
                DESCRIPTION:Full test
                ACKNOWLEDGED:20231215T130000Z
                RELATED-TO:parent-alarm
                DEFAULT-ALARM:TRUE
                PROXIMITY:DEPART
                END:VALARM
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val event1 = parser.parseAllEvents(ical).getOrNull()!![0]
            val alarm1 = event1.alarms[0]

            assertEquals("full-alarm-uid", alarm1.uid)
            assertNotNull(alarm1.acknowledged)
            assertEquals("parent-alarm", alarm1.relatedTo)
            assertTrue(alarm1.defaultAlarm)
            assertEquals(AlarmProximity.DEPART, alarm1.proximity)

            // Generate and re-parse
            val generated = generator.generate(event1, method = null)
            val event2 = parser.parseAllEvents(generated).getOrNull()!![0]
            val alarm2 = event2.alarms[0]

            assertEquals(alarm1.uid, alarm2.uid)
            assertEquals(alarm1.relatedTo, alarm2.relatedTo)
            assertEquals(alarm1.defaultAlarm, alarm2.defaultAlarm)
            assertEquals(alarm1.proximity, alarm2.proximity)
        }

        @Test
        fun `alarm without RFC 9074 properties still works`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:basic-alarm-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Basic Alarm Event
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER:-PT15M
                DESCRIPTION:Basic Reminder
                END:VALARM
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val event1 = parser.parseAllEvents(ical).getOrNull()!![0]
            val alarm1 = event1.alarms[0]

            assertNull(alarm1.uid)
            assertNull(alarm1.acknowledged)
            assertNull(alarm1.relatedTo)
            assertEquals(false, alarm1.defaultAlarm)
            assertNull(alarm1.proximity)

            // Roundtrip should work
            val generated = generator.generate(event1, method = null)
            val event2 = parser.parseAllEvents(generated).getOrNull()!![0]

            assertEquals(1, event2.alarms.size)
        }
    }
}
