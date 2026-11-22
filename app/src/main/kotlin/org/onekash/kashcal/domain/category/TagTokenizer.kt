package org.onekash.kashcal.domain.category

/**
 * Owns the "#tag" grammar, shared by Quick Add extraction and the event form's inline "#"
 * autocomplete so both agree on what a tag token looks like.
 *
 * An accepted tag is `#` followed by 1 to 64 [NAME_CHAR] (Unicode-aware).
 * Extraction runs on the raw Quick Add input, before the Quick Add normalizer's character
 * cleanup strips the `#` marker; a parse rule that ran after normalization could never see it.
 */
object TagTokenizer {

    /**
     * One character of a `#tag` name: a letter, a combining mark (the vowel signs of Hindi,
     * Thai and other scripts, without which `#काम` would end after its first letter), a digit,
     * `_` or `-`.
     * [org.onekash.kashcal.domain.search.SearchQuery] reads search tags with it.
     */
    const val NAME_CHAR = """[\p{L}\p{M}\p{N}_-]"""

    /**
     * `#` and one or more [NAME_CHAR]. Unbounded on purpose: the
     * length limit is the validator's, so an over-long `#word` is rejected (the form's `TOO_LONG`
     * error) instead of silently truncated to a 64-char tag.
     */
    private val TAG = Regex("""#($NAME_CHAR+)""")

    /** A partial, still-being-typed tag anchored to the end of the input. */
    private val TRAILING = Regex("""#($NAME_CHAR*)$""")

    private val WHITESPACE = Regex("""\s+""")

    /**
     * Pulls the `#tag` tokens out of [input], returning the text with the accepted tokens
     * removed (whitespace collapsed) and the de-duplicated tag names, as [extractRanges] finds
     * them.
     */
    fun extract(input: String): Extraction {
        val ranged = extractRanges(input)
        val cleaned = StringBuilder(input)
        for (range in ranged.ranges.asReversed()) {
            cleaned.replace(range.first, range.last + 1, " ")
        }
        return Extraction(cleaned.toString().replace(WHITESPACE, " ").trim(), ranged.tags)
    }

    /**
     * Finds the accepted `#tag` tokens in [input]: their character ranges, in order, and the
     * de-duplicated tag names. Names are validated through [CategoryNameValidator]; casing is
     * first-seen, so a later differently cased duplicate collapses onto the first but its range
     * is still returned. A token the validator rejects (e.g. over-length) has no range: it stays
     * in the text as a literal `#word`, so nothing is stripped that wasn't accepted.
     */
    fun extractRanges(input: String): RangedExtraction {
        val names = LinkedHashMap<String, String>() // lowercase key -> first-seen value
        val ranges = mutableListOf<IntRange>()
        for (match in TAG.findAll(input)) {
            val outcome = CategoryNameValidator.validate(match.groupValues[1], names.values.toSet())
            if (outcome is CategoryName.Valid) {
                names.putIfAbsent(outcome.value.lowercase(), outcome.value)
                ranges += match.range
            }
        }
        return RangedExtraction(ranges, names.values.toList())
    }

    /**
     * Returns the in-progress `#<prefix>` fragment at the end of [text] without its `#`, or null
     * if the text doesn't end in a tag being typed. Returns "" right after a lone trailing `#`.
     */
    fun trailingHashPrefix(text: String): String? =
        TRAILING.find(text)?.groupValues?.get(1)

    /**
     * Removes the in-progress [token] (e.g. "#wo") from the end of [text] and collapses
     * whitespace. Anchored to the end so an identical fragment earlier in the title is left
     * alone: the inline autocomplete only commits the token the user is typing.
     */
    fun stripToken(text: String, token: String): String {
        val stripped = if (text.endsWith(token)) text.dropLast(token.length) else text
        return stripped.replace(WHITESPACE, " ").trim()
    }

    data class Extraction(val text: String, val tags: List<String>)

    data class RangedExtraction(val ranges: List<IntRange>, val tags: List<String>)
}
