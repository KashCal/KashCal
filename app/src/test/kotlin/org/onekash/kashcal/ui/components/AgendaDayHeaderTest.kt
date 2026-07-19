package org.onekash.kashcal.ui.components

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AgendaDayHeaderTest {

    private lateinit var originalLocale: Locale
    private val todayLabel = "Today"
    private val tomorrowLabel = "Tomorrow"

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `today returns today label plus full localized date`() {
        // 20260718 is Saturday.
        val parts = AgendaDayHeader.format(
            dayCode = 20260718,
            todayDayCode = 20260718,
            todayLabel = todayLabel,
            tomorrowLabel = tomorrowLabel
        )
        assertEquals(todayLabel, parts.relativeLabel)
        // Full weekday + month name, not the short "Sat, Jul 18" form.
        assertTrue("expected full weekday, got '${parts.dateText}'", parts.dateText.contains("Saturday"))
        assertTrue("expected full month, got '${parts.dateText}'", parts.dateText.contains("July"))
        assertTrue(parts.dateText.contains("18"))
    }

    @Test
    fun `tomorrow returns tomorrow label plus date`() {
        // 20260719 is Sunday, the day after 20260718.
        val parts = AgendaDayHeader.format(
            dayCode = 20260719,
            todayDayCode = 20260718,
            todayLabel = todayLabel,
            tomorrowLabel = tomorrowLabel
        )
        assertEquals(tomorrowLabel, parts.relativeLabel)
        assertTrue(parts.dateText.contains("Sunday"))
    }

    @Test
    fun `day two or more out has no relative label`() {
        val parts = AgendaDayHeader.format(
            dayCode = 20260720,
            todayDayCode = 20260718,
            todayLabel = todayLabel,
            tomorrowLabel = tomorrowLabel
        )
        assertNull(parts.relativeLabel)
        assertTrue(parts.dateText.contains("Monday"))
        assertTrue(parts.dateText.contains("20"))
    }

    @Test
    fun `tomorrow across month boundary is labeled tomorrow`() {
        // Today Apr 30 -> tomorrow May 1 (integer +1 would give invalid 20260431).
        val parts = AgendaDayHeader.format(
            dayCode = 20260501,
            todayDayCode = 20260430,
            todayLabel = todayLabel,
            tomorrowLabel = tomorrowLabel
        )
        assertEquals(tomorrowLabel, parts.relativeLabel)
    }

    @Test
    fun `tomorrow across year boundary is labeled tomorrow`() {
        // Today Dec 31 -> tomorrow Jan 1 of next year.
        val parts = AgendaDayHeader.format(
            dayCode = 20270101,
            todayDayCode = 20261231,
            todayLabel = todayLabel,
            tomorrowLabel = tomorrowLabel
        )
        assertEquals(tomorrowLabel, parts.relativeLabel)
    }

    @Test
    fun `date text is non-empty and distinct across months`() {
        val apr = AgendaDayHeader.format(20260430, 20260101, todayLabel, tomorrowLabel).dateText
        val may = AgendaDayHeader.format(20260530, 20260101, todayLabel, tomorrowLabel).dateText
        assertTrue(apr.isNotBlank())
        assertNotEquals(apr, may)
    }

    @Test
    fun `joinedHeader combines label and date and accents the label`() {
        val parts = AgendaDayHeader.format(20260718, 20260718, todayLabel, tomorrowLabel)
        val h = AgendaDayHeader.joinedHeader(parts, "%1\$s · %2\$s")
        assertTrue(h.text.startsWith("Today · "))
        assertTrue(h.text.contains(parts.dateText))
        assertTrue(h.hasAccent)
        assertEquals("Today", h.text.substring(h.accentStart, h.accentEnd))
    }

    @Test
    fun `joinedHeader keeps the date when a locale reorders the template params`() {
        // Date-first template (as ja/zh/ko often need). The date must NOT vanish
        // and the accent must still land on the relative word.
        val parts = AgendaDayHeader.format(20260718, 20260718, todayLabel, tomorrowLabel)
        val h = AgendaDayHeader.joinedHeader(parts, "%2\$s · %1\$s")
        assertTrue("date dropped: '${h.text}'", h.text.contains(parts.dateText))
        assertTrue(h.text.contains("Today"))
        assertTrue(h.hasAccent)
        assertEquals("Today", h.text.substring(h.accentStart, h.accentEnd))
        // Date comes first in this template.
        assertTrue(h.text.startsWith(parts.dateText))
    }

    @Test
    fun `joinedHeader for a plain-date header has no accent range`() {
        val parts = AgendaDayHeader.format(20260720, 20260718, todayLabel, tomorrowLabel)
        val h = AgendaDayHeader.joinedHeader(parts, "%1\$s · %2\$s")
        assertEquals(parts.dateText, h.text)
        assertTrue(!h.hasAccent)
    }
}
