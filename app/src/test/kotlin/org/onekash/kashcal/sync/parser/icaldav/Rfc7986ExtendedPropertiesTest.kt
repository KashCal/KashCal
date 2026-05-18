package org.onekash.kashcal.sync.parser.icaldav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * RFC 7986 compliance tests for iCalendar extended properties.
 *
 * Tests parsing (ICalEventMapper) and writing (IcsPatcher) of:
 * - Section 5.9: COLOR property (CSS hex color)
 * - Section 5.3: CATEGORIES property (comma-separated list)
 * - RFC 5545 Section 3.8.1.6: GEO property (latitude;longitude)
 * - RFC 5545 Section 3.8.4.6: URL property
 *
 * Each test verifies compliance through the public API:
 * - Parsing: ICalParser → ICalEventMapper.toEntity().event
 * - Writing: Event → IcsPatcher.generateFresh()
 * - Round-trip: Parse → Entity → Generate → Re-parse → Verify
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Rfc7986ExtendedPropertiesTest {

    private lateinit var parser: ICalParser

    @Before
    fun setup() {
        parser = ICalParser()
    }

    // ========== RFC 7986 Section 5.9: COLOR Property ==========

    @Test
    fun `COLOR parses 6-digit hex to ARGB int`() {
        // RFC 7986: COLOR value is CSS3 color (e.g., "#FF5733")
        val ics = icsWithProperties("COLOR:#FF5733")
        val entity = parseToEntity(ics)

        assertNotNull("COLOR should be parsed", entity.color)
        // #FF5733 → ARGB: 0xFFFF5733 (alpha=FF added by Android Color.parseColor)
        assertEquals(0xFFFF5733.toInt(), entity.color)
    }

    @Test
    fun `COLOR parses 3-digit shorthand hex`() {
        // CSS3 shorthand: #F00 = #FF0000
        val ics = icsWithProperties("COLOR:#F00")
        val entity = parseToEntity(ics)

        assertNotNull("3-digit COLOR should be parsed", entity.color)
        assertEquals(0xFFFF0000.toInt(), entity.color)
    }

    @Test
    fun `COLOR parses named color`() {
        // CSS3 named colors: "red", "blue", etc.
        val ics = icsWithProperties("COLOR:red")
        val entity = parseToEntity(ics)

        assertNotNull("Named color should be parsed", entity.color)
        assertEquals(0xFFFF0000.toInt(), entity.color)
    }

    @Test
    fun `COLOR null when property absent`() {
        val ics = icsWithProperties()
        val entity = parseToEntity(ics)

        assertNull("Missing COLOR should be null", entity.color)
    }

    @Test
    fun `COLOR null for unsupported format`() {
        // rgb() notation is not supported by Android Color.parseColor
        val ics = icsWithProperties("COLOR:rgb(255,0,0)")
        val entity = parseToEntity(ics)

        assertNull("Unsupported COLOR format should be null", entity.color)
    }

    @Test
    fun `COLOR parses black correctly`() {
        // Edge case: black is 0x000000 — ensure it's not confused with null
        val ics = icsWithProperties("COLOR:#000000")
        val entity = parseToEntity(ics)

        assertNotNull("Black COLOR should not be null", entity.color)
        assertEquals(0xFF000000.toInt(), entity.color)
    }

    @Test
    fun `COLOR writes 6-digit hex without alpha`() {
        // RFC 7986: COLOR is CSS3 hex — alpha channel not part of spec
        val event = createEvent(color = 0xFFFF5733.toInt())
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should write COLOR:#FF5733", ics.contains("COLOR:#FF5733"))
    }

    @Test
    fun `COLOR emits css3 name black for pure black`() {
        // Black is in the wheel palette as HueFamily.NEUTRAL, so it round-trips
        // as the CSS3 name rather than hex — more RFC 7986 §5.9 compliant.
        val event = createEvent(color = 0xFF000000.toInt())
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should write COLOR:black (CSS3 name)", ics.contains("COLOR:black"))
    }

    @Test
    fun `COLOR omitted when null`() {
        val event = createEvent(color = null)
        val ics = IcsPatcher.generateFresh(event)

        assertFalse("Null COLOR should not appear in ICS", ics.contains("COLOR:"))
    }

    @Test
    fun `COLOR round-trip preserves value`() {
        // Parse → Entity → Generate → Re-parse → same color
        val original = icsWithProperties("COLOR:#FF5733")
        val entity = parseToEntity(original)
        val regenerated = IcsPatcher.generateFresh(entity)
        val reparsed = parseToEntity(regenerated)

        assertEquals("COLOR should survive round-trip", entity.color, reparsed.color)
    }

    @Test
    fun `COLOR parses CSS3 named color mediumorchid to ARGB`() {
        // RFC 7986 §5.9: CSS3 named colors — Android Color.parseColor doesn't support
        // extended CSS3 names (mediumorchid, slategray, etc.). EventColorPalette fills the gap.
        val ics = icsWithProperties("COLOR:mediumorchid")
        val entity = parseToEntity(ics)

        assertNotNull("mediumorchid should be parsed", entity.color)
        assertEquals(0xFFBA55D3.toInt(), entity.color)
    }

    @Test
    fun `COLOR emits CSS3 name when hex matches palette slot`() {
        // When event.color matches a palette hex, emit the lowercase CSS3 name (RFC 7986 §5.9)
        val event = createEvent(color = 0xFFBA55D3.toInt())
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should emit COLOR:mediumorchid", ics.contains("COLOR:mediumorchid"))
        assertFalse("Should not emit hex fallback", ics.contains("COLOR:#BA55D3"))
    }

    @Test
    fun `COLOR emits hex fallback for non-palette hex`() {
        // Non-palette hex values fall back to #RRGGBB form (still valid per RFC 7986)
        val event = createEvent(color = 0xFF123456.toInt())
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should emit COLOR:#123456", ics.contains("COLOR:#123456"))
    }

    // ========== RFC 7986 Section 5.3: CATEGORIES Property ==========

    @Test
    fun `CATEGORIES parses single category`() {
        val ics = icsWithProperties("CATEGORIES:Meeting")
        val entity = parseToEntity(ics)

        assertNotNull("CATEGORIES should be parsed", entity.categories)
        assertEquals(1, entity.categories!!.size)
        assertEquals("Meeting", entity.categories!![0])
    }

    @Test
    fun `CATEGORIES parses multiple comma-separated values`() {
        // RFC 7986/5545: CATEGORIES can contain multiple comma-separated values
        val ics = icsWithProperties("CATEGORIES:Meeting,Work,Important")
        val entity = parseToEntity(ics)

        assertNotNull("CATEGORIES should be parsed", entity.categories)
        assertEquals(3, entity.categories!!.size)
        assertTrue(entity.categories!!.contains("Meeting"))
        assertTrue(entity.categories!!.contains("Work"))
        assertTrue(entity.categories!!.contains("Important"))
    }

    @Test
    fun `CATEGORIES null when property absent`() {
        val ics = icsWithProperties()
        val entity = parseToEntity(ics)

        assertNull("Missing CATEGORIES should be null", entity.categories)
    }

    @Test
    fun `CATEGORIES writes to ICS output`() {
        val event = createEvent(categories = listOf("Meeting", "Work"))
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should contain CATEGORIES", ics.contains("CATEGORIES:"))
    }

    @Test
    fun `CATEGORIES omitted when null`() {
        val event = createEvent(categories = null)
        val ics = IcsPatcher.generateFresh(event)

        assertFalse("Null CATEGORIES should not appear", ics.contains("CATEGORIES:"))
    }

    @Test
    fun `CATEGORIES round-trip preserves values`() {
        val original = icsWithProperties("CATEGORIES:Meeting,Work")
        val entity = parseToEntity(original)
        val regenerated = IcsPatcher.generateFresh(entity)
        val reparsed = parseToEntity(regenerated)

        assertNotNull("Round-tripped CATEGORIES should not be null", reparsed.categories)
        assertEquals(
            "CATEGORIES count should survive round-trip",
            entity.categories!!.size,
            reparsed.categories!!.size
        )
    }

    // ========== RFC 5545 Section 3.8.1.6: GEO Property ==========

    @Test
    fun `GEO parses latitude and longitude`() {
        // RFC 5545: GEO format is "latitude;longitude"
        val ics = icsWithProperties("GEO:37.386013;-122.082932")
        val entity = parseToEntity(ics)

        assertNotNull("GEO lat should be parsed", entity.geoLat)
        assertNotNull("GEO lon should be parsed", entity.geoLon)
        assertEquals(37.386013, entity.geoLat!!, 0.000001)
        assertEquals(-122.082932, entity.geoLon!!, 0.000001)
    }

    @Test
    fun `GEO null when property absent`() {
        val ics = icsWithProperties()
        val entity = parseToEntity(ics)

        assertNull("Missing GEO lat should be null", entity.geoLat)
        assertNull("Missing GEO lon should be null", entity.geoLon)
    }

    @Test
    fun `GEO handles zero coordinates`() {
        // Null Island (0,0) — edge case to not confuse with null
        val ics = icsWithProperties("GEO:0.0;0.0")
        val entity = parseToEntity(ics)

        assertNotNull("Zero lat should not be null", entity.geoLat)
        assertNotNull("Zero lon should not be null", entity.geoLon)
        assertEquals(0.0, entity.geoLat!!, 0.000001)
        assertEquals(0.0, entity.geoLon!!, 0.000001)
    }

    @Test
    fun `GEO handles negative coordinates`() {
        // Southern and western hemispheres
        val ics = icsWithProperties("GEO:-33.868820;151.209290")
        val entity = parseToEntity(ics)

        assertEquals(-33.868820, entity.geoLat!!, 0.000001)
        assertEquals(151.209290, entity.geoLon!!, 0.000001)
    }

    @Test
    fun `GEO writes semicolon-separated lat lon`() {
        val event = createEvent(geoLat = 37.386013, geoLon = -122.082932)
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should contain GEO:", ics.contains("GEO:"))
        assertTrue("Should contain latitude", ics.contains("37.386013"))
        assertTrue("Should contain semicolon separator", ics.contains(";"))
    }

    @Test
    fun `GEO omitted when lat or lon is null`() {
        val event = createEvent(geoLat = 37.386013, geoLon = null)
        val ics = IcsPatcher.generateFresh(event)

        assertFalse("GEO should be omitted when lon is null", ics.contains("GEO:"))
    }

    @Test
    fun `GEO round-trip preserves coordinates`() {
        val original = icsWithProperties("GEO:37.386013;-122.082932")
        val entity = parseToEntity(original)
        val regenerated = IcsPatcher.generateFresh(entity)
        val reparsed = parseToEntity(regenerated)

        assertNotNull("Round-tripped GEO should not be null", reparsed.geoLat)
        assertEquals("GEO lat should survive round-trip", entity.geoLat!!, reparsed.geoLat!!, 0.001)
        assertEquals("GEO lon should survive round-trip", entity.geoLon!!, reparsed.geoLon!!, 0.001)
    }

    // ========== RFC 5545 Section 3.8.4.6: URL Property ==========

    @Test
    fun `URL parses from iCal`() {
        val ics = icsWithProperties("URL:https://example.com/meeting/123")
        val entity = parseToEntity(ics)

        assertEquals("https://example.com/meeting/123", entity.url)
    }

    @Test
    fun `URL null when property absent`() {
        val ics = icsWithProperties()
        val entity = parseToEntity(ics)

        assertNull("Missing URL should be null", entity.url)
    }

    @Test
    fun `URL writes to ICS output`() {
        val event = createEvent(url = "https://example.com/meeting/123")
        val ics = IcsPatcher.generateFresh(event)

        assertTrue("Should contain URL", ics.contains("URL:https://example.com/meeting/123"))
    }

    @Test
    fun `URL omitted when null`() {
        val event = createEvent(url = null)
        val ics = IcsPatcher.generateFresh(event)

        assertFalse("Null URL should not appear", ics.contains("URL:"))
    }

    @Test
    fun `URL round-trip preserves value`() {
        val original = icsWithProperties("URL:https://example.com/meeting/123")
        val entity = parseToEntity(original)
        val regenerated = IcsPatcher.generateFresh(entity)
        val reparsed = parseToEntity(regenerated)

        assertEquals("URL should survive round-trip", entity.url, reparsed.url)
    }

    // ========== Helper Methods ==========

    /**
     * Build a minimal ICS string with optional extra properties injected into VEVENT.
     */
    private fun icsWithProperties(vararg properties: String): String {
        val extra = if (properties.isNotEmpty()) {
            "\n" + properties.joinToString("\n")
        } else ""
        return """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//RFC7986//EN
BEGIN:VEVENT
UID:rfc7986-test@kashcal.test
DTSTAMP:20260115T120000Z
DTSTART:20260120T090000Z
DTEND:20260120T100000Z
SUMMARY:RFC 7986 Test$extra
END:VEVENT
END:VCALENDAR"""
    }

    private fun parseToEntity(ics: String): Event {
        val events = parser.parseAllEvents(ics).getOrNull()!!
        return ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event
    }

    private fun createEvent(
        color: Int? = null,
        url: String? = null,
        categories: List<String>? = null,
        geoLat: Double? = null,
        geoLon: Double? = null
    ): Event {
        return Event(
            uid = "rfc7986-test@kashcal.test",
            calendarId = 1L,
            title = "RFC 7986 Test",
            startTs = ZonedDateTime.of(2026, 1, 20, 9, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(),
            endTs = ZonedDateTime.of(2026, 1, 20, 10, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(),
            isAllDay = false,
            timezone = "UTC",
            status = "CONFIRMED",
            transp = "OPAQUE",
            classification = "PUBLIC",
            priority = 0,
            geoLat = geoLat,
            geoLon = geoLon,
            color = color,
            url = url,
            categories = categories,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
    }
}
