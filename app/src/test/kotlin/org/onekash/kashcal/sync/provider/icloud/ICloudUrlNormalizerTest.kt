package org.onekash.kashcal.sync.provider.icloud

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ICloudUrlNormalizer.
 *
 * Tests URL normalization from regional iCloud servers to canonical form.
 */
class ICloudUrlNormalizerTest {

    // ========== Normalization - Basic ==========

    @Test
    fun `normalize converts p180-caldav to caldav`() {
        val input = "https://p180-caldav.icloud.com/12345678/calendars/calendar-id/"
        val expected = "https://caldav.icloud.com/12345678/calendars/calendar-id/"
        assertEquals(expected, ICloudUrlNormalizer.normalize(input))
    }

    @Test
    fun `normalize converts p180-caldav with port 443 to caldav without port`() {
        val input = "https://p180-caldav.icloud.com:443/12345678/calendars/calendar-id/"
        val expected = "https://caldav.icloud.com/12345678/calendars/calendar-id/"
        assertEquals(expected, ICloudUrlNormalizer.normalize(input))
    }

    @Test
    fun `normalize handles p1-caldav single digit`() {
        val input = "https://p1-caldav.icloud.com/path"
        val expected = "https://caldav.icloud.com/path"
        assertEquals(expected, ICloudUrlNormalizer.normalize(input))
    }

    @Test
    fun `normalize handles p9999-caldav large number`() {
        val input = "https://p9999-caldav.icloud.com/path"
        val expected = "https://caldav.icloud.com/path"
        assertEquals(expected, ICloudUrlNormalizer.normalize(input))
    }

    @Test
    fun `normalize preserves path after hostname`() {
        val input = "https://p180-caldav.icloud.com:443/12345678/calendars/ABC123-DEF456-GHI789/events/event.ics"
        val expected = "https://caldav.icloud.com/12345678/calendars/ABC123-DEF456-GHI789/events/event.ics"
        assertEquals(expected, ICloudUrlNormalizer.normalize(input))
    }

    @Test
    fun `normalize returns null for null input`() {
        assertNull(ICloudUrlNormalizer.normalize(null))
    }

    @Test
    fun `normalize preserves non-icloud URLs unchanged`() {
        val input = "https://caldav.example.com/calendars/"
        assertEquals(input, ICloudUrlNormalizer.normalize(input))
    }

    @Test
    fun `normalize preserves caldav icloud com unchanged`() {
        val input = "https://caldav.icloud.com/12345678/calendars/"
        assertEquals(input, ICloudUrlNormalizer.normalize(input))
    }

    // ========== Detection - isRegionalUrl ==========

    @Test
    fun `isRegionalUrl returns true for p180-caldav icloud com`() {
        assertTrue(ICloudUrlNormalizer.isRegionalUrl("https://p180-caldav.icloud.com/path"))
    }

    @Test
    fun `isRegionalUrl returns true for p180-caldav icloud com with port`() {
        assertTrue(ICloudUrlNormalizer.isRegionalUrl("https://p180-caldav.icloud.com:443/path"))
    }

    @Test
    fun `isRegionalUrl returns false for caldav icloud com`() {
        assertFalse(ICloudUrlNormalizer.isRegionalUrl("https://caldav.icloud.com/path"))
    }

    @Test
    fun `isRegionalUrl returns false for non-icloud URLs`() {
        assertFalse(ICloudUrlNormalizer.isRegionalUrl("https://caldav.example.com/path"))
    }

    @Test
    fun `isRegionalUrl returns false for null`() {
        assertFalse(ICloudUrlNormalizer.isRegionalUrl(null))
    }

    @Test
    fun `isRegionalUrl returns false for similar non-icloud patterns`() {
        // Should not match p180-caldav on other domains
        assertFalse(ICloudUrlNormalizer.isRegionalUrl("https://p180-caldav.notcloud.com/path"))
        assertFalse(ICloudUrlNormalizer.isRegionalUrl("https://p180-caldav.icloud.net/path"))
    }

