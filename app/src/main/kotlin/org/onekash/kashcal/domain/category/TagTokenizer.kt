package org.onekash.kashcal.domain.category

/**
 * Owns the "#tag" grammar, shared by Quick Add extraction and the event form's inline "#"
 * autocomplete so both agree on what a tag token looks like.
 *
 * An accepted tag is `#` followed by 1 to 64 letters, digits, `_` or `-` (Unicode-aware).
 * Extraction runs on the raw Quick Add input, before the Quick Add normalizer's character
 * cleanup strips the `#` marker; a parse rule that ran after normalization could never see it.
 */
object TagTokenizer {

    /**
     * `#` and one or more letters, digits, underscores or hyphens. Unbounded on purpose: the
     * length limit is the validator's, so an over-long `#word` is rejected (the form's `TOO_LONG`
     * error) instead of silently truncated to a 64-char tag.
     */
    private val TAG = Regex("""#([\p{L}\p{N}_-]+)""")

    /** A partial, still-being-typed tag anchored to the end of the input. */
    private val TRAILING = Regex("""#([\p{L}\p{N}_-]*)$""")

    private val WHITESPACE = Regex("""\s+""")

    /**
     * Pulls the `#tag` tokens out of [input], returning the text with the accepted tokens
     * removed (whitespace collapsed) and the de-duplicated tag names. Names are validated through
     * [CategoryNameValidator]; casing is first-seen, so a later differently cased duplicate
     * collapses onto the first. A token the validator rejects (e.g. over-length) stays in the
     * text as a literal `#word`: nothing is stripped that wasn't accepted.
     */
    fun extract(input: String): Extraction {
        val names = LinkedHashMap<String, String>() // lowercase key -> first-seen value
        val cleaned = TAG.replace(input) { match ->
            when (val outcome = CategoryNameValidator.validate(match.groupValues[1], names.values.toSet())) {
                is CategoryName.Valid -> {
                    names.putIfAbsent(outcome.value.lowercase(), outcome.value)
                    " " // strip the accepted tag from the title
                }
                is CategoryName.Invalid -> match.value // leave the rejected #token in place
            }
        }.replace(WHITESPACE, " ").trim()
        return Extraction(cleaned, names.values.toList())
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
}
