package org.onekash.kashcal.data.ics

/**
 * Builds the stored copy of each UID group of an imported .ics file: the text the group's series
 * keeps as its stored ICS ([org.onekash.kashcal.data.db.EventIcsSource]), as the pull keeps a
 * server's resource.
 *
 * A copy is the file's VCALENDAR reduced to one UID: its calendar-level lines except METHOD (a
 * stored resource carries none, RFC 4791 §4.1), the VTIMEZONEs its VEVENTs name, and that UID's
 * VEVENTs exactly as written, folding included, in file order even when the file interleaves
 * other UIDs. The text is never unescaped or rebuilt, so properties the app doesn't model keep
 * their value data (RFC 5545 §3.2.20).
 */
internal object ImportedCopy {

    /**
     * Returns each UID's copy, keyed by the VEVENT's UID value. A VEVENT without a UID line is left
     * out (the parser invents a different UID on every parse, so no copy could match it). With
     * [dropAlarms] VALARM blocks are removed: an invitation's alarms are its sender's.
     */
    fun build(content: String, dropAlarms: Boolean): Map<String, String> {
        val units = logicalLines(content.removePrefix("\uFEFF"))
        // A file can hold several VCALENDARs; each is reduced on its own, and a later one wins
        // for a UID both hold. The parser reads one of them; the caller keeps a copy only when
        // it matches what the parser read.
        val calendars = mutableListOf<List<ContentLine>>()
        var start = -1
        units.forEachIndexed { index, unit ->
            if (unit.beginOf() == "VCALENDAR" && start < 0) start = index
            if (unit.endOf() == "VCALENDAR" && start >= 0) {
                calendars += units.subList(start, index + 1)
                start = -1
            }
        }
        if (start >= 0) calendars += units.subList(start, units.size)
        return calendars.fold(emptyMap()) { copies, calendar -> copies + buildOne(calendar, dropAlarms) }
    }

    /** Returns [build]'s copies for one VCALENDAR's lines. */
    private fun buildOne(units: List<ContentLine>, dropAlarms: Boolean): Map<String, String> {
        val header = mutableListOf<ContentLine>()
        val zones = linkedMapOf<String, List<ContentLine>>()
        val veventsByUid = linkedMapOf<String, MutableList<List<ContentLine>>>()
        val referencedZones = mutableMapOf<String, MutableSet<String>>()
        var footer: ContentLine? = null

        var i = 0
        while (i < units.size) {
            val unit = units[i]
            val begin = unit.beginOf()
            when {
                begin == "VCALENDAR" -> header += unit
                unit.endOf() == "VCALENDAR" -> footer = unit
                begin != null -> {
                    val end = componentEnd(units, i, begin)
                    val block = units.subList(i, end + 1)
                    when (begin) {
                        "VEVENT" -> {
                            val uid = uidOf(block)
                            if (uid != null) {
                                veventsByUid.getOrPut(uid) { mutableListOf() } +=
                                    if (dropAlarms) withoutAlarms(block) else block
                                referencedZones.getOrPut(uid) { mutableSetOf() } += block.flatMap { it.tzids() }
                            }
                        }
                        "VTIMEZONE" -> block.firstOrNull { it.name() == "TZID" }?.value()?.trim()
                            ?.let { zones[it] = block }
                    }
                    i = end
                }
                header.isNotEmpty() && footer == null && unit.name() != "METHOD" -> header += unit
            }
            i++
        }
        if (header.isEmpty()) return emptyMap()
        val closing = footer?.text ?: "END:VCALENDAR"
        return veventsByUid.mapValues { (uid, blocks) ->
            val zoneBlocks = referencedZones[uid].orEmpty().mapNotNull { zones[it] }
            (header.map { it.text } + zoneBlocks.flatten().map { it.text } +
                blocks.flatten().map { it.text } + closing)
                .joinToString("\r\n", postfix = "\r\n")
        }
    }

