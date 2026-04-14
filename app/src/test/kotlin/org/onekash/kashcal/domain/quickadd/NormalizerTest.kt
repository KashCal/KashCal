package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.domain.quickadd.normalizer.MultiWordNormalizer
import org.onekash.kashcal.domain.quickadd.normalizer.NormalizerChain
import org.onekash.kashcal.domain.quickadd.normalizer.NumberWordNormalizer

class NormalizerTest {

    // ==================== NumberWordNormalizer ====================

    @Test
    fun `NumberWordNormalizer converts fifteen to 15`() {
        assertEquals("15", NumberWordNormalizer.normalize("fifteen"))
    }

    @Test
    fun `NumberWordNormalizer converts forty-five to 45`() {
        assertEquals("45", NumberWordNormalizer.normalize("forty-five"))
    }

    @Test
    fun `NumberWordNormalizer converts a to 1`() {
        assertEquals("in 1 hour", NumberWordNormalizer.normalize("in a hour"))
    }

    @Test
    fun `NumberWordNormalizer converts an to 1`() {
        assertEquals("in 1 hour", NumberWordNormalizer.normalize("in an hour"))
    }

    @Test
    fun `NumberWordNormalizer converts zero to 0`() {
        assertEquals("0", NumberWordNormalizer.normalize("zero"))
    }

    @Test
    fun `NumberWordNormalizer converts twenty to 20`() {
        assertEquals("20", NumberWordNormalizer.normalize("twenty"))
    }

    @Test
    fun `NumberWordNormalizer converts twenty-one to 21`() {
        assertEquals("21", NumberWordNormalizer.normalize("twenty-one"))
    }

    @Test
    fun `NumberWordNormalizer converts ninety-nine to 99`() {
        assertEquals("99", NumberWordNormalizer.normalize("ninety-nine"))
    }

    @Test
    fun `NumberWordNormalizer preserves non-number words`() {
        assertEquals("coffee with sarah", NumberWordNormalizer.normalize("coffee with sarah"))
    }

    @Test
    fun `NumberWordNormalizer handles mixed input`() {
        assertEquals("in 15 minutes", NumberWordNormalizer.normalize("in fifteen minutes"))
    }

    // ==================== MultiWordNormalizer ====================

    @Test
    fun `MultiWordNormalizer joins day after tomorrow`() {
        assertEquals("day_after_tomorrow", MultiWordNormalizer.normalize("day after tomorrow"))
    }

    @Test
    fun `MultiWordNormalizer joins day before yesterday`() {
        assertEquals("day_before_yesterday", MultiWordNormalizer.normalize("day before yesterday"))
    }

    @Test
    fun `MultiWordNormalizer preserves other text`() {
        assertEquals("coffee tomorrow at 3pm", MultiWordNormalizer.normalize("coffee tomorrow at 3pm"))
    }

    @Test
    fun `MultiWordNormalizer joins within larger input`() {
        assertEquals(
            "meeting day_after_tomorrow at 3pm",
            MultiWordNormalizer.normalize("meeting day after tomorrow at 3pm")
        )
    }

    // ==================== NormalizerChain ====================

    @Test
    fun `NormalizerChain lowercases input`() {
        val chain = NormalizerChain()
        assertEquals("tomorrow at 3pm", chain.normalize("TOMORROW at 3PM"))
    }

    @Test
    fun `NormalizerChain normalizes extra whitespace`() {
        val chain = NormalizerChain()
        assertEquals("coffee with sarah tomorrow", chain.normalize("Coffee  with   Sarah   tomorrow"))
    }

    @Test
    fun `NormalizerChain strips special characters except allowed`() {
        val chain = NormalizerChain()
        // Apostrophes, hyphens, slashes, colons, dots should be preserved
        val result = chain.normalize("Doctor's re-schedule 1/15 at 3:30 15.01")
        assertEquals("doctor's re-schedule 1/15 at 3:30 15.01", result)
    }

    @Test
    fun `NormalizerChain applies full pipeline`() {
        val chain = NormalizerChain()
        // Lowercase + char cleanup + number words + multi-word join
        assertEquals(
            "meeting day_after_tomorrow at 15:00",
            chain.normalize("Meeting DAY AFTER TOMORROW at 15:00")
        )
    }

    @Test
    fun `NormalizerChain converts number words in full pipeline`() {
        val chain = NormalizerChain()
        assertEquals("in 30 minutes", chain.normalize("In thirty minutes"))
    }

    @Test
    fun `NormalizerChain handles empty string`() {
        val chain = NormalizerChain()
        assertEquals("", chain.normalize(""))
    }

    @Test
    fun `NormalizerChain handles whitespace only`() {
        val chain = NormalizerChain()
        assertEquals("", chain.normalize("   "))
    }
}
