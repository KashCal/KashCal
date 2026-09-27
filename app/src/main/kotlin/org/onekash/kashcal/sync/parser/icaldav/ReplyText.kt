package org.onekash.kashcal.sync.parser.icaldav

/**
 * Text-level edit of one ATTENDEE's PARTSTAT in an iCalendar file.
 *
 * An invitation reply must send the organizer's file back unchanged apart
 * from the attendee's own answer, so the file is not parsed and rebuilt:
 * the physical lines are kept as they are and only the one ATTENDEE
 * property is rewritten and refolded (RFC 5545 §3.1).
 */
internal object ReplyText {

    private const val MAX_LINE_OCTETS = 75

    /** One physical line and the line break that ended it ("" for the last line). */
    private class PhysicalLine(val text: String, val eol: String)

    /**
     * Sets PARTSTAT on the first ATTENDEE of the [veventIndex]-th top-level
     * VEVENT that is this account: its address or its EMAIL parameter
     * satisfies [isSelf]. The EMAIL parameter covers an address that is a
     * principal URL or urn:uuid, as the parser reads it.
     *
     * Only the VEVENT's own properties are considered: ATTENDEE lines of
     * nested components (an EMAIL VALARM, RFC 9073 PARTICIPANT) are never
     * touched. VEVENTs are counted in document order across every VCALENDAR
     * in the file, as the parser lists them.
     *
     * @return the edited file, or null when the file doesn't hold exactly
     *   [veventCount] VEVENTs (the text and the parsed events can't be lined
     *   up) or no matching ATTENDEE is found.
     */
    fun setPartstat(
        ics: String,
        veventIndex: Int,
        veventCount: Int,
        partstat: String,
        isSelf: (String) -> Boolean
    ): String? {
        val lines = physicalLines(ics)
        val stack = ArrayDeque<String>()
        var vevents = 0
        var edit: Pair<IntRange, String>? = null

        var i = 0
        while (i < lines.size) {
            // A logical line is a physical line plus the continuation lines after it.
            var end = i
            while (end + 1 < lines.size && lines[end + 1].text.let { it.startsWith(" ") || it.startsWith("\t") }) end++
            val logical = buildString {
                append(lines[i].text)
                for (k in i + 1..end) append(lines[k].text.substring(1))
            }
            val name = propertyName(logical)
            when {
                name == "BEGIN" -> {
                    val component = logical.substringAfter(':').trim().uppercase()
                    if (component == "VEVENT" && stack.all { it == "VCALENDAR" }) vevents++
                    stack.addLast(component)
                }
                name == "END" -> stack.removeLastOrNull()
                name == "ATTENDEE" && edit == null && stack.lastOrNull() == "VEVENT" &&
                    stack.count { it != "VCALENDAR" } == 1 && vevents - 1 == veventIndex -> {
                    val property = Property.parse(logical)
                    if (property != null && (isSelf(property.value) || property.email?.let(isSelf) == true)) {
                        edit = (i..end) to property.withPartstat(partstat)
                    }
                }
            }
            i = end + 1
        }
        if (vevents != veventCount) return null
        val (range, replacement) = edit ?: return null

        val eol = lines[range.last].eol
        val folded = fold(replacement)
        return buildString {
            for (k in 0 until range.first) append(lines[k].text).append(lines[k].eol)
            folded.forEachIndexed { n, part ->
                append(part)
                append(if (n < folded.lastIndex) eol.ifEmpty { "\r\n" } else eol)
            }
            for (k in range.last + 1 until lines.size) append(lines[k].text).append(lines[k].eol)
        }
    }

    private fun physicalLines(ics: String): List<PhysicalLine> {
        val out = mutableListOf<PhysicalLine>()
        var start = 0
        while (start <= ics.length) {
            val lf = ics.indexOf('\n', start)
            if (lf < 0) {
                if (start < ics.length) out += PhysicalLine(ics.substring(start), "")
                break
            }
            val cr = lf > start && ics[lf - 1] == '\r'
            out += PhysicalLine(ics.substring(start, if (cr) lf - 1 else lf), if (cr) "\r\n" else "\n")
            start = lf + 1
        }
        return out
    }

    /** Returns the upper-case property name: the text before the first ';' or ':', BOM trimmed. */
    private fun propertyName(logical: String): String {
        val cut = logical.indexOfFirst { it == ';' || it == ':' }
        return (if (cut < 0) logical else logical.substring(0, cut)).trimStart('\uFEFF').trim().uppercase()
    }

    /**
     * A property split into its name, parameters (each kept as written) and
     * value. Separators inside a quoted parameter value (`CN="Doe; Jane"`)
     * don't split.
     */
    private class Property(val name: String, val params: List<String>, val value: String) {

        /** The EMAIL parameter's value (RFC 7986 §6.2), unquoted, or null. */
        val email: String?
            get() = params.firstOrNull { it.substringBefore('=').trim().equals("EMAIL", ignoreCase = true) }
                ?.substringAfter('=')?.trim()?.removeSurrounding("\"")

        fun withPartstat(partstat: String): String {
            var replaced = false
            val updated = params.map { param ->
                if (param.substringBefore('=').trim().equals("PARTSTAT", ignoreCase = true)) {
                    replaced = true
                    "${param.substringBefore('=')}=$partstat"
                } else {
                    param
                }
            }
            val all = if (replaced) updated else updated + "PARTSTAT=$partstat"
            return buildString {
                append(name)
                all.forEach { append(';').append(it) }
                append(':').append(value)
            }
        }

        companion object {
            fun parse(logical: String): Property? {
                val parts = mutableListOf<String>()
                var quoted = false
                var from = 0
                for (k in logical.indices) {
                    val c = logical[k]
                    when {
                        c == '"' -> quoted = !quoted
                        !quoted && c == ';' -> {
                            parts += logical.substring(from, k)
                            from = k + 1
                        }
                        !quoted && c == ':' -> {
                            parts += logical.substring(from, k)
                            return Property(parts.first(), parts.drop(1), logical.substring(k + 1))
                        }
                    }
                }
                return null
            }
        }
    }

    /**
     * Fold [logical] into physical lines of at most 75 octets, continuation
     * lines starting with one space, never splitting a character.
     */
    private fun fold(logical: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var octets = 0
        var k = 0
        while (k < logical.length) {
            val cp = logical.codePointAt(k)
            val chars = Character.charCount(cp)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (octets + size > MAX_LINE_OCTETS) {
                out += current.toString()
                current.setLength(0)
                current.append(' ')
                octets = 1
            }
            current.appendCodePoint(cp)
            octets += size
            k += chars
        }
        out += current.toString()
        return out
    }
}
