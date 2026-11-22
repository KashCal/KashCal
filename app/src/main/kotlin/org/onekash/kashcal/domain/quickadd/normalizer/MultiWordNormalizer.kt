package org.onekash.kashcal.domain.quickadd.normalizer

/**
 * Joins a few multi-word expressions into one underscored word for the rules. Each phrase
 * matches only as whole words, so "call day" or "football day" never becomes "all_day".
 */
object MultiWordNormalizer : Normalizer {

    private val expressions = listOf(
        "day after tomorrow" to "day_after_tomorrow",
        "day before yesterday" to "day_before_yesterday",
        "all day" to "all_day",
        "after work" to "after_work",
        "quarter past" to "quarter_past",
        "half past" to "half_past",
        "quarter to" to "quarter_to"
    ).map { (phrase, joined) ->
        Regex("""\b${Regex.escape(phrase)}\b""", RegexOption.IGNORE_CASE) to joined
    }

    override fun normalize(input: SpannedText): SpannedText {
        var result = input
        for ((regex, joined) in expressions) {
            result = result.replace(regex, joined)
        }
        return result
    }
}
