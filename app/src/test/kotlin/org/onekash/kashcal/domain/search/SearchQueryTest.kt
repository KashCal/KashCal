package org.onekash.kashcal.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.SqliteCalendarProvider

class SearchQueryTest {

    private fun words(text: String) = SearchQuery.of(text).words

    @Test
    fun `a quoted phrase is one word`() {
        assertEquals(listOf("team lunch"), words("\"team lunch\""))
        assertEquals(listOf("dentist", "team lunch"), words("dentist \"team lunch\""))
    }

    @Test
    fun `an apostrophe and sentence punctuation split words`() {
        assertEquals(listOf("O", "Brien"), words("O'Brien"))
        assertEquals(listOf("a", "b", "c", "d", "e"), words("a.b,c?d!e"))
        assertEquals(listOf("x", "y"), words("x \t\n y"))
    }

    @Test
    fun `percent underscore and hash are escaped with hash`() {
        assertEquals(listOf("%50#%%", "%a#_b%", "%x##4%"), SearchQuery.of("50% a_b x#4").likePatterns)
        assertEquals("plain", SearchQuery.escapeLike("plain"))
    }

    @Test
    fun `spaces or separators alone give no words and are not searchable`() {
        for (text in listOf("", " ", "   \t", "...", ", ? ! '", "\"")) {
            val q = SearchQuery.of(text)
            assertEquals("words of '$text'", emptyList<String>(), q.words)
            assertFalse("'$text' searchable", q.isSearchable)
        }
    }

    @Test
    fun `an unbalanced double quote is ignored`() {
        assertEquals(listOf("team", "lunch"), words("team \"lunch"))
        assertEquals(listOf("a b", "c"), words("\"a b\" \"c"))
    }

    @Test
    fun `an empty or blank quoted phrase adds no word`() {
        assertEquals(emptyList<String>(), words("\"\""))
        assertEquals(emptyList<String>(), words("\" \""))
        assertFalse(SearchQuery.of("\"\" \"  \"").isSearchable)
        assertEquals(listOf("dentist"), words("\"\" dentist"))
    }

    @Test
    fun `one Chinese Japanese or Korean character is searchable`() {
        for (text in listOf("歯", "会", "あ", "カ", "한")) {
            assertTrue("'$text'", SearchQuery.of(text).isSearchable)
        }
    }

    @Test
    fun `one character of any other script is not searchable`() {
        for (text in listOf("a", "Z", "д", "ä", "1", "α", "ש")) {
            assertFalse("'$text'", SearchQuery.of(text).isSearchable)
        }
    }

    @Test
    fun `a word of two or more characters is searchable`() {
        for (text in listOf("ab", "де", "a bc", "12", "ä1", "x \"team lunch\"")) {
            assertTrue("'$text'", SearchQuery.of(text).isSearchable)
        }
    }

    @Test
    fun `spaces inside a quoted phrase are not counted`() {
        assertFalse(SearchQuery.of("\" a\"").isSearchable)
        assertFalse(SearchQuery.of("\" a \"").expandsSeries)
        assertTrue(SearchQuery.of("\"a b\"").isSearchable)
        assertFalse(SearchQuery.of("\"a b\"").expandsSeries)
    }

    @Test
    fun `one-character words alone are not searchable`() {
        for (text in listOf("a b", "a b c", "д е", "1 2")) {
            assertFalse("'$text'", SearchQuery.of(text).isSearchable)
        }
    }

    @Test
    fun `at most thirty words are used`() {
        val text = (1..31).joinToString(" ") { "w$it" }
        val q = SearchQuery.of(text)
        assertEquals(SearchQuery.MAX_TERMS, q.words.size)
        assertEquals("w30", q.words.last())
        assertEquals(30, q.likePatterns.size)
    }

    @Test
    fun `words keep their case`() {
        assertEquals(listOf("Dentist", "ВРАЧ"), words("Dentist ВРАЧ"))
    }

