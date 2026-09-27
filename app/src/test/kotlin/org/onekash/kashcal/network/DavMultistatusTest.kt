package org.onekash.kashcal.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks [DavMultistatus]: only a well-formed XML body with a `{DAV:}multistatus` root, in a 207
 * reply, is a DAV answer, and a 507 or number-of-matches-within-limits marks it truncated.
 */
class DavMultistatusTest {

    private val listing = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:"><d:response><d:href>/cal/a.ics</d:href><d:propstat><d:prop><d:getetag>"1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""

    @Test
    fun `a prefixed multistatus is accepted`() = assertNull(DavMultistatus.check(listing))

    @Test
    fun `a default-namespace multistatus is accepted`() =
        assertNull(DavMultistatus.check("""<multistatus xmlns="DAV:"><response><href>/x</href></response></multistatus>"""))

    @Test
    fun `an empty multistatus is accepted (an empty calendar)`() =
        assertNull(DavMultistatus.check("""<D:multistatus xmlns:D="DAV:"/>"""))

    @Test
    fun `a byte-order mark or whitespace before the declaration is accepted`() {
        assertNull(DavMultistatus.check("\uFEFF" + listing))
        assertNull(DavMultistatus.check("\r\n   " + listing))
    }

    @Test
    fun `a hotspot login page is rejected`() {
        val problem = DavMultistatus.check("<!DOCTYPE html><html><head><title>Sign in</title></head><body><form></form></body></html>")
        assertNotNull(problem)
    }

    @Test
    fun `an XHTML page is rejected even though it is well-formed XML`() {
        val problem = DavMultistatus.check("""<html xmlns="http://www.w3.org/1999/xhtml"><body/></html>""")
        assertTrue(problem!!, problem.contains("html"))
    }

    @Test
    fun `a multistatus in the wrong namespace is rejected`() =
        assertNotNull(DavMultistatus.check("""<multistatus xmlns="urn:not-dav"><response/></multistatus>"""))

    @Test
    fun `a reply cut off half way is rejected`() =
        assertNotNull(DavMultistatus.check(listing.substring(0, listing.length - 40)))

    @Test
    fun `a reply cut off between two tags is rejected`() =
        assertNotNull(DavMultistatus.check("""<d:multistatus xmlns:d="DAV:"><d:response><d:href>/cal/a.ics"""))

    @Test
    fun `an empty body and plain text are rejected`() {
        assertNotNull(DavMultistatus.check(""))
        assertNotNull(DavMultistatus.check("   "))
        assertNotNull(DavMultistatus.check("Server storage limit exceeded"))
    }

    @Test
    fun `a listing without a 507 is not truncated`() = assertFalse(DavMultistatus.read(listing).truncated)

    @Test
    fun `a 507 status for the request-URI marks the listing truncated`() {
        val body = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/cal/</d:href><d:status>HTTP/1.1 507 Insufficient Storage</d:status></d:response></d:multistatus>"""
        assertTrue(DavMultistatus.read(body).truncated)
    }

    @Test
    fun `a number-of-matches-within-limits error marks the listing truncated`() {
        val body = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/cal/</d:href><d:status>HTTP/1.1 200 OK</d:status><d:error><d:number-of-matches-within-limits/></d:error></d:response></d:multistatus>"""
        assertTrue(DavMultistatus.read(body).truncated)
    }

    @Test
    fun `a 5070 byte count in an etag is not a 507`() {
        val body = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/a</d:href><d:propstat><d:prop><d:getetag>"HTTP/1.1 5070"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        assertFalse(DavMultistatus.read(body).truncated)
    }

    @Test
    fun `a problem names the root it found`() =
        assertEquals("root element is {}html, not {DAV:}multistatus", DavMultistatus.check("<html><body/></html>"))

    @Test
    fun `a reply that is not a 207 is not a multistatus even with a valid body`() {
        val reading = DavMultistatus.readReply(200, "application/xml", listing)
        assertEquals("status 200, not 207", reading.problem)
    }

    @Test
    fun `a valid 207 under a non-XML content type is still read`() =
        assertNull(DavMultistatus.readReply(207, "text/plain", listing).problem)
}
