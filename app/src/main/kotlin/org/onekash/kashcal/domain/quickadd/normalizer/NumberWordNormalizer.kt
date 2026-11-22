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

    private val compoundOnes: List<String> = ones.keys.filter { it.length > 2 }

    // Hyphenated compounds such as "forty-five", ignoring case, with one capturing group per
    // tens word and then one per ones word, so both values come from the groups that matched
    // (see [wordRegex] for why not from the text).
    private val compoundRegex = Regex(
        tens.keys.joinToString("|", "(?:", ")") { "(${Regex.escape(it)})" } + "-" +
            compoundOnes.joinToString("|", "(?:", ")") { "(${Regex.escape(it)})" },
        RegexOption.IGNORE_CASE
    )

    private val tensValues: List<Int> = tens.values.toList()

    private val numbers: Map<String, Int> = ones + tens

    // Longest first, so "eighteen" is tried before "eight".
    private val orderedWords: List<String> = numbers.keys.sortedByDescending { it.length }

    // Every single number word in one pattern, one capturing group per word in [orderedWords],
    // ignoring case, so the input is scanned once. The value comes from the group that matched,
    // not from the matched text, which case folding can make differ from the word (a long s
    // for "s"). A word of one or two letters ("a", "an") must not be followed by a dot, so
    // "a.m." doesn't become "1.m."
    private val wordRegex = Regex(
        buildString {
            append("\\b(?:")
            for (i in orderedWords.indices) {
                val word = orderedWords[i]
                if (i > 0) append('|')
                append('(').append(Regex.escape(word)).append(')')
                append(if (word.length <= 2) "\\b(?![.])" else "\\b")
            }
            append(')')
        },
        RegexOption.IGNORE_CASE
    )

    private val wordValues: List<String> = orderedWords.map { numbers.getValue(it).toString() }

    override fun normalize(input: SpannedText): SpannedText {
        // Compounds first, so "forty-five" isn't read as "forty" and "five".
        val result = input.replace(compoundRegex) { match ->
            val group = matchedGroups(match)
            (tensValues[group[0]] + ones.getValue(compoundOnes[group[1] - tens.size])).toString()
        }

        return result.replace(wordRegex) { match -> wordValues[matchedGroup(match)] }
    }

    /** Returns the 0-based indexes of the groups that matched, in order. */
    private fun matchedGroups(match: MatchResult): List<Int> {
        val groups = match.groups
        return (1 until groups.size).filter { groups[it] != null }.map { it - 1 }
    }

    /** Returns the index in [orderedWords] of the group that matched. */
    private fun matchedGroup(match: MatchResult): Int {
        val groups = match.groups
        for (i in 1 until groups.size) if (groups[i] != null) return i - 1
        error("no number word group matched")
    }
}
