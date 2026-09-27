package org.onekash.kashcal.network.dns

import org.onekash.kashcal.network.dns.DnsWire.WireFormatException

/**
 * Decodes a DNS TXT response (RFC 1035 message format, §3.3.14 TXT rdata) into a
 * [TxtParseResult], and extracts the RFC 6763 §6.4 `path` attribute that RFC 6764 §4 uses as
 * the DAV context path.
 *
 * The TXT companion to [SrvWireParser]: after a successful SRV lookup, RFC 6764 §6 step 3 has
 * the client query the same name for TXT and honour a `path=` key over the `.well-known`
 * default. It shares [DnsWire]'s message framing, RCODE handling, bounds checks and name
 * reader, adding only the TXT rdata shape (packed character-strings) and the `path` extraction.
 *
 * TXT rdata contains no domain names, so no compression pointers; only the RR owner names
 * (question and answer) do. Pure JVM (no Android APIs), so it is unit- and fuzz-testable
 * off-device.
 */
object TxtRecordParser {

    private const val TYPE_TXT = 16
    private const val PATH_KEY = "path"

    fun parse(response: ByteArray): TxtParseResult =
        try {
            decode(response)
        } catch (e: WireFormatException) {
            TxtParseResult.Failed(e.reason)
        }

    /**
     * Returns the RFC 6763 §6.4 `path` value from TXT character-strings, or null if absent.
     *
     * The key match is case-insensitive and the first occurrence wins; later duplicates are
     * silently ignored (§6.4). A bare `path` with no '=' is a boolean attribute with no value:
     * it yields null, and as the first occurrence it hides any later `path=`. A present but
     * empty `path=` yields "".
     */
    fun pathValue(strings: List<String>): String? {
        for (s in strings) {
            val eq = s.indexOf('=')
            val key = if (eq == -1) s else s.take(eq)
            if (key.equals(PATH_KEY, ignoreCase = true)) {
                return if (eq == -1) null else s.drop(eq + 1)
            }
        }
        return null
    }

    private fun decode(buf: ByteArray): TxtParseResult {
        val strings = ArrayList<String>()
        for (rr in DnsWire.answers(buf)) {
            if (rr.type == TYPE_TXT) {
                readCharacterStrings(buf, rr.rdataStart, rr.rdlength, strings)
            }
        }
        return if (strings.isEmpty()) TxtParseResult.NoRecords else TxtParseResult.Records(strings)
    }

    /**
     * Appends each character-string packed in one TXT RR's rdata to [out].
     *
     * RFC 1035 §3.3.14: one or more <character-string>s, each a length octet then that many
     * bytes. A length running past the RR's declared rdlength throws [WireFormatException]. A
     * single zero-length character-string is kept as ""; an empty rdata (rdlength 0) adds
     * nothing, matching RFC 6763 §6.1's "empty TXT == no record" reading.
     *
     * Decoded as UTF-8: RFC 6763 §6.5 permits any eight-bit bytes in TXT values, so unlike the RR
     * owner names (hostnames, read as ASCII) a value byte >= 0x80 must survive. ASCII values
     * such as `path=/dav/` decode the same either way.
     */
    private fun readCharacterStrings(buf: ByteArray, start: Int, rdlength: Int, out: MutableList<String>) {
        val end = start + rdlength
        var i = start
        while (i < end) {
            val len = buf[i].toInt() and 0xFF
            val strStart = i + 1
            if (strStart + len > end) throw WireFormatException("character-string past rdata")
            out.add(String(buf, strStart, len, Charsets.UTF_8))
            i = strStart + len
        }
    }
}
