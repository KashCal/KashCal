package org.onekash.kashcal.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.dao.TitleSuggestion

/**
 * Unit tests for [mergeTitleSuggestions] — the pure merge function behind
 * [DisplayEventRepository.suggestTitles]. Full integration with Room + device
 * is covered by the downstream DAO tests and the manual smoke test.
 */
class DisplayEventRepositoryTitleSuggestionTest {

    // ==================== Dedup across sources ====================

    @Test
    fun `sums frequency when same title appears in both sources`() {
        val room = listOf(TitleSuggestion("Coffee", freq = 3, lastUsed = 100L))
        val device = listOf(TitleSuggestion("Coffee", freq = 7, lastUsed = 200L))

        val merged = mergeTitleSuggestions(room, device, minFreq = 2, limit = 5)

        assertEquals(1, merged.size)
        assertEquals("Coffee", merged[0].title)
        assertEquals(10, merged[0].freq)
        assertEquals(200L, merged[0].lastUsed)
    }

    @Test
    fun `case-insensitive dedup uses most recent casing for display`() {
        val room = listOf(TitleSuggestion("coffee", freq = 2, lastUsed = 100L))
        val device = listOf(TitleSuggestion("Coffee", freq = 2, lastUsed = 200L))

        val merged = mergeTitleSuggestions(room, device, minFreq = 2, limit = 5)

        assertEquals(1, merged.size)
        assertEquals("Coffee", merged[0].title) // later lastUsed wins
        assertEquals(4, merged[0].freq)
    }

    @Test
    fun `whitespace normalization merges near-identical titles`() {
        val room = listOf(TitleSuggestion("  Coffee  ", freq = 2, lastUsed = 100L))
        val device = listOf(TitleSuggestion("Coffee", freq = 3, lastUsed = 200L))

        val merged = mergeTitleSuggestions(room, device, minFreq = 2, limit = 5)

        assertEquals(1, merged.size)
        assertEquals("Coffee", merged[0].title)
        assertEquals(5, merged[0].freq)
    }

    // ==================== Single-source fallback ====================

    @Test
    fun `device empty returns room results`() {
        val room = listOf(
            TitleSuggestion("Gym", freq = 3, lastUsed = 100L),
            TitleSuggestion("Standup", freq = 2, lastUsed = 90L)
        )

        val merged = mergeTitleSuggestions(room, emptyList(), minFreq = 2, limit = 5)

        assertEquals(2, merged.size)
        assertEquals("Gym", merged[0].title) // higher freq
    }

    @Test
    fun `room empty returns device results`() {
        val device = listOf(
            TitleSuggestion("Meeting", freq = 5, lastUsed = 100L),
            TitleSuggestion("Call", freq = 2, lastUsed = 80L)
        )

        val merged = mergeTitleSuggestions(emptyList(), device, minFreq = 2, limit = 5)

        assertEquals(2, merged.size)
        assertEquals("Meeting", merged[0].title)
    }

    @Test
    fun `both empty returns empty`() {
        val merged = mergeTitleSuggestions(emptyList(), emptyList(), minFreq = 2, limit = 5)
        assertTrue(merged.isEmpty())
    }

    // ==================== Ranking ====================

    @Test
    fun `ranks by frequency descending across sources`() {
        val room = listOf(
            TitleSuggestion("A", freq = 2, lastUsed = 100L),
            TitleSuggestion("B", freq = 5, lastUsed = 100L)
        )
        val device = listOf(TitleSuggestion("C", freq = 10, lastUsed = 100L))

        val merged = mergeTitleSuggestions(room, device, minFreq = 2, limit = 5)

        assertEquals(listOf("C", "B", "A"), merged.map { it.title })
    }

    @Test
    fun `breaks frequency tie with lastUsed descending`() {
        val room = listOf(
            TitleSuggestion("Older", freq = 3, lastUsed = 100L),
            TitleSuggestion("Newer", freq = 3, lastUsed = 200L)
        )

        val merged = mergeTitleSuggestions(room, emptyList(), minFreq = 2, limit = 5)

        assertEquals("Newer", merged[0].title)
        assertEquals("Older", merged[1].title)
    }

    // ==================== Post-merge filtering ====================

    @Test
    fun `min freq filter applies to merged sum`() {
        // Each source has freq=1 for same title; merged = freq=2, which passes minFreq=2
        val room = listOf(TitleSuggestion("Coffee", freq = 1, lastUsed = 100L))
        val device = listOf(TitleSuggestion("Coffee", freq = 1, lastUsed = 200L))

        val merged = mergeTitleSuggestions(room, device, minFreq = 2, limit = 5)

        assertEquals(1, merged.size)
        assertEquals(2, merged[0].freq)
    }

    @Test
    fun `min freq filter excludes when merged sum still below threshold`() {
        val room = listOf(TitleSuggestion("Rare", freq = 1, lastUsed = 100L))

        val merged = mergeTitleSuggestions(room, emptyList(), minFreq = 2, limit = 5)

        assertTrue(merged.isEmpty())
    }

    @Test
    fun `global limit enforced post-merge`() {
        val room = (1..10).map {
            TitleSuggestion("Title$it", freq = 10 - it + 2, lastUsed = 100L + it)
        }

        val merged = mergeTitleSuggestions(room, emptyList(), minFreq = 2, limit = 5)

        assertEquals(5, merged.size)
    }
}