    /**
     * Returns [copy] with the UID property of each of its VEVENTs set to [newUid] where it equals
     * [oldUid]. A VALARM's UID (RFC 9074) and RELATED-TO keep their values.
     */
    fun withUid(copy: String, oldUid: String, newUid: String): String {
        val units = logicalLines(copy)
        var depthInEvent = 0
        val out = units.map { unit ->
            when {
                unit.beginOf() == "VEVENT" -> depthInEvent = 1
                depthInEvent > 0 && unit.beginOf() != null -> depthInEvent++
                depthInEvent > 0 && unit.endOf() != null -> depthInEvent--
            }
            if (depthInEvent == 1 && unit.name() == "UID" && unit.value().trim() == oldUid) {
                unit.withValue(newUid)
            } else {
                unit.text
            }
        }
        return out.joinToString("\r\n", postfix = "\r\n")
    }

    /**
     * One content line as written ([text], folded lines kept) and unfolded ([unfolded]), with
     * its name, value and component boundary read once.
     */
    private class ContentLine(val text: String) {
        val unfolded: String = text.replace(FOLD, "")
        private val valueStart: Int = valueStartOf(unfolded)
        private val name: String = unfolded.substring(0, nameEndOf(unfolded)).uppercase()
        private val begin: String? = if (name == "BEGIN") value().trim().uppercase() else null
        private val end: String? = if (name == "END") value().trim().uppercase() else null

        /** Returns the property name, upper-cased: the text before the first `;` or `:`. */
        fun name(): String = name

        /** Returns the component this line begins, upper-cased, or null. */
        fun beginOf(): String? = begin

        /** Returns the component this line ends, upper-cased, or null. */
        fun endOf(): String? = end

        /** Returns the value: the text after the first `:` outside a quoted parameter value. */
        fun value(): String = unfolded.substring(valueStart)

        /** Returns this line, unfolded, with its value replaced by [newValue]. */
        fun withValue(newValue: String): String = unfolded.substring(0, valueStart) + newValue

        /** Returns the TZID parameter values on this line. */
        fun tzids(): List<String> {
            val params = unfolded.substring(0, (valueStart - 1).coerceAtLeast(0))
            return TZID_PARAM.findAll(params).map { it.groupValues[1].ifEmpty { it.groupValues[2] }.trim() }.toList()
        }

        private companion object {
            fun nameEndOf(line: String): Int {
                val semicolon = line.indexOf(';').takeIf { it >= 0 } ?: Int.MAX_VALUE
                val colon = line.indexOf(':').takeIf { it >= 0 } ?: line.length
                return minOf(semicolon, colon)
            }

            fun valueStartOf(line: String): Int {
                var quoted = false
                for (index in line.indices) {
                    when (line[index]) {
                        '"' -> quoted = !quoted
                        ':' -> if (!quoted) return index + 1
                    }
                }
                return line.length
            }
        }
    }

    /** Splits [content] into content lines, each with its folded continuation lines. */
    private fun logicalLines(content: String): List<ContentLine> {
        val physical = content.split(Regex("\r\n|\n|\r"))
        val units = mutableListOf<StringBuilder>()
        for (line in physical) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && units.isNotEmpty()) {
                units.last().append("\r\n").append(line)
            } else if (line.isNotEmpty()) {
                units += StringBuilder(line)
            }
        }
        return units.map { ContentLine(it.toString()) }
    }

    /** Index of the END line closing the component that begins at [start], or the last line. */
    private fun componentEnd(units: List<ContentLine>, start: Int, name: String): Int {
        var depth = 0
        for (index in start until units.size) {
            if (units[index].beginOf() == name) depth++
            if (units[index].endOf() == name && --depth == 0) return index
        }
        return units.lastIndex
    }

    /** The VEVENT's own UID value (not a VALARM's), or null when it has none. */
    private fun uidOf(block: List<ContentLine>): String? {
        var depth = 0
        for (unit in block) {
            if (unit.beginOf() != null) depth++
            if (unit.endOf() != null) depth--
            if (depth == 1 && unit.name() == "UID") return unit.value().trim().ifEmpty { null }
        }
        return null
    }

    private fun withoutAlarms(block: List<ContentLine>): List<ContentLine> {
        val kept = mutableListOf<ContentLine>()
        var inAlarm = 0
        for (unit in block) {
            if (unit.beginOf() == "VALARM") inAlarm++
            if (inAlarm == 0) kept += unit
            if (unit.endOf() == "VALARM") inAlarm--
        }
        return kept
    }

    private val FOLD = Regex("\r?\n[ \t]")
    private val TZID_PARAM = Regex(""";TZID=(?:"([^"]*)"|([^;:]*))""", RegexOption.IGNORE_CASE)
}
