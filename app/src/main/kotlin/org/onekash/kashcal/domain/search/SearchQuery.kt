package org.onekash.kashcal.domain.search

import org.onekash.kashcal.domain.category.CategoryNameValidator
import org.onekash.kashcal.domain.category.TagTokenizer

/**
 * Splits search text into tags and words. Words are split the way the platform's calendar
 * search does, so app and device calendars match the same words; tags are matched by whole
 * name against an event's tags (RFC 5545 CATEGORIES).
 *
 * A tag is `#` and a name in Quick Add's grammar ([TagTokenizer.NAME_CHAR]), or `#"…"` for any
 * other name, where the `#` starts the text or follows white space; the name must pass
 * [CategoryNameValidator]. A `#` inside a word (`PR#420`) or inside a quoted phrase
 * (`"PR #420"`) is text, and so is a token the validator rejects. Tags whose names fold to the
 * same [SearchFold] text count once, with the first casing.
 *
 * Words are runs of characters other than white space, `"`, `'`, `.`, `?`, `!` and `,` in the
 * rest of the text; a double-quoted phrase is one word. An unbalanced quote is skipped. Unlike
 * the platform, an empty or blank phrase adds no word (the platform turns it into `LIKE '%%'`,
 * which matches every event). Tags and words together keep the first [MAX_TERMS] in the text.
 * Words keep their case: the SQL LIKE folds it.
 */
class SearchQuery private constructor(val words: List<String>, val tags: List<String>) {

    /** True when the text held no tag and no word: there is nothing to search. */
    val isEmpty: Boolean get() = words.isEmpty() && tags.isEmpty()

    /** True when the query is tags alone, with no word. */
    val isTagsOnly: Boolean get() = words.isEmpty() && tags.isNotEmpty()

    private val longest: Int = longestWord()

    private val cjk: Boolean = hasCjk()

    /**
     * True when the query is enough to run a search: a tag, one word of two or more characters,
     * or a Han, Hiragana, Katakana or Hangul character (a whole word in those scripts).
     * One-letter words alone ("a b") would match nearly every event, so they wait for more
     * typing.
     */
    val isSearchable: Boolean = tags.isNotEmpty() || longest >= 2 || cjk

    /**
     * True when a search may expand the repeating series it matches. Words decide: one of
     * three or more characters, or a Han, Hiragana, Katakana or Hangul character; a shorter
     * query matches most series, and expanding them all would store rows the user never asked
     * for. Tags alone always do, since a whole tag name picks out few series.
     */
    val expandsSeries: Boolean = if (words.isEmpty()) tags.isNotEmpty() else longest >= 3 || cjk

    /** One `%word%` pattern per word, `%`, `_` and `#` escaped by `#`, for `LIKE ? ESCAPE '#'`. */
    val likePatterns: List<String> = words.map { "%${escapeLike(it)}%" }

    /**
     * Rebuilds the words as query text for the platform's Instances search, each word quoted,
     * so the provider splits it into exactly [words]. Tags are left out: the platform search
     * doesn't read them.
     */
    fun toProviderQuery(): String = words.joinToString(" ") { "\"$it\"" }

    /** Counts the longest word's characters, leaving out the spaces a quoted phrase keeps. */
    private fun longestWord(): Int =
        words.maxOfOrNull { word -> word.codePoints().filter { !Character.isWhitespace(it) }.count().toInt() } ?: 0

    private fun hasCjk(): Boolean = words.any { word -> word.codePoints().anyMatch { isCjk(it) } }

    override fun equals(other: Any?): Boolean = other is SearchQuery && other.words == words && other.tags == tags

    override fun hashCode(): Int = 31 * words.hashCode() + tags.hashCode()

    override fun toString(): String = "SearchQuery(${words.size} words, ${tags.size} tags)"

    companion object {
        /** Tags and words past this many are ignored, so a pasted paragraph binds few arguments. */
        const val MAX_TERMS = 30

        /** The escape character for `LIKE ... ESCAPE`, the one the platform uses. */
        const val LIKE_ESCAPE = '#'

        private val TOKEN = Regex("[^\\s\"'.?!,]+|\"([^\"]*)\"")

        private val TAG_NAME = Regex("${TagTokenizer.NAME_CHAR}+")

        private const val MAILTO = "mailto:"

        /** A tag or word and where it starts, so the [MAX_TERMS] kept are the text's first. */
        private class Term(val start: Int, val text: String, val isTag: Boolean)

        /** Parses [text] into tags and words; [isSearchable] says whether they are enough. */
        fun of(text: String): SearchQuery {
            val terms = mutableListOf<Term>()
            val rest = StringBuilder(text)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '#' && (i == 0 || text[i - 1].isWhitespace())) {
                    val tag = readTag(text, i)
                    if (tag != null) {
                        terms += Term(i, tag.first, isTag = true)
                        for (k in i until tag.second) rest.setCharAt(k, ' ')
                        i = tag.second
                        continue
                    }
                } else if (c == '"') {
                    // A quoted phrase is skipped whole, so a '#' inside it stays text.
                    val close = text.indexOf('"', i + 1)
                    if (close >= 0) {
                        i = close + 1
                        continue
                    }
                }
                i++
            }
            TOKEN.findAll(rest).forEach { m ->
                val word = m.groups[1]?.value ?: m.value
                if (word.isNotBlank()) terms += Term(m.range.first, word, isTag = false)
            }
            val kept = terms.sortedBy { it.start }.take(MAX_TERMS)
            val tags = LinkedHashMap<String, String>()
            kept.filter { it.isTag }.forEach { tags.putIfAbsent(SearchFold.fold(it.text), it.text) }
            return SearchQuery(kept.filterNot { it.isTag }.map { it.text }, tags.values.toList())
        }

        /**
         * Reads a tag starting at the `#` at [hash]: its name and the index just past it, or
         * null when no tag starts there (no name, an unclosed quote, a name the validator
         * rejects).
         */
        private fun readTag(text: String, hash: Int): Pair<String, Int>? {
            val start = hash + 1
            if (start >= text.length) return null
            val (raw, end) = if (text[start] == '"') {
                val close = text.indexOf('"', start + 1)
                if (close < 0) return null
                text.substring(start + 1, close) to close + 1
            } else {
                val m = TAG_NAME.matchAt(text, start) ?: return null
                m.value to m.range.last + 1
            }
            val name = CategoryNameValidator.normalize(raw) ?: return null
            return name to end
        }

        /**
         * Returns the search text for the tag [name], `#name` or `#"name"`, or null when no
         * text reads back as exactly that tag (a double quote, a comma, more than
         * [CategoryNameValidator.MAX_LENGTH] characters, a leading `#` or surrounding spaces).
         */
        fun tagText(name: String): String? {
            val text = if (TAG_NAME.matches(name)) "#$name" else "#\"$name\""
            val read = of(text)
            return text.takeIf { read.tags == listOf(name) && read.words.isEmpty() }
        }

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
