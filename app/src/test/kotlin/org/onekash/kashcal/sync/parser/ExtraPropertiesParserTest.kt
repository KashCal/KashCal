package org.onekash.kashcal.sync.parser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for TRANSP, CLASS, and X-* property parsing in Ical4jParser.
 * Verifies RFC 5545 extra property preservation for round-trip sync.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ExtraPropertiesParserTest {

    private lateinit var parser: Ical4jParser

    @Before
    fun setup() {
        parser = Ical4jParser()
    }

    // ========== TRANSP (Time Transparency) Tests ==========

    @Test
    fun `parse TRANSP TRANSPARENT`() {
        val ical = loadTestFile("extra_properties/transp_transparent.ics")
        val result = parser.parse(ical)

        assertTrue("Should parse successfully", result.isSuccess())
        val event = result.events.first()
        assertEquals("TRANSPARENT", event.transp)
    }

    @Test
    fun `parse TRANSP OPAQUE`() {
        val ical = loadTestFile("extra_properties/transp_opaque.ics")
        val result = parser.parse(ical)

        assertTrue(result.isSuccess())
        val event = result.events.first()
        assertEquals("OPAQUE", event.transp)
    }

    @Test
    fun `missing TRANSP returns null`() {
        val ical = loadTestFile("basic/simple_event.ics")
        val result = parser.parse(ical)

        assertTrue(result.isSuccess())
        val event = result.events.first()
        assertNull("Missing TRANSP should be null", event.transp)
    }

    // ========== CLASS (Access Classification) Tests ==========

    @Test
    fun `parse CLASS PRIVATE`() {
        val ical = loadTestFile("extra_properties/class_private.ics")
        val result = parser.parse(ical)

        assertTrue(result.isSuccess())
        val event = result.events.first()
        assertEquals("PRIVATE", event.classification)
    }

    @Test
    fun `parse CLASS CONFIDENTIAL`() {
        val ical = loadTestFile("extra_properties/class_confidential.ics")
        val result = parser.parse(ical)

        assertTrue(result.isSuccess())
        val event = result.events.first()
        assertEquals("CONFIDENTIAL", event.classification)
    }

    @Test
    fun `missing CLASS returns null`() {
        val ical = loadTestFile("basic/simple_event.ics")
        val result = parser.parse(ical)

        assertTrue(result.isSuccess())
        val event = result.events.first()
        assertNull("Missing CLASS should be null", event.classification)
    }

    // ========== X-* Property Tests ==========

    @Test
    fun `parse X-APPLE properties`() {
        val ical = loadTestFile("extra_properties/x_apple_simple.ics")
        val result = parser.parse(ical)

        assertTrue(result.isSuccess())
        val event = result.events.first()
        assertNotNull("Should have extraProperties", event.extraProperties)

        val extras = event.extraProperties!!
        assertTrue("Should contain X-APPLE-TRAVEL-ADVISORY-BEHAVIOR",
            extras.keys.any { it.startsWith("X-APPLE-TRAVEL-ADVISORY-BEHAVIOR") })
        assertTrue("Should contain X-APPLE-CREATOR-IDENTITY",
            extras.keys.any { it.startsWith("X-APPLE-CREATOR-IDENTITY") })
    }

    @Test
    fun `parse X-APPLE property with parameters`() {
        val ical = loadTestFile("extra_properties/x_apple_with_params.ics")
        val result = parser.parse(ical)

        assertTrue(result.isSuccess())
        val event = result.events.first()
        assertNotNull("Should have extraProperties", event.extraProperties)

        val extras = event.extraProperties!!
        // Property key should include parameters
        val locationKey = extras.keys.find { it.startsWith("X-APPLE-STRUCTURED-LOCATION") }
        assertNotNull("Should have X-APPLE-STRUCTURED-LOCATION", locationKey)
        // Value should be the geo URI
        assertTrue("Value should be geo URI", extras[locationKey]!!.startsWith("geo:"))
    }

    @Test
    fun `no X-* properties returns null map`() {
        val ical = loadTestFile("basic/simple_event.ics")
        val result = parser.parse(ical)

        assertTrue(result.isSuccess())
        val event = result.events.first()
        assertNull("No X-* properties should return null", event.extraProperties)
    }

    // ========== Combined Tests ==========

    @Test
    fun `parse full iCloud event with all extras`() {
        val ical = loadTestFile("extra_properties/full_icloud_event.ics")
        val result = parser.parse(ical)

        assertTrue(result.isSuccess())
        val event = result.events.first()

        // Verify TRANSP
        assertEquals("TRANSPARENT", event.transp)

        // Verify CLASS
        assertEquals("PRIVATE", event.classification)

        // Verify X-* properties
        assertNotNull("Should have extraProperties", event.extraProperties)
        val extras = event.extraProperties!!
        assertTrue("Should have multiple X-APPLE properties", extras.size >= 2)
    }

    // ========== Round-Trip Tests ==========

    @Test
    fun `round trip TRANSP TRANSPARENT`() {
        val ical = loadTestFile("extra_properties/transp_transparent.ics")
        val parsed = parser.parse(ical)
        assertTrue(parsed.isSuccess())
        assertEquals("TRANSPARENT", parsed.events.first().transp)

        val generated = parser.generate(parsed.events)
        assertTrue("Generated iCal should contain TRANSP:TRANSPARENT",
            generated.contains("TRANSP:TRANSPARENT"))

        val reparsed = parser.parse(generated)
        assertTrue(reparsed.isSuccess())
        assertEquals("TRANSPARENT", reparsed.events.first().transp)
    }

    @Test
    fun `round trip CLASS PRIVATE`() {
        val ical = loadTestFile("extra_properties/class_private.ics")
        val parsed = parser.parse(ical)
        assertTrue(parsed.isSuccess())
        assertEquals("PRIVATE", parsed.events.first().classification)

        val generated = parser.generate(parsed.events)
        assertTrue("Generated iCal should contain CLASS:PRIVATE",
            generated.contains("CLASS:PRIVATE"))

        val reparsed = parser.parse(generated)
        assertTrue(reparsed.isSuccess())
        assertEquals("PRIVATE", reparsed.events.first().classification)
    }

    @Test
    fun `round trip X-APPLE properties`() {
        val ical = loadTestFile("extra_properties/x_apple_simple.ics")
        val parsed = parser.parse(ical)
        assertTrue(parsed.isSuccess())

        val originalExtras = parsed.events.first().extraProperties!!
        assertTrue(originalExtras.isNotEmpty())

        val generated = parser.generate(parsed.events)
        assertTrue("Generated should contain X-APPLE-TRAVEL-ADVISORY-BEHAVIOR",
            generated.contains("X-APPLE-TRAVEL-ADVISORY-BEHAVIOR"))

        val reparsed = parser.parse(generated)
        assertTrue(reparsed.isSuccess())

        val roundTrippedExtras = reparsed.events.first().extraProperties!!
        assertEquals("Should preserve X-* property count",
            originalExtras.size, roundTrippedExtras.size)
    }

    @Test
    fun `default values not output when generating`() {
        // OPAQUE and PUBLIC are defaults, should not be in output
        val ical = loadTestFile("extra_properties/transp_opaque.ics")
        val parsed = parser.parse(ical)
        assertTrue(parsed.isSuccess())
        assertEquals("OPAQUE", parsed.events.first().transp)

        val generated = parser.generate(parsed.events)
        // OPAQUE is default, should not be explicitly output
        assertFalse("OPAQUE (default) should not be in output",
            generated.contains("TRANSP:OPAQUE"))
    }

    // ========== Helper Functions ==========

    private fun loadTestFile(relativePath: String): String {
        val resourcePath = "/ical/$relativePath"
        val inputStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Test file not found: $resourcePath")
        return inputStream.bufferedReader().use { it.readText() }
    }
}
