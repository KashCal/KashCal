package org.onekash.kashcal.domain.category

/**
 * The single owner of the "#tag" grammar, shared by Quick Add extraction and
 * the event form's inline "#" autocomplete so both agree on what a tag token
 * looks like.
 *
 * A tag is `#` followed by 1–64 letters, digits, `_`, or `-` (Unicode-aware).
 * Extraction runs on the *raw* Quick Add input, before the Quick Add
 * normalizer's character cleanup strips the `#` marker — a parse rule that ran
 * after normalization could never see it.
 */
object TagTokenizer {

    /**
     * `#` + one-or-more {letter, digit, underscore, hyphen}. Unbounded on
     * purpose — the length limit is the validator's job, not the grammar's, so
     * an over-long `#word` is *rejected* (matching the form's `TOO_LONG` error)
     * rather than silently truncated to a 64-char tag.
     */
    private val TAG = Regex("""#([\p{L}\p{N}_-]+)""")

    /** A partial, still-being-typed tag anchored to the end of the input. */
    private val TRAILING = Regex("""#([\p{L}\p{N}_-]*)$""")

    private val WHITESPACE = Regex("""\s+""")

    /**
     * Pull all `#tag` tokens out of [input], returning the text with the
     * *accepted* tokens removed (whitespace collapsed) and the de-duplicated
     * tag names. Names are validated/deduped through [CategoryNameValidator];
     * casing is first-seen (a later differently-cased duplicate collapses onto
     * the first). A token the validator *rejects* (e.g. over-length) is left in
     * the text as literal `#word` — we never strip what we didn't accept.
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
     * The in-progress `#<prefix>` fragment at the end of [text], or null if the
     * text doesn't currently end in a tag being typed. Returns "" right after
     * the user types a lone trailing `#`.
     */
    fun trailingHashPrefix(text: String): String? =
        TRAILING.find(text)?.groupValues?.get(1)

    /**
     * Remove the trailing in-progress [token] (e.g. "#wo") from the end of
     * [text] and collapse whitespace. Anchored to the end so an identical
     * fragment earlier in the title is left untouched — the inline autocomplete
     * only ever commits the token the user is currently typing.
     */
    fun stripToken(text: String, token: String): String {
        val stripped = if (text.endsWith(token)) text.dropLast(token.length) else text
        return stripped.replace(WHITESPACE, " ").trim()
    }

    data class Extraction(val text: String, val tags: List<String>)
}
