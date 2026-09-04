package org.onekash.kashcal.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.util.Locale

/**
 * Unit tests for the date widget's full-form labels (the card layout).
 *
 * Robolectric-backed because locale-correct month+day ORDERING comes from ICU's
 * skeleton machinery (an Android API), unlike the short-form [WidgetDateFormatter]
 * labels which are pure JVM. The month+day string must:
 *  - order month and day per the locale (en "September 19" vs fr "19 septembre"),
 *  - carry NO year,
 *  - use the correct month form for the locale — CJK must not double the "月"
 *    suffix, and a Slavic locale must use the date (genitive) month form.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetFullDateFormatterTest {

    // Saturday, 19 September 2026.
    private val date = LocalDate.of(2026, 9, 19)

    @Test
    fun `English full weekday and month day`() {
        val labels = WidgetDateFormatter.buildFullDateWidgetLabels(date, Locale.ENGLISH)
        assertEquals("Saturday", labels.weekdayFull)
        assertEquals("September 19", labels.monthDay)
    }

    @Test
    fun `French reorders day before month`() {
        val labels = WidgetDateFormatter.buildFullDateWidgetLabels(date, Locale.FRENCH)
        assertEquals("samedi", labels.weekdayFull)
        assertEquals("19 septembre", labels.monthDay)
    }

    @Test
    fun `Japanese uses month and day markers without a doubled suffix`() {
        val labels = WidgetDateFormatter.buildFullDateWidgetLabels(date, Locale.JAPANESE)
        assertEquals("土曜日", labels.weekdayFull)
        assertEquals("9月19日", labels.monthDay)
    }

    @Test
    fun `Russian uses the genitive month form in a date`() {
        val russian = Locale.forLanguageTag("ru-RU")
        val labels = WidgetDateFormatter.buildFullDateWidgetLabels(date, russian)
        assertEquals("суббота", labels.weekdayFull)
        assertEquals("19 сентября", labels.monthDay)
    }
}
