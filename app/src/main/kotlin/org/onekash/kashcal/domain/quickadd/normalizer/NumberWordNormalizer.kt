package org.onekash.kashcal.domain.quickadd.normalizer

object NumberWordNormalizer : Normalizer {

    private val ones = mapOf(
        "zero" to 0, "one" to 1, "a" to 1, "an" to 1,
        "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
        "nineteen" to 19
    )

    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40,
        "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90
    )

    // Compound pattern: "forty-five" → 45 (case-insensitive)
    private val compoundRegex = Regex(
        "(${tens.keys.joinToString("|")})-(${ones.keys.filter { it.length > 2 }.joinToString("|")})",
        RegexOption.IGNORE_CASE
    )

    // Standalone word replacements (sorted by length, case-insensitive)
    // Short words (1-2 chars like "a", "an") use negative lookahead for dot
    // to avoid corrupting "a.m." / "a.m" → "1.m." / "1.m"
    private val wordReplacements = (ones + tens).entries
        .sortedByDescending { it.key.length }
        .map { (word, number) ->
            val suffix = if (word.length <= 2) "(?![.])" else ""
            Regex("\\b${Regex.escape(word)}\\b$suffix", RegexOption.IGNORE_CASE) to number.toString()
        }

    override fun normalize(input: String): String {
        var result = input

        // Replace compounds first (e.g., "forty-five" → "45")
        result = compoundRegex.replace(result) { match ->
            val tensVal = tens[match.groupValues[1].lowercase()] ?: 0
            val onesVal = ones[match.groupValues[2].lowercase()] ?: 0
            (tensVal + onesVal).toString()
        }

        // Replace standalone words (word boundary aware)
        for ((regex, replacement) in wordReplacements) {
            result = regex.replace(result, replacement)
        }

        return result
    }
}
