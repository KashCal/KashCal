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
    private val replacements: List<Pair<String, String>> = listOf(
        // Fractions of an hour become minutes.
        "a quarter of an hour" to "15 minutes",
        "quarter of an hour" to "15 minutes",
        "half an hour" to "30 minutes",
        "half hour" to "30 minutes",
        // Fuzzy counts. Only the article-led forms, so a bare "couple" or "few" stays;
        // "a couple of friends" still becomes "2 friends".
        "a couple of" to "2",
        "a couple" to "2",
        "a few" to "3",
    )

    // All phrases in one pattern, in the order above, one capturing group each, so the input is
    // scanned once and the replacement comes from the group that matched (case folding can make
    // the matched text differ from the phrase).
    private val phraseRegex = Regex(
        buildString {
            append("\\b(?:")
            for (i in replacements.indices) {
                if (i > 0) append('|')
                append('(').append(Regex.escape(replacements[i].first)).append(')')
            }
            append(")\\b")
        },
        RegexOption.IGNORE_CASE
    )

    override fun normalize(input: SpannedText): SpannedText =
        input.replace(phraseRegex) { match ->
            val groups = match.groups
            var replacement = ""
            for (i in 1 until groups.size) {
                if (groups[i] != null) {
                    replacement = replacements[i - 1].second
                    break
                }
            }
            replacement
        }
}
