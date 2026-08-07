package org.onekash.kashcal.network.dns

/**
 * Shared, defensive primitives for decoding a DNS response message (RFC 1035
 * message format). Used by both [SrvWireParser] and [TxtRecordParser] so the
 * security-critical parts — header/RCODE validation, the compression-aware name
 * reader, and every bounds check — are defined and tested exactly once rather
 * than duplicated per record type (a hardening fix to one copy would otherwise
 * silently miss the other).
 *
 * The bytes come from an untrusted resolver/server, so decoding is defensive
 * throughout: every read is bounds-checked, compression pointers must point
 * strictly backward (RFC 1035 §4.1.4 — forbids loops and forward/self references
 * without tracking visited offsets), names are capped at 255 octets and labels
 * at 63, and any malformed structure throws [WireFormatException] rather than
 * reading past the end or returning a half-built value. Each parser wraps the
 * walk in its own try/catch and maps the exception to its typed Failed result.
 *
 * Pure JVM logic (no Android APIs) so it is unit- and fuzz-testable off-device.
 */
internal object DnsWire {

    const val HEADER_LEN = 12
    private const val MAX_NAME = 255

    /** Thrown for any untrustworthy structure; [reason] is a short diagnostic. */
    class WireFormatException(val reason: String) : Exception(reason)

    /** An assembled name plus the offset of the field that follows it in the stream. */
    class NameResult(val name: String, val next: Int)

    /** One answer resource record, located but not yet type-decoded. */
    class AnswerRr(val type: Int, val rdataStart: Int, val rdlength: Int)

    /**
     * Validates the 12-octet header and RCODE, skips the question section, then
     * returns every answer RR located (TYPE + rdata offset + RDLENGTH) for the
     * caller to type-decode. Returns an empty list without touching the body on
     * RCODE 3 (NXDOMAIN); throws [WireFormatException] on a short header, a
     * server-failure RCODE (SERVFAIL, REFUSED, ...), or any malformed structure.
     *
     * NXDOMAIN is authoritative: the RCODE alone says "the name does not exist",
     * so the body is neither trusted nor parsed — a garbled or truncated question
     * on such a response must not flip "nothing to honour" into a parse failure.
     * A NOERROR response with no answers yields the same empty list, so callers
     * treat both identically and no RCODE is surfaced. Each RR is accounted by its
     * declared RDLENGTH (a compression pointer inside the rdata may chase backward
     * outside the record's window, but the record still occupies exactly RDLENGTH
     * octets in the stream).
     */
    fun answers(buf: ByteArray): List<AnswerRr> {
        if (buf.size < HEADER_LEN) throw WireFormatException("truncated header")

        when (val rcode = buf[3].toInt() and 0x0F) {
            0 -> {}                                     // NOERROR — inspect the body below
            3 -> return emptyList()                     // NXDOMAIN — authoritative, body untrusted
            else -> throw WireFormatException("RCODE=$rcode")
        }

        val questionCount = u16(buf, 4)
        val answerCount = u16(buf, 6)

        var pos = HEADER_LEN
        // Skip the question section. A QNAME can itself be compressed, so it must
        // go through the same bounds-checked name reader — a truncated or
        // pointer-only QNAME is a real attack class.
        repeat(questionCount) {
            pos = readName(buf, pos).next
            pos = advance(buf, pos, 4)                  // QTYPE(2) + QCLASS(2)
        }

        val rrs = ArrayList<AnswerRr>(answerCount)
        repeat(answerCount) {
            pos = readName(buf, pos).next               // owner NAME
            // TYPE(2) CLASS(2) TTL(4) RDLENGTH(2)
            if (pos + 10 > buf.size) throw WireFormatException("truncated RR header")
            val type = u16(buf, pos)
            val rdlength = u16(buf, pos + 8)
            val rdataStart = pos + 10
            if (rdataStart + rdlength > buf.size) throw WireFormatException("rdlength past buffer")

            rrs.add(AnswerRr(type, rdataStart, rdlength))

            pos = rdataStart + rdlength
        }

        return rrs
    }

    /**
     * Reads a (possibly compressed) domain name starting at [start]. Returns the
     * decoded name (labels joined by '.', empty for the root) and [NameResult.next]:
     * the position in the RR stream immediately after the name — after the first
     * compression pointer if one is followed, otherwise after the zero terminator.
     *
     * [limit] bounds the physical bytes this name may occupy *before* any
     * compression pointer is followed — pass an RR's rdata end
     * (`rdataStart + rdlength`) to enforce that an embedded name (e.g. an SRV
     * target) stays inside the record it belongs to rather than reading into the
     * next record's bytes; it defaults to the whole buffer for owner/question
     * names, which are bounded only by the message. Once a pointer is followed the
     * name legally chases *backward* into earlier message bytes (RFC 1035 §4.1.4),
     * outside the record window, so from that point reads are bounded by the buffer.
     */
    fun readName(buf: ByteArray, start: Int, limit: Int = buf.size): NameResult {
        val labels = ArrayList<String>()
        var pos = start
        var next = -1
        var nameLen = 0
        var bound = limit                               // rdata window until the first pointer chase

        while (true) {
            if (pos >= bound) throw WireFormatException("name past buffer")
            val lenByte = buf[pos].toInt() and 0xFF
            when (lenByte and 0xC0) {
                0x00 -> {
                    if (lenByte == 0) {                 // root / end of name
                        if (next == -1) next = pos + 1
                        break
                    }
                    val labelStart = pos + 1
                    if (labelStart + lenByte > bound) throw WireFormatException("label past buffer")
                    // RFC 1035 §3.1 caps the total encoded name at 255 octets
                    // INCLUDING the terminating zero, so the running content length
                    // plus that mandatory terminator must not exceed the cap.
                    nameLen += lenByte + 1
                    if (nameLen + 1 > MAX_NAME) throw WireFormatException("name too long")
                    labels.add(String(buf, labelStart, lenByte, Charsets.US_ASCII))
                    pos = labelStart + lenByte
                }
                0xC0 -> {                               // compression pointer
                    if (pos + 1 >= bound) throw WireFormatException("truncated pointer")
                    val target = ((lenByte and 0x3F) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                    // Must point strictly backward. This forbids a self- or forward
                    // pointer and makes any pointer->pointer chain strictly descend,
                    // so a pure pointer chain always terminates. It does NOT by
                    // itself forbid every cycle: an interspersed label advances pos,
                    // after which a later pointer can legally aim back at an
                    // already-seen offset and oscillate. Termination in that case is
                    // guaranteed by the MAX_NAME cap below — every label read adds
                    // >=2 to nameLen, so the walk trips "name too long" in a bounded
                    // number of steps. Both guards are load-bearing; do not drop the
                    // cap on the assumption the backward rule alone prevents loops.
                    if (target >= pos) throw WireFormatException("non-backward pointer")
                    if (next == -1) next = pos + 2
                    pos = target
                    bound = buf.size                    // backward chase legally leaves the rdata window
                }
                else -> throw WireFormatException("reserved label type")  // 0x40 / 0x80
            }
        }

        return NameResult(labels.joinToString("."), next)
    }

    fun advance(buf: ByteArray, pos: Int, n: Int): Int {
        if (pos + n > buf.size) throw WireFormatException("truncated section")
        return pos + n
    }

    fun u16(buf: ByteArray, i: Int): Int =
        ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
}
