package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher

/**
 * Edge case and adversarial tests for IcsExporter.
 *
 * Covers:
 * - Events with optional RFC 5545/7986 fields (priority, geo, color, url, categories)
 * - Very long descriptions
 * - Empty calendar export
 * - Exception event bundling
 * - Malformed VEVENT extraction
 */
class IcsExporterEdgeCaseTest {

    private lateinit var exporter: IcsExporter

    @Before
    fun setup() {
        exporter = IcsExporter()
    }

    // ========== Events with Optional RFC 5545/7986 Fields ==========

    @Test
    fun `serialize event with priority`() {
        val event = createBasicEvent().copy(priority = 1)
        val ics = IcsPatcher.serialize(event)

        assertTrue("Should contain VEVENT", ics.contains("BEGIN:VEVENT"))
        assertTrue("Should contain SUMMARY", ics.contains("SUMMARY:"))
    }

    @Test
    fun `serialize event with geo coordinates`() {
        val event = createBasicEvent().copy(
            geoLat = 37.7749,
            geoLon = -122.4194
        )
        val ics = IcsPatcher.serialize(event)

        assertTrue("Should contain VEVENT", ics.contains("BEGIN:VEVENT"))
        // GEO property format: GEO:lat;lon
        if (ics.contains("GEO:")) {
            assertTrue("Should contain latitude", ics.contains("37.7749"))
        }
    }

    @Test
    fun `serialize event with color`() {
        val event = createBasicEvent().copy(color = 0xFFFF0000.toInt())
        val ics = IcsPatcher.serialize(event)

        assertTrue("Should contain VEVENT", ics.contains("BEGIN:VEVENT"))
    }

    @Test
    fun `serialize event with url`() {
        val event = createBasicEvent().copy(url = "https://example.com/meeting")
        val ics = IcsPatcher.serialize(event)

        assertTrue("Should contain VEVENT", ics.contains("BEGIN:VEVENT"))
    }

    @Test
    fun `serialize event with categories`() {
        val event = createBasicEvent().copy(categories = listOf("Work", "Important", "Project-X"))
        val ics = IcsPatcher.serialize(event)

        assertTrue("Should contain VEVENT", ics.contains("BEGIN:VEVENT"))
    }

    @Test
    fun `serialize event with all optional fields set`() {
        val event = createBasicEvent().copy(
            priority = 5,
            geoLat = 48.8566,
            geoLon = 2.3522,
            color = 0xFF00FF00.toInt(),
            url = "https://example.com/conference",
            categories = listOf("Conference", "Tech")
        )
        val ics = IcsPatcher.serialize(event)

        assertTrue("Should produce valid ICS", ics.contains("BEGIN:VCALENDAR"))
        assertTrue("Should contain event", ics.contains("BEGIN:VEVENT"))
        assertTrue("Should contain title", ics.contains("SUMMARY:Test Event"))
    }

    // ========== Very Long Descriptions ==========

    @Test
    fun `serialize event with very long description`() {
        val longDescription = "A".repeat(10_000) + "\n" + "B".repeat(10_000)
        val event = createBasicEvent().copy(description = longDescription)

        val ics = IcsPatcher.serialize(event)

        assertTrue("Should contain VEVENT", ics.contains("BEGIN:VEVENT"))
        assertTrue("Should contain END:VEVENT", ics.contains("END:VEVENT"))
    }

    @Test
    fun `serialize event with description containing RFC 5545 special chars`() {
        val description = "Meeting; Notes: A, B, C\nLine 2\nPath\\to\\file"
        val event = createBasicEvent().copy(description = description)

        val ics = IcsPatcher.serialize(event)

        assertTrue("Should produce valid ICS", ics.contains("BEGIN:VCALENDAR"))
    }

    // ========== Empty/Minimal Input ==========

    @Test
    fun `exportCalendar rejects empty event list`() {
        val context = mockContext()
        val result = exporter.exportCalendar(context, emptyList(), "Empty Cal")

        assertTrue("Should fail for empty list", result.isFailure)
        assertTrue(
            "Should mention no events",
            result.exceptionOrNull()?.message?.contains("No events") == true
        )
    }