    // ========== Edge Cases ==========

    @Test
    fun `normalize handles URL with query parameters`() {
        val input = "https://p180-caldav.icloud.com/path?sync-token=abc123"
        val expected = "https://caldav.icloud.com/path?sync-token=abc123"
        assertEquals(expected, ICloudUrlNormalizer.normalize(input))
    }

    @Test
    fun `normalize handles URL with fragment`() {
        val input = "https://p180-caldav.icloud.com/path#section"
        val expected = "https://caldav.icloud.com/path#section"
        assertEquals(expected, ICloudUrlNormalizer.normalize(input))
    }

    @Test
    fun `normalize is idempotent`() {
        val regional = "https://p180-caldav.icloud.com/path"
        val canonical = "https://caldav.icloud.com/path"

        // Normalizing regional gives canonical
        assertEquals(canonical, ICloudUrlNormalizer.normalize(regional))

        // Normalizing canonical again gives same result (idempotent)
        assertEquals(canonical, ICloudUrlNormalizer.normalize(canonical))

        // Normalizing twice gives same result
        assertEquals(canonical, ICloudUrlNormalizer.normalize(ICloudUrlNormalizer.normalize(regional)))
    }

    // ========== Case Sensitivity ==========

    @Test
    fun `normalize handles uppercase P180-CALDAV ICLOUD COM`() {
        val input = "https://P180-CALDAV.ICLOUD.COM/path"
        val expected = "https://caldav.icloud.com/path"
        assertEquals(expected, ICloudUrlNormalizer.normalize(input))
    }

    @Test
    fun `normalize handles mixed case urls`() {
        val input = "https://P180-CaLdAv.IcLoUd.CoM:443/path"
        val expected = "https://caldav.icloud.com/path"
        assertEquals(expected, ICloudUrlNormalizer.normalize(input))
    }

    @Test
    fun `isRegionalUrl matches uppercase regional URLs`() {
        assertTrue(ICloudUrlNormalizer.isRegionalUrl("https://P180-CALDAV.ICLOUD.COM/path"))
    }

    // ========== Additional Edge Cases ==========

    @Test
    fun `normalize handles trailing slash variations`() {
        val withSlash = "https://p180-caldav.icloud.com/"
        val withoutSlash = "https://p180-caldav.icloud.com"

        assertEquals("https://caldav.icloud.com/", ICloudUrlNormalizer.normalize(withSlash))
        assertEquals("https://caldav.icloud.com", ICloudUrlNormalizer.normalize(withoutSlash))
    }

    @Test
    fun `normalize handles empty string`() {
        assertEquals("", ICloudUrlNormalizer.normalize(""))
    }

    @Test
    fun `normalize handles non-443 port`() {
        // Edge case: non-standard port should be preserved
        val input = "https://p180-caldav.icloud.com:8443/path"
        val expected = "https://caldav.icloud.com/path"
        assertEquals(expected, ICloudUrlNormalizer.normalize(input))
    }

    // ========== isICloudUrl ==========

    @Test
    fun `isICloudUrl returns true for regional icloud urls`() {
        assertTrue(ICloudUrlNormalizer.isICloudUrl("https://p180-caldav.icloud.com/path"))
    }

    @Test
    fun `isICloudUrl returns true for canonical icloud urls`() {
        assertTrue(ICloudUrlNormalizer.isICloudUrl("https://caldav.icloud.com/path"))
    }

    @Test
    fun `isICloudUrl returns false for non-icloud urls`() {
        assertFalse(ICloudUrlNormalizer.isICloudUrl("https://caldav.example.com/path"))
    }

    @Test
    fun `isICloudUrl returns false for null`() {
        assertFalse(ICloudUrlNormalizer.isICloudUrl(null))
    }

    @Test
    fun `isICloudUrl is case insensitive`() {
        assertTrue(ICloudUrlNormalizer.isICloudUrl("https://CALDAV.ICLOUD.COM/path"))
    }
}
