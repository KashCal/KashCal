package org.onekash.kashcal.network.dns

/**
 * Decodes the parts of a DNS response message (RFC 1035) that [SrvWireParser] and
 * [TxtRecordParser] share: header and RCODE validation, the compression-aware name reader and
 * the bounds checks. Keeping one copy means a hardening fix can't miss the other parser.
 *
 * The bytes come from an untrusted resolver or server. Every read is bounds-checked,
 * compression pointers must point strictly backward (RFC 1035 §4.1.4), names are capped at
 * 255 octets and labels at 63, and any malformed structure throws [WireFormatException]
 * instead of reading past the end or returning a half-built value. Each parser catches it and
 * returns its typed Failed result.
 *
 * Pure JVM, with no Android APIs, so it is unit- and fuzz-testable off-device.
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
     * Validates the 12-octet header and RCODE, skips the question section, and returns each
     * answer RR's TYPE, rdata offset and RDLENGTH for the caller to type-decode. Throws
     * [WireFormatException] on a short header, any RCODE other than 0 and 3 (SERVFAIL,
     * REFUSED, ...), or malformed structure.
     *
     * RCODE 3 (NXDOMAIN) returns an empty list without parsing the body: the RCODE alone says
     * the name doesn't exist, so a garbled question on such a response must not become a
     * parse failure. A NOERROR response with no answers returns the same empty list, so
     * callers treat both alike. Each RR advances by its declared RDLENGTH, even when a
     * compression pointer inside its rdata chases backward outside the record.
     */
    fun answers(buf: ByteArray): List<AnswerRr> {
        if (buf.size < HEADER_LEN) throw WireFormatException("truncated header")

        when (val rcode = buf[3].toInt() and 0x0F) {
            0 -> {}                                     // NOERROR: inspect the body below
            3 -> return emptyList()                     // NXDOMAIN: authoritative, body untrusted
            else -> throw WireFormatException("RCODE=$rcode")
        }

        val questionCount = u16(buf, 4)
        val answerCount = u16(buf, 6)

        var pos = HEADER_LEN
        // Skip the question section. A QNAME can be compressed, so it goes through the same
        // bounds-checked name reader: a truncated or pointer-only QNAME is an attack class.
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
     * Reads a possibly compressed domain name at [start]. Returns the name (labels joined by
     * '.', empty for the root) and [NameResult.next], the stream position after the name:
     * after the first compression pointer if one is followed, else after the zero terminator.
     *
     * [limit] bounds the bytes the name may occupy before any pointer is followed. Pass an
     * RR's rdata end (`rdataStart + rdlength`) so an embedded name such as an SRV target
     * can't read into the next record; the default, the whole buffer, suits owner and
     * question names. A followed pointer legally chases backward into earlier message bytes
     * (RFC 1035 §4.1.4), so from then on reads are bounded by the buffer.
     */
    fun readName(buf: ByteArray, start: Int, limit: Int = buf.size): NameResult {
        val labels = ArrayList<String>()
        var pos = start
        var next = -1
        var nameLen = 0
        var bound = limit                               // rdata window until a pointer

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
                    // RFC 1035 §3.1 caps the encoded name at 255 octets including the
                    // terminating zero, so the running length plus that terminator must fit.
                    nameLen += lenByte + 1
                    if (nameLen + 1 > MAX_NAME) throw WireFormatException("name too long")
                    labels.add(String(buf, labelStart, lenByte, Charsets.US_ASCII))
                    pos = labelStart + lenByte
                }
                0xC0 -> {                               // compression pointer
                    if (pos + 1 >= bound) throw WireFormatException("truncated pointer")
                    val target = ((lenByte and 0x3F) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                    // Must point strictly backward, which forbids self and forward pointers
                    // and makes a pure pointer chain strictly descend and terminate. It
                    // doesn't forbid every cycle: a label in between advances pos, and a
                    // later pointer can aim back at an offset already seen. The MAX_NAME cap
                    // ends that walk, since each label adds at least 2 to nameLen. Both
                    // guards are needed; don't drop the cap assuming the backward rule
                    // alone prevents loops.
                    if (target >= pos) throw WireFormatException("non-backward pointer")
                    if (next == -1) next = pos + 2
                    pos = target
                    bound = buf.size                    // a backward chase leaves the rdata window
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
