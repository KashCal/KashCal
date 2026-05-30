package org.onekash.kashcal.ui.util.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class SearchHighlightTest {

    private val highlightStyle = SpanStyle(background = Color(0xFF6750A4))
    private var savedLocale: Locale = Locale.getDefault()

    @Before
    fun saveLocale() {
        savedLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    @Test
    fun `empty query returns plain string with no spans`() {
        val result = highlighted("Time Format", "", highlightStyle)
        assertEquals("Time Format", result.text)
        assertTrue("expected no spans for empty query", result.spanStyles.isEmpty())
    }

    @Test
    fun `whitespace-only query returns plain string with no spans`() {
        val result = highlighted("Time Format", "   ", highlightStyle)
        assertEquals("Time Format", result.text)
        assertTrue("expected no spans for whitespace query", result.spanStyles.isEmpty())
    }

    @Test
    fun `query 'time' against 'Time Format' applies one span over indices 0 to 4`() {
        val result = highlighted("Time Format", "time", highlightStyle)
        assertEquals("Time Format", result.text)
        assertEquals(1, result.spanStyles.size)
        val span = result.spanStyles.single()
        assertEquals(highlightStyle, span.item)
        assertEquals(0, span.start)
        assertEquals(4, span.end)
    }

    @Test
    fun `query '30' against 'Sync Lookback - 30 days' highlights the 30 substring`() {
        val source = "Sync Lookback - 30 days"
        val result = highlighted(source, "30", highlightStyle)
        assertEquals(source, result.text)
        assertEquals(1, result.spanStyles.size)
        val span = result.spanStyles.single()
        val expectedStart = source.indexOf("30")
        assertEquals(expectedStart, span.start)
        assertEquals(expectedStart + 2, span.end)
    }

    @Test
    fun `query 'on' highlights every non-overlapping occurrence in 'Notifications'`() {
        val result = highlighted("Notifications", "on", highlightStyle)
        // N(0) o(1) t(2) i(3) f(4) i(5) c(6) a(7) t(8) i(9) o(10) n(11) s(12).
        // Only "on" pair is at indices 10..11.
        assertEquals(1, result.spanStyles.size)
        val span = result.spanStyles.single()
        assertEquals(10, span.start)
        assertEquals(12, span.end)
    }

    @Test
    fun `query 'on' highlights both occurrences in 'On and on'`() {
        val source = "On and on"
        val result = highlighted(source, "on", highlightStyle)
        assertEquals(2, result.spanStyles.size)
        val starts = result.spanStyles.map { it.start }.sorted()
        assertEquals(listOf(0, 7), starts)
    }

    @Test
    fun `russian locale - query 'врем' matches prefix of 'Время'`() {
        Locale.setDefault(Locale("ru", "RU"))
        val result = highlighted("Время", "врем", highlightStyle)
        assertEquals(1, result.spanStyles.size)
        val span = result.spanStyles.single()
        assertEquals(0, span.start)
        assertEquals(4, span.end)
    }

    @Test
    fun `query has no match returns plain string with no spans`() {
        val result = highlighted("Time Format", "xyz", highlightStyle)
        assertEquals("Time Format", result.text)
        assertTrue("expected no spans for non-matching query", result.spanStyles.isEmpty())
    }

    @Test
    fun `query is case-insensitive - uppercase query matches lowercase target`() {
        val result = highlighted("time format", "TIME", highlightStyle)
        assertEquals(1, result.spanStyles.size)
        val span = result.spanStyles.single()
        assertEquals(0, span.start)
        assertEquals(4, span.end)
    }

    @Test
    fun `Turkish locale - capital I in source does not crash and any spans align with source`() {
        // Regression for the locale-aware lowercase length-mismatch bug:
        // Turkish 'İ'.lowercase(tr) is 'i̇' (two code units). If the
        // matcher computed indices in a lowercased buffer, slicing the
        // original would crash or produce garbled spans. regionMatches
        // works on the original string positions, so offsets always align.
        Locale.setDefault(Locale("tr", "TR"))
        val source = "İCloud"
        val result = highlighted(source, "i", highlightStyle)
        assertEquals(source, result.text)
        // Whether 'İ' folds to 'i' under regionMatches(ignoreCase) is
        // platform-dependent; the contract under test is no crash plus
        // every emitted span landing inside the original string.
        result.spanStyles.forEach { span ->
            assertTrue("span start in range", span.start in 0..source.length)
            assertTrue("span end in range", span.end in span.start..source.length)
        }
    }
}
