package org.onekash.kashcal.domain.quickadd.normalizer

/**
 * Holds normalized Quick Add text together with, for each of its characters, the range of the
 * typed text it came from, so a word the rules read can be cut out of what the user typed.
 *
 * Every transform keeps the map: a per-character change maps one to one, and each character a
 * replacement writes maps to the whole typed range of the text it replaced. A word's range is
 * the union of its characters' ranges.
 */
class SpannedText private constructor(
    val text: String,
    // Typed range of text[i]: starts[i] inclusive to ends[i] exclusive.
    private val starts: IntArray,
    private val ends: IntArray,
) {

    /** A whitespace-free run of [text] and the typed range it came from. */
    data class Word(val text: String, val sourceRange: IntRange)

    /** Lowercases each character, one for one (Char.lowercaseChar), so positions stay. */
    fun lowercase(): SpannedText {
        val chars = CharArray(text.length)
        for (i in text.indices) chars[i] = text[i].lowercaseChar()
        return SpannedText(String(chars), starts, ends)
    }

    /** Returns [chars], which must be as long as [text], with this text's typed ranges. */
    fun withChars(chars: CharArray): SpannedText {
        require(chars.size == text.length)
        return SpannedText(String(chars), starts, ends)
    }

    /** Replaces each non-empty match of [regex] with [replacement]. */
    fun replace(regex: Regex, replacement: String): SpannedText = replaceMatches(regex, replacement, null)

    /** Replaces each non-empty match of [regex] with [transform]'s output. */
    fun replace(regex: Regex, transform: (MatchResult) -> String): SpannedText = replaceMatches(regex, null, transform)

    private fun replaceMatches(regex: Regex, fixed: String?, transform: ((MatchResult) -> String)?): SpannedText {
        val out = StringBuilder(text.length)
        val newStarts = IntArrayBuilder(text.length)
        val newEnds = IntArrayBuilder(text.length)
        var cursor = 0
        for (match in regex.findAll(text)) {
            val first = match.range.first
            val last = match.range.last
            if (last < first) continue
            for (i in cursor until first) {
                out.append(text[i])
                newStarts.add(starts[i])
                newEnds.add(ends[i])
            }
            var sourceStart = starts[first]
            var sourceEnd = ends[first]
            for (i in first..last) {
                sourceStart = minOf(sourceStart, starts[i])
                sourceEnd = maxOf(sourceEnd, ends[i])
            }
            for (c in fixed ?: transform!!(match)) {
                out.append(c)
                newStarts.add(sourceStart)
                newEnds.add(sourceEnd)
            }
            cursor = last + 1
        }
        if (cursor == 0) return this
        for (i in cursor until text.length) {
            out.append(text[i])
            newStarts.add(starts[i])
            newEnds.add(ends[i])
        }
        return SpannedText(out.toString(), newStarts.toArray(), newEnds.toArray())
    }

    /**
     * Turns each run of ASCII whitespace (regex `\s`) into one space and trims both ends, in one
     * pass. The space keeps the whole run's typed range.
     */
    fun collapseWhitespace(): SpannedText {
        val out = StringBuilder(text.length)
        val newStarts = IntArrayBuilder(text.length)
        val newEnds = IntArrayBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (!isAsciiWhitespace(text[i])) {
                out.append(text[i])
                newStarts.add(starts[i])
                newEnds.add(ends[i])
                i++
                continue
            }
            val runStart = i
            while (i < text.length && isAsciiWhitespace(text[i])) i++
            // A run at either end is trimmed.
            if (runStart == 0 || i == text.length) continue
            out.append(' ')
            newStarts.add(starts[runStart])
            newEnds.add(ends[i - 1])
        }
        if (out.length == text.length && out.toString() == text) return this
        return SpannedText(out.toString(), newStarts.toArray(), newEnds.toArray())
    }

    private fun isAsciiWhitespace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

    /** Splits [text] at whitespace runs. */
    fun words(): List<Word> {
        val words = mutableListOf<Word>()
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) {
                i++
                continue
            }
            val begin = i
            var sourceStart = starts[i]
            var sourceEnd = ends[i]
            while (i < text.length && !text[i].isWhitespace()) {
                sourceStart = minOf(sourceStart, starts[i])
                sourceEnd = maxOf(sourceEnd, ends[i])
                i++
            }
            words += Word(text.substring(begin, i), sourceStart until sourceEnd)
        }
        return words
    }

    companion object {

        /** Wraps [source] with every character mapped to itself. */
        fun of(source: String): SpannedText =
            SpannedText(source, IntArray(source.length) { it }, IntArray(source.length) { it + 1 })
    }

    private class IntArrayBuilder(capacity: Int) {
        private var values = IntArray(maxOf(capacity, 4))
        private var size = 0

        fun add(value: Int) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        fun toArray(): IntArray = values.copyOf(size)
    }
}
