package org.onekash.kashcal.domain.quickadd.normalizer

/**
 * Rewrites vague quantity phrases into concrete number+unit forms so downstream
 * rules can treat them like explicit offsets/durations.
 *
 * This MUST run before [NumberWordNormalizer], because that normalizer maps the
 * articles "a"/"an" to 1 (e.g. "a couple" would otherwise become "1 couple" and
 * "half an hour" would become "half 1 hour"), destroying the phrases below.
 *
 * Phrases are matched case-insensitively with surrounding word boundaries.
 */
object FuzzyQuantifierNormalizer : Normalizer {

    // Longest phrases first so "a couple of" wins over "a couple", etc.
    private val replacements: List<Pair<Regex, String>> = listOf(
        // Fractional-hour idioms → explicit minutes (longest first)
        phrase("a quarter of an hour") to "15 minutes",
        phrase("quarter of an hour") to "15 minutes",
        phrase("half an hour") to "30 minutes",
        phrase("half hour") to "30 minutes",
        // Fuzzy counts (only the article-led forms, to avoid rewriting plain
        // nouns like "a couple of friends" — always paired with a following unit
        // in practice, and the count itself is harmless).
        phrase("a couple of") to "2",
        phrase("a couple") to "2",
        phrase("a few") to "3",
    )

    private fun phrase(text: String): Regex =
        Regex("""\b${Regex.escape(text)}\b""", RegexOption.IGNORE_CASE)

    override fun normalize(input: String): String {
        var result = input
        for ((regex, replacement) in replacements) {
            result = regex.replace(result, replacement)
        }
        return result
    }
}
