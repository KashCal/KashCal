package org.onekash.icaldav.parser

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.ConferenceFeature
import org.onekash.icaldav.model.ImageDisplay
import org.onekash.icaldav.model.ParseResult
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for RFC 7986 property parsing and roundtrip.
 * Tests IMAGE and CONFERENCE properties.
 */
class ICalParserRfc7986Test {

    private lateinit var parser: ICalParser
    private lateinit var generator: ICalGenerator

    @BeforeEach
    fun setup() {
        parser = ICalParser()
        generator = ICalGenerator()
    }

    @Nested
    inner class ImagePropertyParsing {

        @Test
        fun `parse event with single IMAGE property`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:image-test-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Image
                IMAGE;VALUE=URI:https://example.com/event.png
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)

            val event = result.getOrNull()!![0]
            assertEquals(1, event.images.size)
            assertEquals("https://example.com/event.png", event.images[0].uri)
            assertEquals(ImageDisplay.GRAPHIC, event.images[0].display)
        }

        @Test
        fun `parse IMAGE with DISPLAY parameter`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:image-test-2
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Badge Image
                IMAGE;VALUE=URI;DISPLAY=BADGE:https://example.com/badge.png
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val event = result.getOrNull()!![0]

            assertEquals(ImageDisplay.BADGE, event.images[0].display)
        }

        @Test
        fun `parse IMAGE with FMTTYPE parameter`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:image-test-3
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with PNG Image
                IMAGE;VALUE=URI;FMTTYPE=image/png:https://example.com/image.png
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val event = result.getOrNull()!![0]

            assertEquals("image/png", event.images[0].mediaType)
        }

        @Test
        fun `parse multiple IMAGE properties`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:image-test-4
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Multiple Images
                IMAGE;VALUE=URI;DISPLAY=BADGE:https://example.com/badge.png
                IMAGE;VALUE=URI;DISPLAY=FULLSIZE:https://example.com/full.jpg
                IMAGE;VALUE=URI;DISPLAY=THUMBNAIL:https://example.com/thumb.jpg
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val event = result.getOrNull()!![0]

            assertEquals(3, event.images.size)
        }
    }

    @Nested
    inner class ConferencePropertyParsing {

        @Test
        fun `parse event with single CONFERENCE property`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:conf-test-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Video Meeting
                CONFERENCE;VALUE=URI:https://zoom.us/j/123456789
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)

            val event = result.getOrNull()!![0]
            assertEquals(1, event.conferences.size)
            assertEquals("https://zoom.us/j/123456789", event.conferences[0].uri)
        }

        @Test
        fun `parse CONFERENCE with FEATURE parameter`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:conf-test-2
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Video Meeting with Features
                CONFERENCE;VALUE=URI;FEATURE=VIDEO,AUDIO,SCREEN:https://zoom.us/j/123
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val event = result.getOrNull()!![0]

            val features = event.conferences[0].features
            assertTrue(features.contains(ConferenceFeature.VIDEO))
            assertTrue(features.contains(ConferenceFeature.AUDIO))
            assertTrue(features.contains(ConferenceFeature.SCREEN))
        }

        @Test
        fun `parse CONFERENCE with LABEL parameter`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:conf-test-3
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Labeled Meeting
                CONFERENCE;VALUE=URI;LABEL=Join Zoom Meeting:https://zoom.us/j/123
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val event = result.getOrNull()!![0]

            assertEquals("Join Zoom Meeting", event.conferences[0].label)
        }

        @Test
        fun `parse multiple CONFERENCE properties`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:conf-test-4
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Meeting with Multiple Options
                CONFERENCE;VALUE=URI;FEATURE=VIDEO;LABEL=Join Video:https://zoom.us/j/123
                CONFERENCE;VALUE=URI;FEATURE=PHONE;LABEL=Dial-in:tel:+1-555-123-4567
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val event = result.getOrNull()!![0]

            assertEquals(2, event.conferences.size)
        }

        @Test
        fun `parse phone dial-in CONFERENCE`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:conf-test-5
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Phone Meeting
                CONFERENCE;VALUE=URI;FEATURE=PHONE,AUDIO:tel:+1-555-123-4567
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val event = result.getOrNull()!![0]

            val conf = event.conferences[0]
            assertEquals("tel:+1-555-123-4567", conf.uri)
            assertTrue(conf.isPhoneDialIn())
            assertTrue(conf.hasAudio())
        }
    }

    @Nested
    inner class RoundtripTests {

        @Test
        fun `IMAGE property survives roundtrip`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-image-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Roundtrip Test
                IMAGE;VALUE=URI;DISPLAY=BADGE;FMTTYPE=image/png:https://example.com/logo.png
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            // Parse original
            val event1 = parser.parseAllEvents(ical).getOrNull()!![0]
            assertEquals(1, event1.images.size)

            // Generate
            val generated = generator.generate(event1, method = null)

            // Parse generated
            val event2 = parser.parseAllEvents(generated).getOrNull()!![0]

            // Verify
            assertEquals(event1.images.size, event2.images.size)
            assertEquals(event1.images[0].uri, event2.images[0].uri)
            assertEquals(event1.images[0].display, event2.images[0].display)
            assertEquals(event1.images[0].mediaType, event2.images[0].mediaType)
        }

        @Test
        fun `CONFERENCE property survives roundtrip`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-conf-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Conference Roundtrip Test
                CONFERENCE;VALUE=URI;FEATURE=VIDEO,AUDIO;LABEL=Join Meeting:https://zoom.us/j/123
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            // Parse original
            val event1 = parser.parseAllEvents(ical).getOrNull()!![0]
            assertEquals(1, event1.conferences.size)

            // Generate
            val generated = generator.generate(event1, method = null)

            // Parse generated
            val event2 = parser.parseAllEvents(generated).getOrNull()!![0]

            // Verify
            assertEquals(event1.conferences.size, event2.conferences.size)
            assertEquals(event1.conferences[0].uri, event2.conferences[0].uri)
            assertEquals(event1.conferences[0].features, event2.conferences[0].features)
            assertEquals(event1.conferences[0].label, event2.conferences[0].label)
        }

        @Test
        fun `multiple images and conferences survive roundtrip`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-multi-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Multi Property Test
                IMAGE;VALUE=URI;DISPLAY=BADGE:https://example.com/badge.png
                IMAGE;VALUE=URI;DISPLAY=FULLSIZE:https://example.com/poster.jpg
                CONFERENCE;VALUE=URI;FEATURE=VIDEO:https://zoom.us/j/123
                CONFERENCE;VALUE=URI;FEATURE=PHONE:tel:+1-555-123-4567
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            // Parse original
            val event1 = parser.parseAllEvents(ical).getOrNull()!![0]
            assertEquals(2, event1.images.size)
            assertEquals(2, event1.conferences.size)

            // Generate and re-parse
            val generated = generator.generate(event1, method = null)
            val event2 = parser.parseAllEvents(generated).getOrNull()!![0]

            // Verify counts preserved
            assertEquals(2, event2.images.size)
            assertEquals(2, event2.conferences.size)
        }

        @Test
        fun `event without RFC 7986 properties still works`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:no-rfc7986-props
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Basic Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val event1 = parser.parseAllEvents(ical).getOrNull()!![0]
            assertTrue(event1.images.isEmpty())
            assertTrue(event1.conferences.isEmpty())

            // Generate and re-parse
            val generated = generator.generate(event1, method = null)
            val event2 = parser.parseAllEvents(generated).getOrNull()!![0]

            assertTrue(event2.images.isEmpty())
            assertTrue(event2.conferences.isEmpty())
        }
    }

    @Nested
    inner class ColorPropertyTests {

        @Test
        fun `parse COLOR property preserves value`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:color-test-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Colored Event
                COLOR:crimson
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val event = parser.parseAllEvents(ical).getOrNull()!![0]
            assertEquals("crimson", event.color)
        }

        @Test
        fun `COLOR property survives roundtrip`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:color-roundtrip-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Colored Event
                COLOR:blue
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val event1 = parser.parseAllEvents(ical).getOrNull()!![0]
            val generated = generator.generate(event1, method = null)
            val event2 = parser.parseAllEvents(generated).getOrNull()!![0]

            assertEquals(event1.color, event2.color)
        }
    }
}
