package org.onekash.kashcal.domain.quickadd.normalizer

import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer

/** Rewrites Quick Add text for the rules while keeping each character's typed range. */
interface Normalizer {
    fun normalize(input: SpannedText): SpannedText

    fun normalize(input: String): String = normalize(SpannedText.of(input)).text
}

/**
 * Normalizes Quick Add input for the rules. The parser normalizes once and cuts the title and
 * location out of the typed text by each word's range, so case and number words in what the user
 * typed never depend on these steps.
 */
class NormalizerChain : Normalizer {

    override fun normalize(input: SpannedText): SpannedText {
        // Per character, so the text keeps its length; String.lowercase can lengthen it.
        var text = input.lowercase()
        // En and em dashes: a hyphen where they join a range, else a space (CHAR_CLEANUP's
        // result for them).
        text = normalizeDashes(text)
        // Replaces every code point outside CHAR_CLEANUP's keep set with a space.
        text = cleanUp(text)
        // A word-final colon ("tomorrow:", "3pm:") separates, so the word before it is read;
        // "15:00" keeps its colon.
        text = text.replace(WORD_FINAL_COLON, " ")
        text = text.collapseWhitespace()
        // Must precede NumberWordNormalizer, which maps "a" and "an" to 1.
        text = FuzzyQuantifierNormalizer.normalize(text)
        text = NumberWordNormalizer.normalize(text)
        // Multi-word expressions to one underscored word, ignoring case.
        return MultiWordNormalizer.normalize(text)
    }

    companion object {
        // Keeps Unicode letters, digits, emoji (So, other symbol), whitespace and / ' : . -
        // Built only for text with surrogate pairs; the rest uses keptByCleanup, which is the same
        // test for one UTF-16 char.
        private val CHAR_CLEANUP by lazy { Regex("[^\\p{L}\\p{N}\\p{So}\\s/':.\\-]") }
        private const val CLEANUP_KEEP = "/':.-"

        /**
         * Applies CHAR_CLEANUP. Text without surrogate pairs (almost all input) is mapped one
         * character at a time, which is the same as the regex for a single UTF-16 char; a code
         * point outside the BMP goes through the regex, which can turn two chars into one space.
         */
        private fun cleanUp(input: SpannedText): SpannedText {
            val text = input.text
            for (c in text) if (c.isSurrogate()) return input.replace(CHAR_CLEANUP, " ")
            val chars = CharArray(text.length)
            for (i in text.indices) chars[i] = if (keptByCleanup(text[i])) text[i] else ' '
            return input.withChars(chars)
        }

        private fun keptByCleanup(c: Char): Boolean {
            if (c.isLetter() || c in CLEANUP_KEEP) return true
            if (c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r') return true
            return when (Character.getType(c).toByte()) {
                Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
                Character.OTHER_SYMBOL -> true
                else -> false
            }
        }

        private val WORD_FINAL_COLON = Regex(""":(?=\s|$)""")
        private const val EN_DASH = '\u2013'
        private const val EM_DASH = '\u2014'

        /**
         * Turns an en or em dash into a hyphen when it is spaced or glued inside a word that with
         * hyphens is a range ([WordTokenizer.isRangeWord]: "23-28", "3pm-5pm", "15h30-18h"), and
         * into a space otherwise, so a dash joining two words ("Sam", "tomorrow") reads as two
         * words and a dashed slash date ("4/20", "4/22") reads as its first date. One character
         * per character, so every typed position stays.
         */
        private fun normalizeDashes(input: SpannedText): SpannedText {
            val text = input.text
            if (EN_DASH !in text && EM_DASH !in text) return input
            val chars = text.toCharArray()
            for (i in chars.indices) {
                val c = chars[i]
                if (c != EN_DASH && c != EM_DASH) continue
                val spaced = (i == 0 || text[i - 1].isWhitespace()) &&
                    (i == text.length - 1 || text[i + 1].isWhitespace())
                chars[i] = if (spaced || WordTokenizer.isRangeWord(rangeWordAround(text, i))) '-' else ' '
            }
            return input.withChars(chars)
        }

        /**
         * Returns the word around [index] as the rules will see it, with dashes as hyphens and no
         * word-final colon: it ends where CHAR_CLEANUP would put a space, so quotes and brackets
         * around a time range are not part of it.
         */
        private fun rangeWordAround(text: String, index: Int): String {
            var start = index
            var end = index + 1
            while (start > 0) {
                val cp = Character.codePointBefore(text, start)
                if (!isWordCodePoint(cp)) break
                start -= Character.charCount(cp)
            }
            while (end < text.length) {
                val cp = Character.codePointAt(text, end)
                if (!isWordCodePoint(cp)) break
                end += Character.charCount(cp)
            }
            // A word-final colon is a separator for the rules (WORD_FINAL_COLON).
            return text.substring(start, end).replace(EN_DASH, '-').replace(EM_DASH, '-').trimEnd(':')
        }

        // A code point CHAR_CLEANUP keeps (or a dash this step decides), other than whitespace.
        private fun isWordCodePoint(cp: Int): Boolean {
            if (cp == EN_DASH.code || cp == EM_DASH.code) return true
            if (Character.isWhitespace(cp)) return false
            if (Character.isBmpCodePoint(cp)) return keptByCleanup(cp.toChar())
            return Character.isLetter(cp) || Character.getType(cp).toByte() in KEPT_TYPES
        }

        private val KEPT_TYPES = setOf(
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER, Character.OTHER_SYMBOL,
        )
    }
}
