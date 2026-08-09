package org.onekash.kashcal.sync.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [CaldavUrlNormalizer], the comparison-only URL canonicalizer that lets
 * href->local-row matching survive servers (e.g. Radicale) that percent-encode
 * pchar-legal reserved characters like '@' as '%40' in echoed hrefs.
 */
class CaldavUrlNormalizerTest {

    @Test
    fun `literal at-sign and percent-40 canonicalize to the same string`() {
        val literal = "https://s.example/cal/uuid@kashcal.onekash.org.ics"
        val encoded = "https://s.example/cal/uuid%40kashcal.onekash.org.ics"
        assertEquals(
            CaldavUrlNormalizer.canonicalize(literal),
            CaldavUrlNormalizer.canonicalize(encoded)
        )
    }

    @Test
    fun `percent-encoding hex is case-insensitive`() {
        // %2C and %2c both decode to ',' (a decodable pchar sub-delim), so both
        // forms must canonicalize to the same literal comma.
        val upper = "https://s.example/cal/a%2Cb.ics"
        val lower = "https://s.example/cal/a%2cb.ics"
        assertEquals("https://s.example/cal/a,b.ics", CaldavUrlNormalizer.canonicalize(upper))
        assertEquals(
            CaldavUrlNormalizer.canonicalize(upper),
            CaldavUrlNormalizer.canonicalize(lower)
        )
    }

    @Test
    fun `other pchar-legal reserved octets are decoded`() {
        // sub-delims + ':' are all legal unencoded in a path segment (RFC 3986 pchar)
        val encoded = "https://s.example/cal/a%2Cb%3Bc%3Dd%26e%3Af.ics"
        val literal = "https://s.example/cal/a,b;c=d&e:f.ics"
        assertEquals(
            CaldavUrlNormalizer.canonicalize(literal),
            CaldavUrlNormalizer.canonicalize(encoded)
        )
    }

    @Test
    fun `encoded slash is NOT decoded (would change path structure)`() {
        // %2F must stay encoded — decoding it crosses a segment boundary and could
        // merge two structurally distinct resources.
        val encoded = "https://s.example/cal/a%2Fb.ics"
        val decoded = "https://s.example/cal/a/b.ics"
        val ce = CaldavUrlNormalizer.canonicalize(encoded)
        val cd = CaldavUrlNormalizer.canonicalize(decoded)
        // They must remain distinct.
        assertEquals("https://s.example/cal/a%2Fb.ics", ce)
        assertEquals(decoded, cd)
    }

    @Test
    fun `non-pchar octets like percent-20 are left untouched`() {
        val encoded = "https://s.example/cal/a%20b.ics"
        // Space is not a pchar; leaving it encoded is safe (and it never appears in
        // a KashCal-generated filename anyway).
        assertEquals(encoded, CaldavUrlNormalizer.canonicalize(encoded))
    }

    @Test
    fun `non-encoded url is returned unchanged`() {
        val url = "https://s.example/cal/plain-event.ics"
        assertEquals(url, CaldavUrlNormalizer.canonicalize(url))
    }

    @Test
    fun `canonicalize is idempotent`() {
        val url = "https://s.example/cal/uuid%40kashcal.onekash.org.ics"
        val once = CaldavUrlNormalizer.canonicalize(url)
        val twice = CaldavUrlNormalizer.canonicalize(once)
        assertEquals(once, twice)
    }

    @Test
    fun `null and empty pass through`() {
        assertNull(CaldavUrlNormalizer.canonicalize(null))
        assertEquals("", CaldavUrlNormalizer.canonicalize(""))
    }

    @Test
    fun `synthetic birthday url passes through unchanged`() {
        val url = "contact_birthday:12345"
        assertEquals(url, CaldavUrlNormalizer.canonicalize(url))
    }

    @Test
    fun `synthetic anniversary url passes through unchanged`() {
        val url = "contact_anniversary:12345"
        assertEquals(url, CaldavUrlNormalizer.canonicalize(url))
    }

    @Test
    fun `synthetic subscription url passes through unchanged`() {
        val url = "ics_subscription:7:some-event-id"
        assertEquals(url, CaldavUrlNormalizer.canonicalize(url))
    }

    @Test
    fun `malformed trailing percent does not throw`() {
        val url = "https://s.example/cal/weird%.ics"
        // Incomplete escape: leave as-is rather than crash.
        assertEquals(url, CaldavUrlNormalizer.canonicalize(url))
    }

    @Test
    fun `malformed non-hex percent does not throw`() {
        val url = "https://s.example/cal/weird%zz.ics"
        assertEquals(url, CaldavUrlNormalizer.canonicalize(url))
    }
}