    @Test
    fun `the provider query splits back into the same words`() {
        for (text in listOf("dentist", "\"team lunch\" O'Brien", "50% a_b", "a.b", "\"\" x")) {
            val q = SearchQuery.of(text)
            assertEquals(text, q.words, SearchQuery.of(q.toProviderQuery()).words)
            assertEquals(text, q.words, SqliteCalendarProvider.tokenizeSearchQuery(q.toProviderQuery()))
        }
    }

    @Test
    fun `the words equal the platform tokenizer's output`() {
        val corpus = listOf(
            "dentist", "Dr. Amara Lee", "\"team lunch\" friday", "O'Brien", "a.b,c?d!e",
            "team \"lunch", "50% x#4 a_b", "врач Ärzte", "定期歯科検診", "  spaced   out  ",
            "amara@example.test", "it's \"half open",
        )
        for (text in corpus) {
            assertEquals(text, SqliteCalendarProvider.tokenizeSearchQuery(text), words(text))
        }
    }

    private fun tags(text: String) = SearchQuery.of(text).tags

    @Test
    fun `a hash word is a tag and the rest are words`() {
        assertEquals(listOf("doctor"), tags("#doctor kids"))
        assertEquals(listOf("kids"), words("#doctor kids"))
        assertEquals(listOf("doctor"), tags("kids #doctor"))
        assertEquals(listOf("kids"), words("kids #doctor"))
        assertEquals(listOf("work", "kids"), tags("#work #kids"))
        assertEquals(emptyList<String>(), words("#work #kids"))
    }

    @Test
    fun `a tag name follows the quick add grammar`() {
        assertEquals(listOf("q3_review-2", "Работа", "会議"), tags("#q3_review-2 #Работа #会議"))
        assertEquals(listOf("420"), tags("#420"))
        assertEquals(emptyList<String>(), words("#420"))
        assertEquals(listOf("work"), tags("#work's"))
        assertEquals(listOf("s"), words("#work's"))
        assertEquals(listOf("q3"), tags("#q3.review"))
        assertEquals(listOf("review"), words("#q3.review"))
    }

    @Test
    fun `a tag in a script with vowel signs is read whole`() {
        assertEquals(listOf("काम", "วันหยุด"), tags("#काम #วันหยุด"))
        assertEquals(emptyList<String>(), words("#काम #วันหยุด"))
        assertEquals("#काम", SearchQuery.tagText("काम"))
    }

    @Test
    fun `a quoted tag takes any name`() {
        assertEquals(listOf("Team meeting"), tags("#\"Team meeting\" notes"))
        assertEquals(listOf("notes"), words("#\"Team meeting\" notes"))
        assertEquals(listOf("q3.review"), tags("#\"q3.review\""))
        assertEquals(listOf("Work"), tags("#\"  Work \""))
    }

    @Test
    fun `a hash inside a word or a quoted phrase is text`() {
        assertEquals(emptyList<String>(), tags("PR#420"))
        assertEquals(listOf("PR#420"), words("PR#420"))
        assertEquals(emptyList<String>(), tags("\"PR #420\""))
        assertEquals(listOf("PR #420"), words("\"PR #420\""))
        assertEquals(emptyList<String>(), tags("x#\"a b\""))
        assertEquals(listOf("x#", "a b"), words("x#\"a b\""))
        assertEquals(listOf("Room", "4#"), words("Room 4#"))
    }

    @Test
    fun `a hash with no tag after it is text`() {
        for (text in listOf("#", "# work", "#.", "#\"foo", "#\"\"", "#\"a,b\"")) {
            assertEquals("tags of '$text'", emptyList<String>(), tags(text))
        }
        assertFalse(SearchQuery.of("#").isSearchable)
        assertEquals(listOf("#", "foo"), words("#\"foo"))
        assertEquals(listOf("#", "a,b"), words("#\"a,b\""))
    }

    @Test
    fun `a name the validator rejects is text`() {
        val long = "a".repeat(65)
        assertEquals(emptyList<String>(), tags("#$long"))
        assertEquals(listOf("#$long"), words("#$long"))
        assertEquals(listOf("a".repeat(64)), tags("#${"a".repeat(64)}"))
    }

    @Test
    fun `tags that fold alike count once with the first casing`() {
        assertEquals(listOf("Work"), tags("#Work #work #WORK"))
        assertEquals(listOf("Работа"), tags("#Работа #работа"))
    }

