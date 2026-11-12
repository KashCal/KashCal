package org.onekash.kashcal.domain.search

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.SqliteCalendarProvider
import org.onekash.kashcal.data.db.dao.BaseDaoTest
import org.onekash.kashcal.testutil.PropertyRuns

/**
 * Builds search text from letters, digits, the word separators, the LIKE specials and a few
 * letters whose case folds outside ASCII (German, Cyrillic, accented, Han, Turkish dotted and
 * dotless i). The letters left out are those where [SearchFold] and SQLite's ICU folding may
 * part, which another test compares.
 */
private object SearchText {
    private val letters = ('a'..'z') + ('A'..'Z')
    private val others = ('0'..'9') + listOf('ä', 'Ä', 'ß', 'д', 'Д', 'é', '歯', 'İ', 'ı')

    // The double quote is listed twice so quoted phrases are common.
    private val separators = listOf(' ', '\t', '"', '"', '\'', '.', '?', '!', ',')
    private val likeSpecials = listOf('%', '_', '#')

    val chars = Arb.choice(
        Arb.element(letters),
        Arb.element(others),
        Arb.element(separators),
        Arb.element(likeSpecials),
    )

    fun of(sizes: IntRange) = Arb.list(chars, sizes).map { it.joinToString("") }

    /** Returns [text] quoted with tabs, quotes and backslashes escaped, for failure messages. */
    fun show(text: String): String = "\"" +
        text.replace("\\", "\\\\").replace("\t", "\\t").replace("\"", "\\\"") + "\""
}

/**
 * Checks [SearchQuery] against the platform's search tokenizer
 * ([SqliteCalendarProvider.tokenizeSearchQuery]) over generated text, long enough that some
 * texts hold more than [SearchQuery.MAX_WORDS] words.
 */
class SearchQueryPropertiesTest {

    private val texts = SearchText.of(0..200)

    @Test
    fun `words are the platform's tokens without blank phrases, up to the word limit`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(300), texts) { text ->
            val expected = SqliteCalendarProvider.tokenizeSearchQuery(text)
                .filter { it.isNotBlank() }
                .take(SearchQuery.MAX_WORDS)
            assertEquals("words of ${SearchText.show(text)}", expected, SearchQuery.of(text).words)
        }
    }

    @Test
    fun `the provider query parses back to the same words`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(300), texts) { text ->
            val query = SearchQuery.of(text)
            val providerQuery = query.toProviderQuery()
            assertEquals(
                "words of ${SearchText.show(providerQuery)}, built from ${SearchText.show(text)}",
                query.words,
                SearchQuery.of(providerQuery).words,
            )
        }
    }
}

/**
 * Checks that a word's escaped LIKE pattern (as [SearchQuery.likePatterns] builds it, with
 * [SearchQuery.escapeLike]) matches text in the SQLite under Room when and only when [SearchFold]
 * finds the word, so the reason line and the highlight agree with the search. Text is built
 * without the word, with it, or with it in flipped case, so matches are common.
 */
class SearchQueryLikePropertiesTest : BaseDaoTest() {

    private fun like(text: String, pattern: String): Int =
        database.openHelper.readableDatabase
            .query("SELECT ? LIKE ? ESCAPE '${SearchQuery.LIKE_ESCAPE}'", arrayOf(text, pattern))
            .use { c -> c.moveToFirst(); c.getInt(0) }

    private fun flipCase(word: String, flips: List<Boolean>): String = buildString {
        word.forEachIndexed { i, c ->
            append(
                when {
                    !flips.getOrElse(i) { false } -> c
                    Character.isUpperCase(c) -> Character.toLowerCase(c)
                    else -> Character.toUpperCase(c)
                }
            )
        }
    }

    @Test
    fun `an escaped word matches with LIKE when and only when SearchFold finds it`(): Unit = runBlocking {
        checkAll(
            PropertyRuns.config(150),
            SearchText.of(1..5),
            SearchText.of(0..12),
            SearchText.of(0..12),
            Arb.list(Arb.boolean(), 0..5),
            Arb.int(0..2),
        ) { word, prefix, suffix, flips, mode ->
            val text = when (mode) {
                0 -> prefix + suffix
                1 -> prefix + word + suffix
                else -> prefix + flipCase(word, flips) + suffix
            }
            val pattern = "%${SearchQuery.escapeLike(word)}%"
            val expected = if (SearchFold.contains(text, word)) 1 else 0
            assertEquals(
                "${SearchText.show(text)} LIKE ${SearchText.show(pattern)} for word ${SearchText.show(word)}",
                expected,
                like(text, pattern),
            )
        }
    }
}
