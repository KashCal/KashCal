package org.onekash.kashcal.domain.search

/**
 * Splits search text into words the way the platform's calendar search does, so app and
 * device calendars match the same words.
 *
 * Words are runs of characters other than white space, `"`, `'`, `.`, `?`, `!` and `,`; a
 * double-quoted phrase is one word. An unbalanced quote is skipped. Unlike the platform, an
 * empty or blank phrase adds no word (the platform turns it into `LIKE '%%'`, which matches
 * every event), and only the first [MAX_WORDS] words are used. Words keep their case: the
 * SQL LIKE folds it.
 */
class SearchQuery private constructor(val words: List<String>) {

    private val longest: Int = longestWord()

    private val cjk: Boolean = hasCjk()

    /**
     * True when the words are enough to run a search: one word of two or more characters, or a
     * Han, Hiragana, Katakana or Hangul character (a whole word in those scripts). One-letter
     * words alone ("a b") would match nearly every event, so they wait for more typing.
     */
    val isSearchable: Boolean = longest >= 2 || cjk

    /**
     * True when a search for these words may expand the repeating series it matches: one word
     * of three or more characters, or a Han, Hiragana, Katakana or Hangul character. A shorter
     * query matches most series, and expanding them all would store rows the user never asked
     * for.
     */
    val expandsSeries: Boolean = longest >= 3 || cjk

    /** One `%word%` pattern per word, `%`, `_` and `#` escaped by `#`, for `LIKE ? ESCAPE '#'`. */
    val likePatterns: List<String> = words.map { "%${escapeLike(it)}%" }

    /**
     * Rebuilds the words as query text for the platform's Instances search, each word quoted,
     * so the provider splits it into exactly [words].
     */
    fun toProviderQuery(): String = words.joinToString(" ") { "\"$it\"" }

    /** Counts the longest word's characters, leaving out the spaces a quoted phrase keeps. */
    private fun longestWord(): Int =
        words.maxOfOrNull { word -> word.codePoints().filter { !Character.isWhitespace(it) }.count().toInt() } ?: 0

    private fun hasCjk(): Boolean = words.any { word -> word.codePoints().anyMatch { isCjk(it) } }

    override fun equals(other: Any?): Boolean = other is SearchQuery && other.words == words

    override fun hashCode(): Int = words.hashCode()

    override fun toString(): String = "SearchQuery(${words.size} words)"

    companion object {
        /** Words past this many are ignored, so a pasted paragraph binds few arguments. */
        const val MAX_WORDS = 30

        /** The escape character for `LIKE ... ESCAPE`, the one the platform uses. */
        const val LIKE_ESCAPE = '#'

        private val TOKEN = Regex("[^\\s\"'.?!,]+|\"([^\"]*)\"")

        private const val MAILTO = "mailto:"

        /** Parses [text] into words; [isSearchable] says whether they are enough to search. */
        fun of(text: String): SearchQuery = SearchQuery(
            TOKEN.findAll(text)
                .map { m -> m.groups[1]?.value ?: m.value }
                .filter { it.isNotBlank() }
                .take(MAX_WORDS)
                .toList()
        )

        /** Escapes `%`, `_` and [LIKE_ESCAPE] with [LIKE_ESCAPE], as the platform does. */
        fun escapeLike(word: String): String = buildString(word.length) {
            word.forEach { c ->
                if (c == '%' || c == '_' || c == LIKE_ESCAPE) append(LIKE_ESCAPE)
                append(c)
            }
        }

        /**
         * Returns the part of a guest address search matches, or null when it matches none.
         *
         * A leading `mailto:` (any case) is removed, and the rest counts only when it looks
         * like an email: it has an `@` and no `:` or `/`. So `urn:uuid:` ids and principal
         * paths are never matched, and "mailto" never matches through the prefix. The platform
         * stores bare emails, so this keeps app and device guests matching alike.
         */
        fun emailShapedAddress(address: String?): String? {
            if (address == null) return null
            val bare = if (address.regionMatches(0, MAILTO, 0, MAILTO.length, ignoreCase = true)) {
                address.substring(MAILTO.length)
            } else {
                address
            }
            return bare.takeIf { '@' in it && ':' !in it && '/' !in it }
        }

        private fun isCjk(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL -> true
            else -> false
        }
    }
}
