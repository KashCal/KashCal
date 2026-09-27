package org.onekash.kashcal.util

/**
 * Returns true when a server URL provably points at the local network.
 *
 * Android 17 (targetSdk 37) blocks local-network socket traffic, OkHttp connections included,
 * unless the app holds the ACCESS_LOCAL_NETWORK runtime permission. The CalDAV sign-in sheet
 * and the add-subscription dialog use this to show the permission banner up front for a LAN
 * address only, never for a public-internet server.
 *
 * The decision uses the URL string alone (no DNS): a literal private, CGNAT, link-local,
 * loopback, unique-local or IPv4-mapped address, or an mDNS `.local` name. A bare hostname or
 * a custom domain that resolves to a private IP can't be proven LAN here and returns false;
 * the reactive connection-failure hint covers that case.
 */
fun isLanHost(url: String): Boolean {
    val host = extractHost(url)?.lowercase() ?: return false
    if (host.isEmpty()) return false

    // mDNS names.
    if (host == "local" || host.endsWith(".local")) return true

    // IPv6 literal (host came out of [...] brackets, may carry a %zone id).
    if (host.contains(':')) {
        val addr = host.substringBefore('%')
        return isLanIpv6(addr)
    }

    // IPv4 literal, only when it parses as four octets.
    val v4 = parseIpv4(host)
    if (v4 != null) return isLanIpv4(v4)

    // Anything else (bare hostname, public domain) is not provably LAN.
    return false
}

/** Returns the host of [url], which may lack a scheme; an IPv6 literal loses its brackets. */
private fun extractHost(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null

    // Strip scheme.
    var rest = trimmed.substringAfter("://", trimmed)

    // Strip any userinfo (user:pass@host).
    val at = rest.indexOf('@')
    if (at >= 0) rest = rest.substring(at + 1)

    // Bracketed IPv6: [addr] or [addr%zone]:port/path.
    if (rest.startsWith("[")) {
        val close = rest.indexOf(']')
        if (close < 0) return null
        return rest.substring(1, close)
    }

    // Cut at port / path / query.
    rest = rest.substringBefore('/').substringBefore('?').substringBefore(':')
    return rest.ifEmpty { null }
}

private fun parseIpv4(host: String): IntArray? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val octets = IntArray(4)
    for (i in 0 until 4) {
        val n = parts[i].toIntOrNull() ?: return null
        if (n !in 0..255) return null
        octets[i] = n
    }
    return octets
}

private fun isLanIpv4(o: IntArray): Boolean = when {
    o[0] == 10 -> true                                  // 10.0.0.0/8
    o[0] == 127 -> true                                 // 127.0.0.0/8 loopback
    o[0] == 172 && o[1] in 16..31 -> true               // 172.16.0.0/12
    o[0] == 192 && o[1] == 168 -> true                  // 192.168.0.0/16
    o[0] == 169 && o[1] == 254 -> true                  // 169.254.0.0/16 link-local
    o[0] == 100 && o[1] in 64..127 -> true              // 100.64.0.0/10 CGNAT (e.g. Tailscale)
    else -> false
}

private fun isLanIpv6(addr: String): Boolean {
    if (addr == "::1") return true                      // loopback

    // IPv4-mapped (::ffff:a.b.c.d): classify the embedded IPv4 address.
    val mapped = addr.substringAfterLast(':')
    if (mapped.contains('.')) {
        parseIpv4(mapped)?.let { return isLanIpv4(it) }
    }

    val head = addr.substringBefore("::").substringBefore(':')
    val hextet = head.toIntOrNull(16) ?: return false
    return when {
        hextet and 0xffc0 == 0xfe80 -> true             // fe80::/10 link-local (top 10 bits)
        hextet and 0xfe00 == 0xfc00 -> true             // fc00::/7 unique-local
        else -> false
    }
}
