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
        assertEquals(listOf("%50#%%", "%a#_b%", "%##4%"), SearchQuery.of("50% a_b #4").likePatterns)
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
        assertEquals(SearchQuery.MAX_WORDS, q.words.size)
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
            "team \"lunch", "50% #4 a_b", "врач Ärzte", "定期歯科検診", "  spaced   out  ",
            "amara@example.test", "it's \"half open",
        )
        for (text in corpus) {
            assertEquals(text, SqliteCalendarProvider.tokenizeSearchQuery(text), words(text))
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
