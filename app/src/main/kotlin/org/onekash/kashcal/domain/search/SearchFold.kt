package org.onekash.kashcal.domain.search

/**
 * Folds case one code point at a time, as SQLite's ICU `LIKE` does on the phone, so the reason
 * line and the highlight find what the search matched.
 *
 * Each code point maps to one code point, so an index found in folded text is the same index
 * in the original: "ß" stays "ß" and "İ" stays "İ", where lowercasing a whole string would
 * change its length. Dotless "ı" and "İ" are kept apart from "i", as simple case folding keeps
 * them. Accents are not folded.
 */
object SearchFold {

    private const val DOTLESS_I = 0x0131
    private const val DOTTED_CAPITAL_I = 0x0130

    /**
     * Code points ICU simple case folding (Unicode 16, as on Android 17) joins to another though
     * lowercasing keeps them apart: Greek iota and upsilon with dialytika and tonos in their two
     * encodings, and the two "st" ligatures. An API 37 emulator's SQLite LIKE matches each pair.
     * `SearchFoldPropertiesTest` compares the rest of the code points with ICU.
     */
    private val ICU_ONLY_FOLDS = mapOf(0x1FD3 to 0x0390, 0x1FE3 to 0x03B0, 0xFB05 to 0xFB06)

    /** Returns the folded form of [codePoint]. */
    fun fold(codePoint: Int): Int = when (codePoint) {
        DOTLESS_I, DOTTED_CAPITAL_I -> codePoint
        else -> ICU_ONLY_FOLDS[codePoint] ?: Character.toLowerCase(Character.toUpperCase(codePoint))
    }

    /** Returns [text] folded; same length as [text], so indices carry over. */
    fun fold(text: String): String = buildString(text.length) {
        text.codePoints().forEach { appendCodePoint(fold(it)) }
    }

    /** Returns the first index of [word] in [text] at or after [from], ignoring case, or -1. */
    fun indexOf(text: String, word: String, from: Int = 0): Int {
        if (word.isEmpty()) return -1
        return fold(text).indexOf(fold(word), from)
    }

    /** Returns whether [text] contains [word], ignoring case; see [fold] for repeated checks. */
    fun contains(text: String?, word: String): Boolean = text != null && indexOf(text, word) >= 0
}
