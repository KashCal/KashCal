package org.onekash.kashcal.domain.quickadd

/**
 * Cuts a title or location out of the text the user typed, so it keeps their case, number words
 * and punctuation, then tidies the edges the cuts leave.
 *
 * The text is read as whitespace-separated pieces; control characters and U+200B ZERO WIDTH SPACE
 * also separate pieces.
 * Format characters (soft hyphen, direction marks) are dropped, except U+200D ZERO WIDTH JOINER
 * and the emoji tag characters U+E0020..U+E007F, which hold emoji sequences and flags together.
 * A piece a cut touched (the nearest non-space neighbour on either side was cut) is dropped when
 * it has no letter, digit or other-symbol character ("@" in "Call mom @ 5pm", "?" in "meeting!
 * tomorrow?"), except that a dash, colon or slash with kept words before and after it stays
 * ("Q3 review \u2014 bring notes"; only the first of several between the same two words). A
 * bracket or quote right against a cut, with no space between, is dropped too:
 * an opener before it ("Lunch(Friday)" gives "Lunch") and a closer after it. Across the whole
 * result a space before a word-ending , . ; : ! ? is dropped, repeated commas collapse, and
 * , ; : and dashes are trimmed from both ends.
 */
internal object TypedText {

    private const val OPENERS = "([{\"'“‘«"
    private const val CLOSERS = ")]}\"'”’»"

    // Only before punctuation that ends a word: "Movie !10" keeps its space.
    private val SPACE_BEFORE_PUNCTUATION = Regex("""\s+([,.;:!?])(?=\s|$)""")

    // Hyphen, en dash, em dash, colon, slash.
    private const val SEPARATORS = "-\u2013\u2014:/"

    // Comma, semicolon, colon, hyphen, en dash, em dash.
    private const val EDGE_TRIM = ",;:-\u2013\u2014"

    private class Piece(val text: StringBuilder, val leftCut: Boolean, val leftTouch: Boolean) {
        var rightCut = false
        var rightTouch = false
    }

    /**
     * Returns [source] from [from] (inclusive) to [to] (exclusive) with every range in [removed]
     * cut out and the result tidied, or "" when no letter, digit or other symbol is left.
     */
    fun cut(source: String, from: Int, to: Int, removed: Collection<IntRange>): String {
        val deleted = BooleanArray(to - from)
        for (range in removed) {
            val start = maxOf(range.first, from)
            val end = minOf(range.last, to - 1)
            for (i in start..end) deleted[i - from] = true
        }

        val pieces = mutableListOf<Piece>()
        var current: Piece? = null
        var pendingCut = false
        // True while the previous character was cut, with no separator since.
        var touching = false
        var i = from
        while (i < to) {
            val codePoint = source.codePointAt(i)
            val width = Character.charCount(codePoint)
            when {
                deleted[i - from] -> {
                    current?.rightTouch = true
                    (current ?: pieces.lastOrNull())?.rightCut = true
                    current = null
                    pendingCut = true
                    touching = true
                }
                isDropped(codePoint) -> {}
                isSeparator(codePoint) -> {
                    current = null
                    touching = false
                }
                else -> {
                    val piece = current ?: Piece(StringBuilder(), leftCut = pendingCut, leftTouch = touching).also {
                        pieces += it
                        pendingCut = false
                    }
                    touching = false
                    piece.text.appendCodePoint(codePoint)
                    current = piece
                }
            }
            i += width
        }

        // A dash, colon or slash a cut touched stays when kept words come before and after it;
        // of several between the same two words only the first stays ("Meet Bob - Room 4").
        val contentAfter = BooleanArray(pieces.size)
        for (j in pieces.indices.reversed()) {
            contentAfter[j] = j + 1 < pieces.size && (hasContent(pieces[j + 1].text) || contentAfter[j + 1])
        }
        var contentBefore = false
        var separatorKept = false
        val kept = ArrayList<String>(pieces.size)
        for (index in pieces.indices) {
            val piece = pieces[index]
            if (hasContent(piece.text)) {
                contentBefore = true
                separatorKept = false
            }
            val touched = piece.leftCut || piece.rightCut
            if (touched && !hasContent(piece.text)) {
                // A separator between two cut words separated only those ("tomorrow - 3pm").
                val keep = contentBefore && contentAfter[index] && !separatorKept &&
                    !(piece.leftCut && piece.rightCut) && piece.text.all { it in SEPARATORS }
                if (!keep) continue
                separatorKept = true
                kept.add(piece.text.toString())
                continue
            }
            var text = piece.text.toString()
            if (piece.rightTouch) text = text.trimEnd { it in OPENERS }
            if (piece.leftTouch) text = text.trimStart { it in CLOSERS }
            if (text.isNotEmpty()) kept.add(text)
        }

        val joined = squeeze(kept.joinToString(" ").replace(SPACE_BEFORE_PUNCTUATION, "$1"))
            .trim { it.isWhitespace() || it in EDGE_TRIM }
        return if (hasContent(joined)) joined else ""
    }

    private val BRACKET_PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}')

    /**
     * Returns [from] and [to] widened over a bracket right against them whose partner is already
     * inside, so a location cut from its first to its last word keeps "(downtown)" whole. Quotes
     * around the whole location (at "Joe's") are left out.
     */
    fun widenOverBrackets(source: String, from: Int, to: Int): Pair<Int, Int> {
        var start = from
        var end = to
        while (end < source.length) {
            val opener = BRACKET_PAIRS.entries.firstOrNull { it.value == source[end] }?.key ?: break
            if (opener !in source.substring(start, end)) break
            end++
        }
        while (start > 0) {
            val closer = BRACKET_PAIRS[source[start - 1]] ?: break
            if (closer !in source.substring(start, end)) break
            start--
        }
        return start to end
    }

    /** Collapses repeated commas into one and repeated spaces into one. */
    private fun squeeze(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            if ((c == ',' || c == ' ') && out.isNotEmpty() && out[out.length - 1] == c) continue
            out.append(c)
        }
        return out.toString()
    }

    // U+200B ZERO WIDTH SPACE marks a word break, so it separates like a space.
    private fun isSeparator(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x200B ||
            Character.getType(codePoint) == Character.CONTROL.toInt()

    private fun isDropped(codePoint: Int): Boolean =
        Character.getType(codePoint) == Character.FORMAT.toInt() && codePoint != 0x200B &&
            codePoint != 0x200D && codePoint !in 0xE0020..0xE007F

    private fun hasContent(text: CharSequence): Boolean {
        var i = 0
        while (i < text.length) {
            val codePoint = Character.codePointAt(text, i)
            if (Character.isLetterOrDigit(codePoint) ||
                Character.getType(codePoint) == Character.OTHER_SYMBOL.toInt()
            ) {
                return true
            }
            i += Character.charCount(codePoint)
        }
        return false
    }
}
