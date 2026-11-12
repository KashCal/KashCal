package org.onekash.kashcal.ui.util.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.domain.search.SearchFold
import org.onekash.kashcal.testutil.PropertyRuns

/**
 * Holds [highlighted] with a word list to what [SearchFold] finds: for generated texts and one
 * to three words over ASCII letters, spaces, "ä", "ß", Turkish "İ" and "ı", Cyrillic "д" and two
 * code points outside the BMP, the spans cover exactly the folded occurrences of the words, sit
 * on code point edges and neither overlap nor touch.
 */
class SearchHighlightPropertiesTest {

    private val style = SpanStyle(background = Color(0xFF6750A4))

    // Code points the no-match texts are drawn from.
    private val textAlphabet = listOf("a", "b", "c", "A", "B", "C", "s", "S", " ", "ä", "Ä", "i", "I")

    // Code points that fold apart from every code point of textAlphabet.
    private val apartAlphabet = listOf("ß", "ẞ", "İ", "ı", "д", "Д", "😀", "𝐀")

    private val alphabet = textAlphabet + apartAlphabet

    // The case forms each code point is swapped for when a word is cut from the text.
    private val caseForms = listOf(
        listOf("a", "A"), listOf("b", "B"), listOf("c", "C"), listOf("s", "S"),
        listOf("ä", "Ä"), listOf("i", "I"), listOf("д", "Д"), listOf("ß", "ẞ"),
    ).flatMap { forms -> forms.map { it to forms } }.toMap()

    private data class Case(val text: String, val words: List<String>) {
        override fun toString(): String =
            "text=${codePoints(text)} words=${words.map(::codePoints)}"
    }

    private fun tokens(from: List<String>, size: IntRange) = Arb.list(Arb.element(from), size)

    // Texts over alphabet with words that are either cut from the text and recased, so most
    // cases have matches, or drawn at random, so some have none.
    private val cases = arbitrary {
        val textTokens = tokens(alphabet, 0..24).bind()
        val words = (1..Arb.int(1..3).bind()).map {
            if (textTokens.isNotEmpty() && Arb.boolean().bind()) {
                val from = Arb.int(0 until textTokens.size).bind()
                val to = Arb.int(from + 1..minOf(textTokens.size, from + 4)).bind()
                textTokens.subList(from, to)
                    .map { Arb.element(caseForms[it] ?: listOf(it)).bind() }
                    .joinToString("")
            } else {
                tokens(alphabet, 1..4).bind().joinToString("")
            }
        }
        Case(textTokens.joinToString(""), words)
    }

    // Texts over textAlphabet with words that each hold a code point of apartAlphabet.
    private val missCases = arbitrary {
        val text = tokens(textAlphabet, 0..24).bind().joinToString("")
        val words = (1..Arb.int(1..3).bind()).map {
            val parts = tokens(alphabet, 0..3).bind().toMutableList()
            parts.add(Arb.int(0..parts.size).bind(), Arb.element(apartAlphabet).bind())
            parts.joinToString("")
        }
        Case(text, words)
    }

    /**
     * Returns the char indices of [text] inside some occurrence of a word, found with
     * [SearchFold.indexOf] at every start. Blank words are left out, as [highlighted] leaves them.
     */
    private fun expectedCover(text: String, words: List<String>): Set<Int> {
        val cover = sortedSetOf<Int>()
        for (word in words.filter { it.isNotBlank() }) {
            var at = SearchFold.indexOf(text, word)
            while (at >= 0) {
                cover += at until at + word.length
                at = SearchFold.indexOf(text, word, at + 1)
            }
        }
        return cover
    }

    private fun sortedSpans(result: AnnotatedString) = result.spanStyles.sortedBy { it.start }

    private fun onCodePointEdge(text: String, index: Int): Boolean =
        index <= 0 || index >= text.length ||
            !(text[index - 1].isHighSurrogate() && text[index].isLowSurrogate())

    @Test
    fun `span edges fall between code points`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(300), cases) { case ->
            val result = highlighted(case.text, case.words, style)
            for (span in result.spanStyles) {
                assertTrue("span ${span.start}..${span.end} out of range, $case", span.start in 0 until span.end && span.end <= case.text.length)
                assertTrue("span start ${span.start} splits a surrogate pair, $case", onCodePointEdge(case.text, span.start))
                assertTrue("span end ${span.end} splits a surrogate pair, $case", onCodePointEdge(case.text, span.end))
            }
        }
    }

    @Test
    fun `spans neither overlap nor touch`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(300), cases) { case ->
            val spans = sortedSpans(highlighted(case.text, case.words, style))
            spans.zipWithNext().forEach { (before, after) ->
                assertTrue(
                    "span ${before.start}..${before.end} meets ${after.start}..${after.end}, $case",
                    after.start > before.end,
                )
            }
        }
    }

    @Test
    fun `spans cover exactly the folded occurrences of the words`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(300), cases) { case ->
            val result = highlighted(case.text, case.words, style)
            assertEquals("text changed, $case", case.text, result.text)
            assertTrue("a span with another style, $case", result.spanStyles.all { it.item == style })
            val covered = result.spanStyles.flatMap { it.start until it.end }.toSortedSet()
            assertEquals("covered indices, $case", expectedCover(case.text, case.words), covered)
        }
    }

    @Test
    fun `a text without any of the words has no spans`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(300), missCases) { case ->
            assertTrue("generator made a match, $case", expectedCover(case.text, case.words).isEmpty())
            val result = highlighted(case.text, case.words, style)
            assertEquals("text changed, $case", case.text, result.text)
            assertTrue("spans ${result.spanStyles.map { it.start..it.end }}, $case", result.spanStyles.isEmpty())
        }
    }

    private companion object {
        fun codePoints(s: String): String =
            s.codePoints().toArray().joinToString(" ", "[", "]") { "%04X".format(it) }
    }
}
