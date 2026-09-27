package org.onekash.kashcal.sync.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests [EtagUtils.normalizeEtag] (RFC 7232 etags): null and blank input, plain, quoted and weak
 * (`W/`) etags, `&quot;` entity quotes, whitespace trimming, a single character and empty quotes.
 */
class EtagUtilsTest {

    @Test
    fun `normalizeEtag - null input returns null`() {
        assertNull(EtagUtils.normalizeEtag(null))
    }

    @Test
    fun `normalizeEtag - blank input returns null`() {
        assertNull(EtagUtils.normalizeEtag(""))
        assertNull(EtagUtils.normalizeEtag("   "))
    }

    @Test
    fun `normalizeEtag - plain etag unchanged`() {
        assertEquals("abc123", EtagUtils.normalizeEtag("abc123"))
    }

    @Test
    fun `normalizeEtag - quoted etag strips quotes`() {
        assertEquals("abc123", EtagUtils.normalizeEtag("\"abc123\""))
    }

    @Test
    fun `normalizeEtag - weak etag strips W prefix and quotes`() {
        assertEquals("abc123", EtagUtils.normalizeEtag("W/\"abc123\""))
    }

    @Test
    fun `normalizeEtag - XML entity quotes decoded and stripped`() {
        assertEquals("abc123", EtagUtils.normalizeEtag("&quot;abc123&quot;"))
    }

    @Test
    fun `normalizeEtag - W prefix with XML entity quotes`() {
        assertEquals("abc", EtagUtils.normalizeEtag("W/&quot;abc&quot;"))
    }

    @Test
    fun `normalizeEtag - whitespace trimmed`() {
        assertEquals("abc", EtagUtils.normalizeEtag("  \"abc\"  "))
    }

    @Test
    fun `normalizeEtag - single character`() {
        assertEquals("a", EtagUtils.normalizeEtag("\"a\""))
    }

    @Test
    fun `normalizeEtag - empty quotes returns null`() {
        assertNull(EtagUtils.normalizeEtag("\"\""))
    }
}
