package org.onekash.kashcal.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks [wellKnownEndpoint]: a well-known discovery result is stored without its query and
 * fragment, keeps its path, and never loses https.
 */
class WellKnownEndpointTest {

    @Test
    fun `an https result stays https when the user typed https`() =
        assertEquals("https://dav.example.test/dav.php/", wellKnownEndpoint("https://dav.example.test/dav.php/?x=1#f", "https"))

    @Test
    fun `an https result stays https when the user typed http (the server upgraded)`() =
        assertEquals("https://dav.example.test/remote.php/dav/", wellKnownEndpoint("https://dav.example.test/remote.php/dav/", "http"))

    @Test
    fun `an http result is lifted to https when the user started on https (TLS-terminating proxy)`() =
        assertEquals("https://dav.example.test/dav.php", wellKnownEndpoint("http://dav.example.test/dav.php", "https"))

    @Test
    fun `an http result stays http when the user set up an http account`() =
        assertEquals("http://nas.local:5232/", wellKnownEndpoint("http://nas.local:5232/", "http"))

    @Test
    fun `the path and trailing slash are kept`() =
        assertEquals("https://dav.example.test/caldav/user/", wellKnownEndpoint("https://dav.example.test/caldav/user/", null))
}