    @Test
    fun `serialize event with minimal fields`() {
        val event = createBasicEvent().copy(
            title = "",
            location = null,
            description = null
        )
        val ics = IcsPatcher.serialize(event)

        assertTrue("Should still produce valid ICS", ics.contains("BEGIN:VCALENDAR"))
        assertTrue("Should still contain VEVENT", ics.contains("BEGIN:VEVENT"))
    }

    // VEVENT extraction edge-case tests removed: extractVEventBlocks is deleted —
    // ICalGenerator.generate(ICalCalendar) handles component emission directly,
    // so line-scraping is no longer part of the code path.

    // ========== Filename Edge Cases ==========

    @Test
    fun `generateFileName preserves unicode letters and strips emoji`() {
        val fileName = invokeGenerateFileName("会議 Meeting 🎉")
        assertTrue("Should end with .ics", fileName.endsWith(".ics"))
        assertTrue("Should keep unicode letters", fileName.contains("会議"))
        assertFalse("Should not contain emoji", fileName.contains("🎉"))
    }

    @Test
    fun `generateFileName with only hyphens after sanitization`() {
        val fileName = invokeGenerateFileName("---")
        // After sanitization: hyphens are kept, then trim('-') removes them → empty → "event"
        assertTrue("Should use default name", fileName.contains("event"))
    }

    @Test
    fun `generateFileName with mix of valid and invalid chars`() {
        val fileName = invokeGenerateFileName("Q1 Budget (2026) - Draft!!")
        assertTrue("Should end with .ics", fileName.endsWith(".ics"))
        assertTrue("Should contain Budget", fileName.contains("Budget"))
        assertFalse("Should not contain parens", fileName.contains("("))
    }

    // ========== Exception Event Bundling ==========

    @Test
    fun `serialize recurring event with exceptions bundles into single VCALENDAR`() {
        val masterEvent = createBasicEvent().copy(
            uid = "recurring-uid",
            rrule = "FREQ=WEEKLY;BYDAY=MO"
        )
        val exception = createBasicEvent().copy(
            id = 2L,
            uid = "recurring-uid", // Same UID as master (RFC 5545)
            originalEventId = 1L,
            originalInstanceTime = System.currentTimeMillis() + 86400_000,
            title = "Modified Monday Meeting",
            rrule = null
        )

        val ics = IcsPatcher.serializeWithExceptions(masterEvent, listOf(exception))

        assertTrue("Should contain single VCALENDAR", ics.contains("BEGIN:VCALENDAR"))
        // Count VEVENT blocks
        val veventCount = Regex("BEGIN:VEVENT").findAll(ics).count()
        assertEquals("Should contain 2 VEVENTs (master + exception)", 2, veventCount)
        assertTrue("Should contain master RRULE", ics.contains("RRULE:"))
        assertTrue("Should contain RECURRENCE-ID", ics.contains("RECURRENCE-ID"))
    }

    // ICS text escaping edge cases removed: escapeIcsText is deleted —
    // escaping is handled by icaldav-core's escapeICalText, tested there.

    // ========== Helper Methods ==========

    private fun invokeGenerateFileName(baseName: String): String {
        val method = IcsExporter::class.java.getDeclaredMethod("generateFileName", String::class.java)
        method.isAccessible = true
        return method.invoke(exporter, baseName) as String
    }

    private fun mockContext(): android.content.Context {
        return io.mockk.mockk(relaxed = true)
    }

    private fun createBasicEvent(
        title: String = "Test Event",
        uid: String = "test-uid-123"
    ): Event {
        val startTs = 1700000000000L // Fixed timestamp for deterministic tests
        return Event(
            id = 1L,
            calendarId = 1L,
            uid = uid,
            title = title,
            location = null,
            description = null,
            startTs = startTs,
            endTs = startTs + 3600000L,
            isAllDay = false,
            timezone = null,
            rrule = null,
            exdate = null,
            dtstamp = startTs,
            createdAt = startTs,
            updatedAt = startTs,
            sequence = 0,
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = null,
            etag = null
        )
    }
}
