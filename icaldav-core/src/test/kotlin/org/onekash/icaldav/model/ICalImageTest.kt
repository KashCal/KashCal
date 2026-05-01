package org.onekash.icaldav.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for ICalImage and ImageDisplay (RFC 7986).
 */
class ICalImageTest {

    @Nested
    inner class ImageDisplayTests {

        @Test
        fun `fromString parses BADGE correctly`() {
            assertEquals(ImageDisplay.BADGE, ImageDisplay.fromString("BADGE"))
            assertEquals(ImageDisplay.BADGE, ImageDisplay.fromString("badge"))
            assertEquals(ImageDisplay.BADGE, ImageDisplay.fromString("Badge"))
        }

        @Test
        fun `fromString parses GRAPHIC correctly`() {
            assertEquals(ImageDisplay.GRAPHIC, ImageDisplay.fromString("GRAPHIC"))
            assertEquals(ImageDisplay.GRAPHIC, ImageDisplay.fromString("graphic"))
        }

        @Test
        fun `fromString parses FULLSIZE correctly`() {
            assertEquals(ImageDisplay.FULLSIZE, ImageDisplay.fromString("FULLSIZE"))
            assertEquals(ImageDisplay.FULLSIZE, ImageDisplay.fromString("fullsize"))
        }

        @Test
        fun `fromString parses THUMBNAIL correctly`() {
            assertEquals(ImageDisplay.THUMBNAIL, ImageDisplay.fromString("THUMBNAIL"))
            assertEquals(ImageDisplay.THUMBNAIL, ImageDisplay.fromString("thumbnail"))
        }

        @Test
        fun `fromString returns GRAPHIC for null`() {
            assertEquals(ImageDisplay.GRAPHIC, ImageDisplay.fromString(null))
        }

        @Test
        fun `fromString returns GRAPHIC for empty string`() {
            assertEquals(ImageDisplay.GRAPHIC, ImageDisplay.fromString(""))
        }

        @Test
        fun `fromString returns GRAPHIC for unknown value`() {
            assertEquals(ImageDisplay.GRAPHIC, ImageDisplay.fromString("UNKNOWN"))
            assertEquals(ImageDisplay.GRAPHIC, ImageDisplay.fromString("invalid"))
        }
    }

    @Nested
    inner class ICalImageConstructorTests {

        @Test
        fun `create image with uri only`() {
            val image = ICalImage(uri = "https://example.com/image.png")

            assertEquals("https://example.com/image.png", image.uri)
            assertEquals(ImageDisplay.GRAPHIC, image.display)
            assertNull(image.mediaType)
            assertNull(image.altText)
        }

        @Test
        fun `create image with all parameters`() {
            val image = ICalImage(
                uri = "https://example.com/logo.png",
                display = ImageDisplay.BADGE,
                mediaType = "image/png",
                altText = "Company Logo"
            )

            assertEquals("https://example.com/logo.png", image.uri)
            assertEquals(ImageDisplay.BADGE, image.display)
            assertEquals("image/png", image.mediaType)
            assertEquals("Company Logo", image.altText)
        }
    }

    @Nested
    inner class ToICalStringTests {

        @Test
        fun `toICalString with uri only uses default display`() {
            val image = ICalImage(uri = "https://example.com/image.png")
            val ical = image.toICalString()

            assertEquals("IMAGE;VALUE=URI:https://example.com/image.png", ical)
        }

        @Test
        fun `toICalString includes DISPLAY when not GRAPHIC`() {
            val image = ICalImage(
                uri = "https://example.com/image.png",
                display = ImageDisplay.BADGE
            )
            val ical = image.toICalString()

            assertEquals("IMAGE;VALUE=URI;DISPLAY=BADGE:https://example.com/image.png", ical)
        }

        @Test
        fun `toICalString includes FMTTYPE when present`() {
            val image = ICalImage(
                uri = "https://example.com/image.png",
                mediaType = "image/png"
            )
            val ical = image.toICalString()

            assertEquals("IMAGE;VALUE=URI;FMTTYPE=image/png:https://example.com/image.png", ical)
        }

        @Test
        fun `toICalString includes all parameters`() {
            val image = ICalImage(
                uri = "https://example.com/logo.png",
                display = ImageDisplay.THUMBNAIL,
                mediaType = "image/jpeg",
                altText = "Event thumbnail"
            )
            val ical = image.toICalString()

            assert(ical.contains("VALUE=URI"))
            assert(ical.contains("DISPLAY=THUMBNAIL"))
            assert(ical.contains("FMTTYPE=image/jpeg"))
            assert(ical.contains("ALTREP=\"Event thumbnail\""))
            assert(ical.endsWith(":https://example.com/logo.png"))
        }
    }

    @Nested
    inner class FromParametersTests {

        @Test
        fun `fromParameters with minimal values`() {
            val image = ICalImage.fromParameters(uri = "https://example.com/image.png")

            assertEquals("https://example.com/image.png", image.uri)
            assertEquals(ImageDisplay.GRAPHIC, image.display)
        }

        @Test
        fun `fromParameters with all values`() {
            val image = ICalImage.fromParameters(
                uri = "https://example.com/image.png",
                displayValue = "BADGE",
                fmttype = "image/png",
                altrep = "Alt text"
            )

            assertEquals("https://example.com/image.png", image.uri)
            assertEquals(ImageDisplay.BADGE, image.display)
            assertEquals("image/png", image.mediaType)
            assertEquals("Alt text", image.altText)
        }
    }
}
