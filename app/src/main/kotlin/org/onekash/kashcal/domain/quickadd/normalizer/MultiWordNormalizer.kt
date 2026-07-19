package org.onekash.kashcal.domain.quickadd.normalizer

object MultiWordNormalizer : Normalizer {

    private val expressions = listOf(
        "day after tomorrow" to "day_after_tomorrow",
        "day before yesterday" to "day_before_yesterday",
        "all day" to "all_day",
        "after work" to "after_work",
        "quarter past" to "quarter_past",
        "half past" to "half_past",
        "quarter to" to "quarter_to"
    )

    override fun normalize(input: String): String {
        var result = input
        for ((phrase, replacement) in expressions) {
            result = result.replace(phrase, replacement, ignoreCase = true)
        }
        return result
    }
}
