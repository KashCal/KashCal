package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.domain.quickadd.normalizer.MultiWordNormalizer
import org.onekash.kashcal.domain.quickadd.normalizer.NormalizerChain
import org.onekash.kashcal.domain.quickadd.normalizer.NumberWordNormalizer
import org.onekash.kashcal.domain.quickadd.normalizer.SpannedText

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
    fun `MultiWordNormalizer joins phrases only on word boundaries`() {
        assertEquals("call day_after_tomorrow", MultiWordNormalizer.normalize("call day after tomorrow"))
        assertEquals("football day", MultiWordNormalizer.normalize("football day"))
        assertEquals("install day", MultiWordNormalizer.normalize("install day"))
        assertEquals("hall day spa", MultiWordNormalizer.normalize("hall day spa"))
        assertEquals("meeting after workshop", MultiWordNormalizer.normalize("meeting after workshop"))
        assertEquals("gym day_after_tomorrow", MultiWordNormalizer.normalize("gym day after tomorrow"))
        assertEquals("drinks after_work", MultiWordNormalizer.normalize("drinks after work"))
        assertEquals("offsite all_day", MultiWordNormalizer.normalize("offsite all day"))
    }

    @Test
    fun `NormalizerChain keeps the typed position of every word`() {
        val normalized = NormalizerChain().normalize(SpannedText.of("Call Mom, a couple of times!"))
        assertEquals("call mom 2 times", normalized.text)
        assertEquals(
            listOf("call" to 0..3, "mom" to 5..7, "2" to 10..20, "times" to 22..26),
            normalized.words().map { it.text to it.sourceRange }
        )
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
        // Apostrophes, hyphens, slashes, colons and dots are kept.
        val result = chain.normalize("Doctor's re-schedule 1/15 at 3:30 15.01")
        assertEquals("doctor's re-schedule 1/15 at 3:30 15.01", result)
    }

    @Test
    fun `NormalizerChain applies full pipeline`() {
        val chain = NormalizerChain()
        // Lowercasing, character cleanup, number words and the multi-word join.
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

    // ==================== MultiWordNormalizer: all day ====================

    @Test
    fun `MultiWordNormalizer joins all day to all_day`() {
        assertEquals("all_day", MultiWordNormalizer.normalize("all day"))
    }

    @Test
    fun `MultiWordNormalizer joins All Day case insensitive`() {
        assertEquals("all_day meeting", MultiWordNormalizer.normalize("All Day meeting"))
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

    // ==================== Fuzzy quantifiers ====================

    @Test
    fun `chain rewrites a couple to 2 before number-word normalization`() {
        val chain = NormalizerChain()
        // Must resolve "a couple" as a unit; the "a"->1 rule must not fire first.
        assertEquals("in 2 hours", chain.normalize("in a couple hours"))
    }

    @Test
    fun `chain rewrites a few to 3`() {
        val chain = NormalizerChain()
        assertEquals("in 3 days", chain.normalize("in a few days"))
    }

    @Test
    fun `chain rewrites half an hour to 30 minutes`() {
        val chain = NormalizerChain()
        assertEquals("for 30 minutes", chain.normalize("for half an hour"))
    }

    @Test
    fun `chain rewrites quarter of an hour to 15 minutes`() {
        val chain = NormalizerChain()
        assertEquals("in 15 minutes", chain.normalize("in a quarter of an hour"))
    }

    @Test
    fun `several and dozen are not treated as fuzzy counts`() {
        // "several" and "dozen" stay words: a blanket substitution would corrupt ordinary
        // titles ("a dozen eggs" to "12 eggs"). NumberWordNormalizer's separate "a" to 1 rule
        // isn't what this guards.
        val chain = NormalizerChain()
        assertEquals("in several weeks", chain.normalize("in several weeks"))
        assertEquals("dozen", chain.normalize("dozen"))
    }

    @Test
    fun `range dashes become hyphens and other dashes spaces, keeping positions`() {
        val normalized = NormalizerChain().normalize(SpannedText.of("Aug 23\u201328 Sam\u2014tomorrow 3pm \u2014 5pm"))
        assertEquals("aug 23-28 sam tomorrow 3pm - 5pm", normalized.text)
        assertEquals(
            listOf("aug" to 0..2, "23-28" to 4..8, "sam" to 10..12, "tomorrow" to 14..21, "3pm" to 23..25, "-" to 27..27, "5pm" to 29..31),
            normalized.words().map { it.text to it.sourceRange }
        )
    }

    @Test
    fun `a word-final colon becomes a space`() {
        assertEquals("lunch tomorrow bring 15:00", NormalizerChain().normalize("Lunch tomorrow: bring 15:00"))
    }
}
