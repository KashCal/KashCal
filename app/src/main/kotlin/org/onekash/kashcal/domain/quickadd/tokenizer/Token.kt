package org.onekash.kashcal.domain.quickadd.tokenizer

/**
 * One classified word of normalized Quick Add input.
 *
 * @param originalText the typed text of the word: the time rule reads it to tell an hour typed as
 *   digits from a number word, the structured-date rule to spot an en or em dash. The title and
 *   location are cut from the typed text by [span].
 * @param span the word's character range in the typed input, or null when the tokens were built
 *   without the typed text (rule-level tests).
 */
data class Token(
    val type: TokenType,
    val text: String,
    val value: Any? = null,
    val originalText: String = text,
    val span: IntRange? = null,
)
