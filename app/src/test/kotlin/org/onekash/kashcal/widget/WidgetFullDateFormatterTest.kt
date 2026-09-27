package org.onekash.kashcal.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.util.Locale

/**
 * Tests [WidgetDateFormatter.buildFullDateWidgetLabels], the Date widget's card-layout labels.
 *
 * Runs under Robolectric because the month and day order comes from the Android
 * `DateFormat.getBestDateTimePattern` skeleton lookup, unlike the pure-JVM short-form labels.
 * The month and day string must:
 *  - order month and day per the locale (en "September 19", fr "19 septembre")
 *  - carry no year
 *  - use the locale's month form: Japanese doesn't double the "月" suffix, and Russian uses the
 *    genitive month form of a date
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
