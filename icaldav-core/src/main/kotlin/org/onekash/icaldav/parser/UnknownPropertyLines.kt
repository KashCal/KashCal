package org.onekash.icaldav.parser

/**
 * Collects the content lines of properties the parser does not model, exactly
 * as they were read, so they can be written back unchanged.
 *
 * An IANA or X- property's value can be of any type (RFC 5545 §3.8.8.1,
 * §3.8.8.2), and §3.2.20 requires applications to preserve value data of a
 * value type they don't recognize without interpreting it. Decoding and
 * re-encoding them cannot be done reliably: the original line is the only
 * lossless form. Most of them may also occur more than once (§3.6.1), so they
 * are kept as an ordered list, not keyed by name.
 *
 * The scan runs over the same prepared, unfolded text the parser hands to
 * ical4j, so its components come out in the same order as ical4j's. The lines
 * are therefore exact apart from the parser's input repairs: an uppercase `\N`
 * becomes `\n`, a day DURATION written `PTnD` becomes `PnD`, and a custom
 * TZID whose X-LIC-LOCATION names a known zone becomes that zone.
 */
internal object UnknownPropertyLines {

    /** One component's unknown lines, plus its UID line value for alignment. */
    class Block(val uid: String?, val lines: List<String>)

    /**
     * Scans every [componentName] directly under VCALENDAR. Properties of
     * nested components (VALARM, and so on) are not collected, and neither are
     * the names in [modelled] or VCALENDAR-level properties.
     */
    fun scan(prepared: String, componentName: String, modelled: Set<String>): List<Block> {
        val blocks = mutableListOf<Block>()
        val stack = ArrayDeque<String>()
        var lines: MutableList<String>? = null
        var uid: String? = null

        for (raw in prepared.splitToSequence('\n')) {
            val line = raw.removeSuffix("\r")
            if (line.isBlank()) continue
            val name = propertyName(line)
            when (name) {
                "BEGIN" -> {
                    stack.addLast(line.substringAfter(':').trim().uppercase())
                    if (isTarget(stack, componentName)) {
                        lines = mutableListOf()
                        uid = null
                    }
                }
                "END" -> {
                    if (isTarget(stack, componentName) && lines != null) {
                        blocks += Block(uid, lines)
                        lines = null
                    }
                    stack.removeLastOrNull()
                }
                else -> if (lines != null && isTarget(stack, componentName)) {
                    if (name == "UID") uid = line.substringAfter(':').trim()
                    if (name !in modelled) lines += line
                }
            }
        }
        return blocks
    }

    /**
     * Attaches each scanned block to its parsed component. Lines are only
     * attached when every block lines up with a component by position and
     * UID; otherwise none are, and callers fall back to the property map.
     * A component without a UID can't be confirmed, so it disables lines for
     * every component of that type in the parse.
     */
    fun <C> alignedLines(blocks: List<Block>, components: List<C>, uidOf: (C) -> String?): List<List<String>>? {
        if (blocks.size != components.size) return null
        val aligned = blocks.zip(components).all { (block, component) ->
            val uid = uidOf(component)?.trim()
            !block.uid.isNullOrEmpty() && block.uid == uid
        }
        return if (aligned) blocks.map { it.lines } else null
    }

    private fun isTarget(stack: ArrayDeque<String>, componentName: String): Boolean =
        stack.size == 2 && stack[0] == "VCALENDAR" && stack[1] == componentName

    /** The name part of a content line: everything before the first ';' or ':'. */
    private fun propertyName(line: String): String {
        val end = line.indexOfFirst { it == ';' || it == ':' }
        return (if (end < 0) line else line.substring(0, end)).trim().uppercase()
    }
}
