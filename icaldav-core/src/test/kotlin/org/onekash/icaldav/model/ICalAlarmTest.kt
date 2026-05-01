package org.onekash.icaldav.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * Comprehensive tests for ICalAlarm model per RFC 5545 and RFC 9074.
 *
 * Tests cover:
 * - Alarm construction with different triggers
 * - Duration parsing (RFC 5545 Section 3.3.6)
 * - AlarmAction enum parsing
 * - AlarmProximity enum parsing (RFC 9074)
 * - RFC 9074 extensions (ACKNOWLEDGED, UID, DEFAULT-ALARM)
 * - Edge cases and validation
 */
class ICalAlarmTest {

    // ==================== Basic Alarm Construction ====================

    @Nested
    inner class BasicConstructionTests {

        @Test
        fun `create display alarm with relative trigger`() {
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ofMinutes(-15),
                triggerAbsolute = null,
                description = "Event starting soon",
                summary = null
            )

            assertEquals(AlarmAction.DISPLAY, alarm.action)
            assertEquals(Duration.ofMinutes(-15), alarm.trigger)
            assertNull(alarm.triggerAbsolute)
            assertEquals("Event starting soon", alarm.description)
            assertEquals(-15, alarm.triggerMinutes())
        }

        @Test
        fun `create audio alarm`() {
            val alarm = ICalAlarm(
                action = AlarmAction.AUDIO,
                trigger = Duration.ofMinutes(-30),
                triggerAbsolute = null,
                description = null,
                summary = null
            )

            assertEquals(AlarmAction.AUDIO, alarm.action)
            assertEquals(-30, alarm.triggerMinutes())
        }

        @Test
        fun `create email alarm with summary`() {
            val alarm = ICalAlarm(
                action = AlarmAction.EMAIL,
                trigger = Duration.ofHours(-1),
                triggerAbsolute = null,
                description = "Email body",
                summary = "Email subject"
            )

            assertEquals(AlarmAction.EMAIL, alarm.action)
            assertEquals(-60, alarm.triggerMinutes())
            assertEquals("Email subject", alarm.summary)
            assertEquals("Email body", alarm.description)
        }

        @Test
        fun `create alarm with absolute trigger`() {
            val absoluteTime = ICalDateTime.parse("20231215T090000Z")
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = null,
                triggerAbsolute = absoluteTime,
                description = "Reminder",
                summary = null
            )

