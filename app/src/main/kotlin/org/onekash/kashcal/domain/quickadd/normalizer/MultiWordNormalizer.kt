package org.onekash.kashcal.domain.quickadd.normalizer

object MultiWordNormalizer : Normalizer {

    private val expressions = listOf(
        "day after tomorrow" to "day_after_tomorrow",
        "day before yesterday" to "day_before_yesterday"
    )

    override fun normalize(input: String): String {
        var result = input
        for ((phrase, replacement) in expressions) {
            result = result.replace(phrase, replacement, ignoreCase = true)
        }
        return result
    }
}
