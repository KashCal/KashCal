package org.onekash.kashcal.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale

/** Pins the relative part of search dates at each unit boundary, in English and Japanese. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RelativeSearchDateTest {

    private val today = LocalDate.of(2026, 11, 11)

    private fun en(date: LocalDate) = relativeSearchDate(date, today, Locale.US)
    private fun ja(date: LocalDate) = relativeSearchDate(date, today, Locale.JAPAN)

    @Test
    fun `days under a week`() {
        assertEquals("today", en(today))
        assertEquals("tomorrow", en(today.plusDays(1)))
        assertEquals("yesterday", en(today.minusDays(1)))
        assertEquals("in 6 days", en(today.plusDays(6)))
        assertEquals("6 days ago", en(today.minusDays(6)))
    }

    @Test
    fun `weeks from a week to a month`() {
        assertEquals("in 1 week", en(today.plusDays(7)))
        assertEquals("in 3 weeks", en(today.plusDays(21)))
        assertEquals("4 weeks ago", en(today.minusDays(29)))
    }

    @Test
    fun `months from a month up to two years`() {
        assertEquals("in 1 month", en(today.plusMonths(1)))
        assertEquals("2 months ago", en(today.minusMonths(2)))
        assertEquals("14 months ago", en(today.minusMonths(14)))
        assertEquals("23 months ago", en(today.minusMonths(23)))
    }

    @Test
    fun `years from two years on`() {
        assertEquals("2 years ago", en(today.minusMonths(24)))
        assertEquals("3 years ago", en(today.minusYears(3)))
        assertEquals("in 2 years", en(today.plusYears(2)))
    }

    @Test
    fun `Japanese uses its own words`() {
        assertEquals("今日", ja(today))
        assertEquals("明日", ja(today.plusDays(1)))
        assertEquals("3 週間後", ja(today.plusDays(21)))
        assertEquals("2 か月前", ja(today.minusMonths(2)))
        assertEquals("3 年前", ja(today.minusYears(3)))
    }
}