            assertNull(alarm.trigger)
            assertEquals(absoluteTime, alarm.triggerAbsolute)
            assertNull(alarm.triggerMinutes()) // null for absolute triggers
        }
    }

    // ==================== Trigger Minutes Calculation ====================

    @Nested
    inner class TriggerMinutesTests {

        @Test
        fun `triggerMinutes converts negative duration`() {
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ofMinutes(-15),
                triggerAbsolute = null,
                description = null,
                summary = null
            )
            assertEquals(-15, alarm.triggerMinutes())
        }

        @Test
        fun `triggerMinutes converts positive duration`() {
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ofMinutes(30),
                triggerAbsolute = null,
                description = null,
                summary = null
            )
            assertEquals(30, alarm.triggerMinutes())
        }

        @Test
        fun `triggerMinutes converts hours to minutes`() {
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ofHours(-2),
                triggerAbsolute = null,
                description = null,
                summary = null
            )
            assertEquals(-120, alarm.triggerMinutes())
        }

        @Test
        fun `triggerMinutes converts combined duration`() {
            // -PT1H30M = 1 hour 30 minutes before
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ofHours(-1).minusMinutes(30),
                triggerAbsolute = null,
                description = null,
                summary = null
            )
            assertEquals(-90, alarm.triggerMinutes())
        }

        @Test
        fun `triggerMinutes returns null for absolute trigger`() {
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = null,
                triggerAbsolute = ICalDateTime.parse("20231215T090000Z"),
                description = null,
                summary = null
            )
            assertNull(alarm.triggerMinutes())
        }

        @Test
        fun `triggerMinutes handles zero duration`() {
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ZERO,
                triggerAbsolute = null,
                description = null,
                summary = null
            )
            assertEquals(0, alarm.triggerMinutes())
        }
    }

    // ==================== Duration Parsing Tests ====================

    @Nested
    inner class DurationParsingTests {

        @Test
        fun `parseDuration handles negative minutes`() {
            val duration = ICalAlarm.parseDuration("-PT15M")
            assertEquals(Duration.ofMinutes(-15), duration)
        }

        @Test
        fun `parseDuration handles positive minutes`() {
            val duration = ICalAlarm.parseDuration("PT15M")
            assertEquals(Duration.ofMinutes(15), duration)
        }

        @Test
        fun `parseDuration handles hours`() {
            val duration = ICalAlarm.parseDuration("-PT1H")
            assertEquals(Duration.ofHours(-1), duration)
        }

        @Test
        fun `parseDuration handles combined hours and minutes`() {
            val duration = ICalAlarm.parseDuration("-PT1H30M")
            assertEquals(Duration.ofMinutes(-90), duration)
        }

        @Test
        fun `parseDuration handles days`() {
            val duration = ICalAlarm.parseDuration("-P1D")
            assertEquals(Duration.ofDays(-1), duration)
        }

        @Test
        fun `parseDuration handles days with time`() {
            val duration = ICalAlarm.parseDuration("-P1DT2H")
            assertEquals(Duration.ofHours(-26), duration) // 24 + 2
        }

        @Test
        fun `parseDuration handles weeks`() {
            val duration = ICalAlarm.parseDuration("-P1W")
            assertEquals(Duration.ofDays(-7), duration)
        }

        @Test
        fun `parseDuration handles seconds`() {
            val duration = ICalAlarm.parseDuration("PT30S")
            assertEquals(Duration.ofSeconds(30), duration)
        }

        @Test
        fun `parseDuration handles complex duration`() {
            // P1DT2H30M15S = 1 day, 2 hours, 30 minutes, 15 seconds
            val duration = ICalAlarm.parseDuration("P1DT2H30M15S")
            val expected = Duration.ofDays(1)
                .plusHours(2)
                .plusMinutes(30)
                .plusSeconds(15)
            assertEquals(expected, duration)
        }

        @Test
        fun `parseDuration throws for invalid format`() {
            assertThrows<IllegalArgumentException> {
                ICalAlarm.parseDuration("invalid")
            }
        }

        @Test
        fun `parseDuration throws for empty string`() {
            assertThrows<IllegalArgumentException> {
                ICalAlarm.parseDuration("")
            }
        }
    }

    // ==================== Duration Formatting Tests ====================

    @Nested
    inner class DurationFormattingTests {

        @Test
        fun `formatDuration formats negative minutes`() {
            val formatted = ICalAlarm.formatDuration(Duration.ofMinutes(-15))
            assertEquals("-PT15M", formatted)
        }

        @Test
        fun `formatDuration formats positive minutes`() {
            val formatted = ICalAlarm.formatDuration(Duration.ofMinutes(15))
            assertEquals("PT15M", formatted)
        }

        @Test
        fun `formatDuration formats hours`() {
            val formatted = ICalAlarm.formatDuration(Duration.ofHours(-2))
            assertEquals("-PT2H", formatted)
        }

        @Test
        fun `formatDuration handles zero duration`() {
            val formatted = ICalAlarm.formatDuration(Duration.ZERO)
            assertEquals("PT0S", formatted)
        }
    }

    // ==================== AlarmAction Tests ====================

    @Nested
    inner class AlarmActionTests {

        @Test
        fun `fromString parses DISPLAY`() {
            assertEquals(AlarmAction.DISPLAY, AlarmAction.fromString("DISPLAY"))
            assertEquals(AlarmAction.DISPLAY, AlarmAction.fromString("display"))
        }

        @Test
        fun `fromString parses AUDIO`() {
            assertEquals(AlarmAction.AUDIO, AlarmAction.fromString("AUDIO"))
            assertEquals(AlarmAction.AUDIO, AlarmAction.fromString("audio"))
        }

        @Test
        fun `fromString parses EMAIL`() {
            assertEquals(AlarmAction.EMAIL, AlarmAction.fromString("EMAIL"))
            assertEquals(AlarmAction.EMAIL, AlarmAction.fromString("email"))
        }

        @Test
        fun `fromString defaults to DISPLAY for unknown values`() {
            assertEquals(AlarmAction.DISPLAY, AlarmAction.fromString(null))
            assertEquals(AlarmAction.DISPLAY, AlarmAction.fromString(""))
            assertEquals(AlarmAction.DISPLAY, AlarmAction.fromString("UNKNOWN"))
            assertEquals(AlarmAction.DISPLAY, AlarmAction.fromString("PROCEDURE")) // Deprecated
        }
    }

    // ==================== AlarmProximity Tests (RFC 9074) ====================

    @Nested
    inner class AlarmProximityTests {

        @Test
        fun `fromString parses ARRIVE`() {
            assertEquals(AlarmProximity.ARRIVE, AlarmProximity.fromString("ARRIVE"))
            assertEquals(AlarmProximity.ARRIVE, AlarmProximity.fromString("arrive"))
        }

        @Test
        fun `fromString parses DEPART`() {
            assertEquals(AlarmProximity.DEPART, AlarmProximity.fromString("DEPART"))
            assertEquals(AlarmProximity.DEPART, AlarmProximity.fromString("depart"))
        }

        @Test
        fun `fromString returns null for unknown values`() {
            assertNull(AlarmProximity.fromString(null))
            assertNull(AlarmProximity.fromString(""))
            assertNull(AlarmProximity.fromString("UNKNOWN"))
        }

        @Test
        fun `toICalString returns correct format`() {
            assertEquals("ARRIVE", AlarmProximity.ARRIVE.toICalString())
            assertEquals("DEPART", AlarmProximity.DEPART.toICalString())
        }
    }

    // ==================== RFC 9074 Extension Tests ====================

    @Nested
    inner class Rfc9074ExtensionTests {

        @Test
        fun `alarm with acknowledged timestamp`() {
            val acknowledgedTime = ICalDateTime.parse("20231215T100500Z")
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ofMinutes(-15),
                triggerAbsolute = null,
                description = "Reminder",
                summary = null,
                acknowledged = acknowledgedTime
            )

            assertNotNull(alarm.acknowledged)
            assertEquals(acknowledgedTime, alarm.acknowledged)
        }

        @Test
        fun `alarm with UID`() {
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ofMinutes(-15),
                triggerAbsolute = null,
                description = "Reminder",
                summary = null,
                uid = "alarm-uid-123"
            )

            assertEquals("alarm-uid-123", alarm.uid)
        }

        @Test
        fun `alarm with relatedTo for snooze`() {
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ofMinutes(5), // 5 minutes from now (snooze)
                triggerAbsolute = null,
                description = "Snoozed reminder",
                summary = null,
                relatedTo = "original-alarm-uid"
            )

            assertEquals("original-alarm-uid", alarm.relatedTo)
        }

        @Test
        fun `alarm marked as default`() {
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ofMinutes(-15),
                triggerAbsolute = null,
                description = "Default reminder",
                summary = null,
                defaultAlarm = true
            )

            assertTrue(alarm.defaultAlarm)
        }

        @Test
        fun `alarm with proximity trigger`() {
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = null, // Proximity alarms don't use time triggers
                triggerAbsolute = null,
                description = "Location reminder",
                summary = null,
                proximity = AlarmProximity.ARRIVE
            )

            assertEquals(AlarmProximity.ARRIVE, alarm.proximity)
        }
    }

    // ==================== Repeat Alarm Tests ====================

    @Nested
    inner class RepeatAlarmTests {

        @Test
        fun `alarm with repeat count and duration`() {
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ofMinutes(-15),
                triggerAbsolute = null,
                description = "Repeating reminder",
                summary = null,
                repeatCount = 3,
                repeatDuration = Duration.ofMinutes(5)
            )

            assertEquals(3, alarm.repeatCount)
            assertEquals(Duration.ofMinutes(5), alarm.repeatDuration)
        }

        @Test
        fun `default repeat count is zero`() {
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ofMinutes(-15),
                triggerAbsolute = null,
                description = null,
                summary = null
            )

            assertEquals(0, alarm.repeatCount)
            assertNull(alarm.repeatDuration)
        }
    }

    // ==================== Trigger Related To End Tests ====================

    @Nested
    inner class TriggerRelatedTests {

        @Test
        fun `default trigger is related to start`() {
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ofMinutes(-15),
                triggerAbsolute = null,
                description = null,
                summary = null
            )

            assertFalse(alarm.triggerRelatedToEnd)
        }

        @Test
        fun `trigger can be related to end`() {
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ofMinutes(0), // At event end
                triggerAbsolute = null,
                description = "End of event reminder",
                summary = null,
                triggerRelatedToEnd = true
            )

            assertTrue(alarm.triggerRelatedToEnd)
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    inner class EdgeCases {

        @Test
        fun `alarm with null trigger and null absolute trigger`() {
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = null,
                triggerAbsolute = null,
                description = null,
                summary = null
            )

            assertNull(alarm.trigger)
            assertNull(alarm.triggerAbsolute)
            assertNull(alarm.triggerMinutes())
        }

        @Test
        fun `alarm with very long duration`() {
            // 1 week before
            val duration = Duration.ofDays(-7)
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = duration,
                triggerAbsolute = null,
                description = null,
                summary = null
            )

            assertEquals(-7 * 24 * 60, alarm.triggerMinutes())
        }

        @Test
        fun `alarm with all optional fields`() {
            val alarm = ICalAlarm(
                action = AlarmAction.EMAIL,
                trigger = Duration.ofMinutes(-30),
                triggerAbsolute = null,
                triggerRelatedToEnd = false,
                description = "Email body",
                summary = "Email subject",
                repeatCount = 2,
                repeatDuration = Duration.ofMinutes(10),
                uid = "alarm-123",
                acknowledged = ICalDateTime.parse("20231215T100000Z"),
                relatedTo = "parent-alarm",
                defaultAlarm = false,
                proximity = null
            )

            assertNotNull(alarm.uid)
            assertNotNull(alarm.acknowledged)
            assertNotNull(alarm.relatedTo)
        }

        @Test
        fun `data class equality`() {
            val alarm1 = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ofMinutes(-15),
                triggerAbsolute = null,
                description = "Test",
                summary = null
            )
            val alarm2 = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ofMinutes(-15),
                triggerAbsolute = null,
                description = "Test",
                summary = null
            )

            assertEquals(alarm1, alarm2)
        }

        @Test
        fun `data class copy`() {
            val original = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ofMinutes(-15),
                triggerAbsolute = null,
                description = "Original",
                summary = null
            )
            val modified = original.copy(description = "Modified")

            assertNotEquals(original.description, modified.description)
            assertEquals("Modified", modified.description)
        }
    }
}
