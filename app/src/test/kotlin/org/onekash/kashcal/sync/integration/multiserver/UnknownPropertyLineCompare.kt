package org.onekash.kashcal.sync.integration.multiserver

/**
 * Compares the property lines of iCalendar bodies the way the multi-server tests for properties
 * the app doesn't model need: per VEVENT, unfolded, with the one rewrite a server does on its own
 * (SOGo, [serverNormalized]) evened out.
 */
internal object UnknownPropertyLineCompare {

    /** Unfolds RFC 5545 §3.1 continuation lines. */
    fun unfold(ics: String) = ics.replace(Regex("""\r?\n[ \t]"""), "")

    /** Top-level property lines of each VEVENT (nested VALARMs skipped). */
    fun veventBlocks(ics: String): List<List<String>> {
        val blocks = mutableListOf<List<String>>()
        var current: MutableList<String>? = null
        var depth = 0
        for (line in unfold(ics).lines().map { it.trimEnd('\r') }) {
            when {
                current == null && line.equals("BEGIN:VEVENT", ignoreCase = true) -> { current = mutableListOf(); depth = 0 }
                current == null -> Unit
                line.equals("END:VEVENT", ignoreCase = true) && depth == 0 -> { blocks += current; current = null }
                line.startsWith("BEGIN:", ignoreCase = true) -> depth++
                line.startsWith("END:", ignoreCase = true) -> depth--
                depth == 0 && line.isNotBlank() -> current += line
            }
        }
        return blocks
    }

    /** The property name of [line], upper-cased. */
    fun nameOf(line: String) = line.split(':', ';').first().uppercase()

    /**
     * SOGo rewrites parameters each time it stores an event, even when a client
     * sends back SOGo's own bytes unchanged (checked with plain PUT/GET, no app
     * involved): it doubles every backslash inside a quoted parameter value
     * (`X-ADDRESS="a\\nb, c"` comes back as `"a\\\\nb, c"`, then
     * `"a\\\\\\\\nb, c"`) and shuffles the parameter order between stores. So
     * for SOGo only, backslash runs inside quoted parameter values compare
     * equal and parameters compare as a set. Names, values and everything else
     * still have to match exactly, on SOGo too.
     */
    fun serverNormalized(config: CalDavServerConfig, line: String): String {
        if (config.name != CalDavServerConfig.SOGO.name) return line
        val colon = splitOutsideQuotes(line, ':').first().length
        val head = line.substring(0, colon)
            .replace(Regex("\"[^\"]*\"")) { quoted -> quoted.value.replace(Regex("""\\+"""), Regex.escapeReplacement("\\")) }
        val parts = splitOutsideQuotes(head, ';')
        return (listOf(parts.first()) + parts.drop(1).sorted()).joinToString(";") + line.substring(colon)
    }

    private fun splitOutsideQuotes(text: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        for (c in text) {
            when {
                c == '"' -> { quoted = !quoted; current.append(c) }
                c == separator && !quoted -> { parts += current.toString(); current.clear() }
                else -> current.append(c)
            }
        }
        parts += current.toString()
        return parts
    }
}
