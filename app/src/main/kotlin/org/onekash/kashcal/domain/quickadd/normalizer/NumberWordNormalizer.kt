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

    // Hyphenated compounds such as "forty-five", ignoring case.
    private val compoundRegex = Regex(
        "(${tens.keys.joinToString("|")})-(${ones.keys.filter { it.length > 2 }.joinToString("|")})",
        RegexOption.IGNORE_CASE
    )

    // Single words, longest first, ignoring case. A word of one or two letters ("a", "an")
    // must not be followed by a dot, so "a.m." doesn't become "1.m."
    private val wordReplacements = (ones + tens).entries
        .sortedByDescending { it.key.length }
        .map { (word, number) ->
            val suffix = if (word.length <= 2) "(?![.])" else ""
            Regex("\\b${Regex.escape(word)}\\b$suffix", RegexOption.IGNORE_CASE) to number.toString()
        }

    override fun normalize(input: String): String {
        var result = input

        // Compounds first, so "forty-five" isn't read as "forty" and "five".
        result = compoundRegex.replace(result) { match ->
            val tensVal = tens[match.groupValues[1].lowercase()] ?: 0
            val onesVal = ones[match.groupValues[2].lowercase()] ?: 0
            (tensVal + onesVal).toString()
        }

        for ((regex, replacement) in wordReplacements) {
            result = regex.replace(result, replacement)
        }

        return result
    }
}
