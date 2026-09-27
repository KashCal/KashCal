package org.onekash.kashcal.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [isLanHost], which decides from the URL string alone whether a server is on the local
 * network (rule and callers on the function).
 *
 * The bare-hostname test pins an intended limit: a bare hostname can't be proven LAN from the
 * string, so it classifies false and the reactive connection-failure hint covers it at runtime.
 */
class LocalNetworkAddressTest {

    // ===== Private IPv4 ranges (RFC 1918) =====

    @Test fun `192_168 is LAN`() = assertTrue(isLanHost("https://192.168.1.10/dav"))
    @Test fun `10 slash 8 is LAN`() = assertTrue(isLanHost("http://10.0.0.5:8443"))
    @Test fun `172_16 is LAN`() = assertTrue(isLanHost("https://172.16.4.4"))
    @Test fun `172_31 is LAN`() = assertTrue(isLanHost("https://172.31.255.1"))
    @Test fun `172_32 is NOT LAN (above private block)`() = assertFalse(isLanHost("https://172.32.0.1"))
    @Test fun `172_15 is NOT LAN (below private block)`() = assertFalse(isLanHost("https://172.15.0.1"))

    // ===== Link-local, loopback, CGNAT =====

    @Test fun `169_254 link-local is LAN`() = assertTrue(isLanHost("http://169.254.10.1"))
    @Test fun `127 loopback is LAN`() = assertTrue(isLanHost("http://127.0.0.1:8080/caldav"))
    @Test fun `CGNAT 100_64 is LAN (Tailscale home NAS)`() = assertTrue(isLanHost("https://100.64.1.1"))
    @Test fun `100_128 is NOT LAN (above CGNAT block)`() = assertFalse(isLanHost("https://100.128.0.1"))

    // ===== IPv6 =====

    @Test fun `ipv6 loopback is LAN`() = assertTrue(isLanHost("http://[::1]:8443/dav"))
    @Test fun `ipv6 link-local fe80 is LAN`() = assertTrue(isLanHost("https://[fe80::1]"))
    @Test fun `ipv6 link-local with zone id is LAN`() = assertTrue(isLanHost("https://[fe80::1%eth0]/dav"))
    @Test fun `ipv6 link-local upper range febf is LAN`() = assertTrue(isLanHost("https://[febf::1]"))
    @Test fun `ipv6 fec0 is NOT link-local (above slash 10)`() = assertFalse(isLanHost("https://[fec0::1]"))
    @Test fun `ipv6 ULA fc00 is LAN`() = assertTrue(isLanHost("https://[fc00::1]"))
    @Test fun `ipv6 ULA fd00 is LAN`() = assertTrue(isLanHost("https://[fd12:3456::1]"))
    @Test fun `ipv4-mapped ipv6 of private v4 is LAN`() = assertTrue(isLanHost("https://[::ffff:192.168.1.1]"))
    @Test fun `ipv4-mapped ipv6 of public v4 is NOT LAN`() = assertFalse(isLanHost("https://[::ffff:8.8.8.8]"))
    @Test fun `public ipv6 is NOT LAN`() = assertFalse(isLanHost("https://[2606:4700:4700::1111]"))

    // ===== .local mDNS names =====

    @Test fun `dot-local hostname is LAN`() = assertTrue(isLanHost("https://nas.local/dav"))
    @Test fun `dot-local with port is LAN`() = assertTrue(isLanHost("https://raspberrypi.local:5232"))

    // ===== Public hosts are never LAN =====

    @Test fun `icloud is NOT LAN`() = assertFalse(isLanHost("https://caldav.icloud.com"))
    @Test fun `nextcloud example is NOT LAN`() = assertFalse(isLanHost("https://nextcloud.example.com/remote.php/dav"))
    @Test fun `public dns ip is NOT LAN`() = assertFalse(isLanHost("https://8.8.8.8"))

    // ===== Documented gap: bare hostname can't be proven LAN by string =====

    @Test fun `bare hostname is NOT classified LAN (documented gap, reactive net covers it)`() {
        assertFalse(isLanHost("http://nas"))
        assertFalse(isLanHost("https://raspberrypi/dav"))
    }

    // ===== Parsing edge cases (no scheme, blank, garbage) must not throw =====

    @Test fun `no scheme still classifies host`() = assertTrue(isLanHost("192.168.0.1/dav"))
    @Test fun `blank input is NOT LAN`() = assertFalse(isLanHost("   "))
    @Test fun `garbage input is NOT LAN and does not throw`() = assertFalse(isLanHost("ht!tp://::::"))
}
