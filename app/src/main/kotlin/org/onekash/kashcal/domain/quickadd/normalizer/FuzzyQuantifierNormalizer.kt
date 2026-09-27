package org.onekash.kashcal.domain.quickadd.normalizer

/**
 * Rewrites vague quantities ("a couple", "half an hour") as numbers or minutes, so the rules
 * treat them like explicit offsets and durations.
 *
 * It must run before [NumberWordNormalizer], which maps the articles "a" and "an" to 1 and
 * would turn "a couple" into "1 couple" and "half an hour" into "half 1 hour".
 *
 * Phrases match case-insensitively on word boundaries.
 */
object FuzzyQuantifierNormalizer : Normalizer {

    // Longest phrase first, so "a couple of" wins over "a couple".
    private val replacements: List<Pair<Regex, String>> = listOf(
        // Fractions of an hour become minutes.
        phrase("a quarter of an hour") to "15 minutes",
        phrase("quarter of an hour") to "15 minutes",
        phrase("half an hour") to "30 minutes",
        phrase("half hour") to "30 minutes",
        // Fuzzy counts. Only the article-led forms, so a bare "couple" or "few" stays;
        // "a couple of friends" still becomes "2 friends".
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
