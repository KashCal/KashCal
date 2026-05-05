package org.onekash.kashcal.widget

import org.junit.Test
import org.junit.Assert.assertEquals
import java.time.LocalDate
import java.util.Locale

/**
 * Unit tests for [WidgetDateFormatter].
 *
 * The formatter is the extracted, testable seam for DateWidgetContent's day-name
 * and date-number labels. Pure JVM — no Glance runtime needed.
 */
class WidgetDateFormatterTest {

    private val sunday = LocalDate.of(2026, 5, 3) // Sunday

    @Test
    fun `English produces SUN and day number`() {
        val labels = WidgetDateFormatter.buildDateWidgetLabels(sunday, Locale.ENGLISH)
        assertEquals("SUN", labels.dayName)
        assertEquals("3", labels.dateNumber)
    }

    @Test
    fun `Japanese produces localized single-character day name`() {
        val labels = WidgetDateFormatter.buildDateWidgetLabels(sunday, Locale.JAPANESE)
        // ja_JP SHORT day-of-week is a single kanji ("日" for Sunday)
        assertEquals("日", labels.dayName)
        assertEquals("3", labels.dateNumber)
    }

    @Test
    fun `Turkish Sunday returns PAZ`() {
        val turkish = Locale.forLanguageTag("tr-TR")
        val labels = WidgetDateFormatter.buildDateWidgetLabels(sunday, turkish)
        assertEquals("PAZ", labels.dayName)
    }

    @Test
    fun `Turkish uppercase is dotless-i sensitive at the platform level`() {
        // Turkish SHORT day names (Paz, Pzt, Sal, Çar, Per, Cum, Cmt) contain
        // no 'i'/'ı' characters, so they can't exercise the dotless-i branch
        // of locale-sensitive uppercase. Assert the platform invariant
        // directly: 'i'.uppercase(tr) == "İ", not "I". If this assertion
        // ever fails, it means `uppercase(locale)` is not actually locale-
        // aware, which would be a JDK-level regression — and would also mean
        // `buildDateWidgetLabels` silently produces wrong output for any
        // future Turkish locale that adds day-name forms containing 'i'.
        val turkish = Locale.forLanguageTag("tr-TR")
        assertEquals("İ", "i".uppercase(turkish))
        // Default-locale uppercase differs: on an English JVM this gives "I".
        // We don't assert against Locale.getDefault() to keep the test
        // environment-independent, but this anchors the contract the
        // formatter relies on.
    }

    @Test
    fun `date number uses ASCII digits regardless of locale`() {
        // Arabic locales historically rendered Arabic-Indic digits via
        // NumberFormat, but LocalDate.getDayOfMonth().toString() uses
        // Integer.toString which is locale-independent. Pin this behavior
        // so a future refactor to a locale-aware formatter doesn't silently
        // change widget digits.
        val arabic = Locale.forLanguageTag("ar-SA")
        val labels = WidgetDateFormatter.buildDateWidgetLabels(sunday, arabic)
        assertEquals("3", labels.dateNumber)
    }
}
