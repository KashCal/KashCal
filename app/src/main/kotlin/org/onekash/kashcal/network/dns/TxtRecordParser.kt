package org.onekash.kashcal.network.dns

import org.onekash.kashcal.network.dns.DnsWire.WireFormatException

/**
 * Decodes a DNS TXT response (RFC 1035 message format, §3.3.14 TXT rdata) into a
 * typed [TxtParseResult], and extracts the RFC 6763 §6.4 `path` attribute used by
 * RFC 6764 §4 as the DAV context path.
 *
 * This is the TXT companion to [SrvWireParser]: after a successful SRV lookup,
 * RFC 6764 §6 step 3 requires the client to also query the same name for a TXT
 * record and honour a `path=` key over the `.well-known` default. It shares
 * [DnsWire]'s message framing, RCODE handling, bounds checks, and name reader,
 * adding only the TXT-specific rdata shape (packed character-strings) and the
 * `path` extraction.
 *
 * TXT rdata itself contains no domain names, so it carries no compression
 * pointers; only the RR owner names (question + answer) do. Pure JVM logic (no
 * Android APIs) so it is unit- and fuzz-testable off-device.
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
     * The RFC 6763 §6.4 `path` value from a set of TXT character-strings, or null
     * if absent. Key match is case-insensitive; the first occurrence of the key
     * wins (later duplicates are silently ignored, §6.4), and a bare `path` with
     * no '=' is a boolean attribute carrying no value (so it yields null even
     * though it counts as the first — and thus only considered — occurrence).
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
     * Reads the character-strings packed in one TXT RR's rdata (RFC 1035 §3.3.14:
     * one or more <character-string>s, each a length octet then that many bytes),
     * appending each to [out]. A length running past the RR's declared rdlength is
     * malformed. A single empty character-string (length 0) is a legitimate empty
     * string and is preserved; an entirely empty rdata (rdlength 0) contributes
     * nothing, matching RFC 6763 §6.1's "empty TXT == no record" reading.
     *
     * Decoded as UTF-8: RFC 6763 §6.4 permits non-ASCII bytes in TXT values, so
     * (unlike the RR owner names, which are hostnames) a value byte >= 0x80 must be
     * preserved rather than mangled. UTF-8 is a superset of ASCII, so the common
     * `path=/dav/`-style values decode identically.
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