    @Test
    fun `a tag alone is searchable and expands series`() {
        for (text in listOf("#a", "#ab", "#doctor")) {
            val q = SearchQuery.of(text)
            assertTrue("'$text' searchable", q.isSearchable)
            assertTrue("'$text' expands", q.expandsSeries)
        }
    }

    @Test
    fun `with words the words decide series expansion`() {
        assertFalse(SearchQuery.of("#doctor ab").expandsSeries)
        assertTrue(SearchQuery.of("#doctor abc").expandsSeries)
        assertTrue(SearchQuery.of("#doctor a").isSearchable)
    }

    @Test
    fun `tags are not sent to the platform search or the word patterns`() {
        val q = SearchQuery.of("#doctor kids")
        assertEquals("\"kids\"", q.toProviderQuery())
        assertEquals(listOf("%kids%"), q.likePatterns)
    }

    @Test
    fun `tags and words together keep the first thirty terms`() {
        val text = (1..20).joinToString(" ") { "#t$it" } + " " + (1..20).joinToString(" ") { "w$it" }
        val q = SearchQuery.of(text)
        assertEquals(20, q.tags.size)
        assertEquals(10, q.words.size)
        assertEquals("w10", q.words.last())
        val tagsOnly = SearchQuery.of((1..40).joinToString(" ") { "#t$it" })
        assertEquals(SearchQuery.MAX_TERMS, tagsOnly.tags.size)
    }

    @Test
    fun `a tag change is a different query`() {
        assertFalse(SearchQuery.of("#work") == SearchQuery.of("#home"))
        assertFalse(SearchQuery.of("#work") == SearchQuery.of("work"))
        assertEquals(SearchQuery.of("#work kids"), SearchQuery.of("kids #work"))
    }

    @Test
    fun `tag text is the hash form or the quoted form`() {
        assertEquals("#Work", SearchQuery.tagText("Work"))
        assertEquals("#q3_review-2", SearchQuery.tagText("q3_review-2"))
        assertEquals("#\"Team meeting\"", SearchQuery.tagText("Team meeting"))
        assertEquals("#\"q3.review\"", SearchQuery.tagText("q3.review"))
    }

    @Test
    fun `a name no search text reads back has no tag text`() {
        for (name in listOf("x\"b", "a,b", "a".repeat(65), "#work", " Work", "Work ", "", " ")) {
            assertNull("tagText of '$name'", SearchQuery.tagText(name))
        }
    }

    @Test
    fun `a mailto prefix is removed from a guest address in any case`() {
        assertEquals("amara@example.test", SearchQuery.emailShapedAddress("mailto:amara@example.test"))
        assertEquals("amara@example.test", SearchQuery.emailShapedAddress("MAILTO:amara@example.test"))
        assertEquals("amara@example.test", SearchQuery.emailShapedAddress("amara@example.test"))
    }

    @Test
    fun `an address that is not email shaped is left out`() {
        assertNull(SearchQuery.emailShapedAddress("urn:uuid:1234-5678"))
        assertNull(SearchQuery.emailShapedAddress("/123456789/principal/"))
        assertNull(SearchQuery.emailShapedAddress("https://caldav.example.test/u@x/"))
        assertNull(SearchQuery.emailShapedAddress("mailto:noatsign"))
        assertNull(SearchQuery.emailShapedAddress(null))
    }

    @Test
    fun `series are expanded from a word of three characters or one Chinese Japanese or Korean character`() {
        assertFalse(SearchQuery.of("me").expandsSeries)
        assertFalse(SearchQuery.of("a b").expandsSeries)
        assertFalse(SearchQuery.of("a bc").expandsSeries)
        assertFalse(SearchQuery.of("ab cd").expandsSeries)
        assertTrue(SearchQuery.of("phy").expandsSeries)
        assertTrue(SearchQuery.of("abc d").expandsSeries)
        assertTrue(SearchQuery.of("歯").expandsSeries)
        assertTrue(SearchQuery.of("a 歯").expandsSeries)
    }
}
