package org.onekash.kashcal.widget

import org.junit.Test
import org.junit.Assert.assertEquals
import java.time.LocalDate
import java.util.Locale

/**
 * Tests [WidgetDateFormatter.buildDateWidgetLabels], the day-name and date-number labels of the
 * Date widget's icon layout. Pure JVM, no Glance runtime.
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
        // The Japanese SHORT day-of-week is a single kanji ("日" for Sunday).
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
        // Turkish SHORT day names (Paz, Pzt, Sal, Çar, Per, Cum, Cmt) contain no 'i' or 'ı', so
        // they can't exercise the dotted-i case of locale-sensitive uppercase. This asserts the
        // platform rule the formatter relies on instead: "i".uppercase(tr) is "İ", not "I". If it
        // fails, `uppercase(locale)` isn't locale-aware and `buildDateWidgetLabels` would
        // silently mis-uppercase any Turkish day name containing 'i'.
        val turkish = Locale.forLanguageTag("tr-TR")
        assertEquals("İ", "i".uppercase(turkish))
        // On an English JVM the default-locale uppercase gives "I"; not asserted, so the test
        // doesn't depend on Locale.getDefault().
    }

    @Test
    fun `date number uses ASCII digits regardless of locale`() {
        // A locale-aware NumberFormat renders Arabic-Indic digits for Arabic locales; the
        // formatter uses dayOfMonth.toString(), which is locale-independent. This stops a switch
        // to a locale-aware formatter from silently changing widget digits.
        val arabic = Locale.forLanguageTag("ar-SA")
        val labels = WidgetDateFormatter.buildDateWidgetLabels(sunday, arabic)
        assertEquals("3", labels.dateNumber)
    }
}
