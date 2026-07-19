package org.onekash.kashcal.ui.components

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AgendaWeekBarLogicTest {

    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    // 2026-07-18 is a Saturday.
    private val sat = LocalDate.of(2026, 7, 18)

    @Test
    fun `weekDates sunday-first starts on the sunday of the anchor week`() {
        val dates = AgendaWeekBarLogic.weekDates(sat, Calendar.SUNDAY)
        assertEquals(7, dates.size)
        assertEquals(DayOfWeek.SUNDAY, dates.first().dayOfWeek)
        assertEquals(LocalDate.of(2026, 7, 12), dates.first())
        assertEquals(LocalDate.of(2026, 7, 18), dates.last())
        assertTrue(dates.contains(sat))
    }

    @Test
    fun `weekDates monday-first starts on the monday of the anchor week`() {
        val dates = AgendaWeekBarLogic.weekDates(sat, Calendar.MONDAY)
        assertEquals(DayOfWeek.MONDAY, dates.first().dayOfWeek)
        assertEquals(LocalDate.of(2026, 7, 13), dates.first())
        assertTrue(dates.contains(sat))
    }

    @Test
    fun `weekDates saturday-first starts on the saturday of the anchor week`() {
        val dates = AgendaWeekBarLogic.weekDates(sat, Calendar.SATURDAY)
        assertEquals(DayOfWeek.SATURDAY, dates.first().dayOfWeek)
        // Saturday Jul 18 is itself the week start when weeks begin on Saturday.
        assertEquals(sat, dates.first())
    }

    @Test
    fun `weekdayLetters order follows first-day setting`() {
        val sun = AgendaWeekBarLogic.weekdayLetters(Calendar.SUNDAY)
        val mon = AgendaWeekBarLogic.weekdayLetters(Calendar.MONDAY)
        assertEquals(7, sun.size)
        assertEquals(7, mon.size)
        // English narrow letters: Sunday-first -> S,M,T,W,T,F,S ; Monday-first -> M,T,W,T,F,S,S
        assertEquals("S", sun.first())
        assertEquals("M", mon.first())
        assertEquals("S", mon.last())
        // Every letter is non-blank.
        assertTrue(sun.all { it.isNotBlank() })
    }

    @Test
    fun `zero sentinel resolves to a locale default without crashing`() {
        val dates = AgendaWeekBarLogic.weekDates(sat, 0)
        assertEquals(7, dates.size)
        assertTrue(dates.contains(sat))
        assertEquals(7, AgendaWeekBarLogic.weekdayLetters(0).size)
    }

    @Test
    fun `anchorDateFromItemKey parses trailing daycode from header and card keys`() {
        val fallback = LocalDate.of(2000, 1, 1)
        assertEquals(LocalDate.of(2026, 7, 18), AgendaWeekBarLogic.anchorDateFromItemKey("header_20260718", fallback))
        assertEquals(LocalDate.of(2026, 7, 18), AgendaWeekBarLogic.anchorDateFromItemKey("room_42_1752800000000_20260718", fallback))
        assertEquals(LocalDate.of(2026, 7, 18), AgendaWeekBarLogic.anchorDateFromItemKey("device_99_20260718", fallback))
    }

    @Test
    fun `anchorDateFromItemKey falls back on null or garbage`() {
        val fallback = LocalDate.of(2026, 3, 3)
        assertEquals(fallback, AgendaWeekBarLogic.anchorDateFromItemKey(null, fallback))
        assertEquals(fallback, AgendaWeekBarLogic.anchorDateFromItemKey("no_daycode_here_abc", fallback))
        assertEquals(fallback, AgendaWeekBarLogic.anchorDateFromItemKey("header_99999999", fallback))
    }

    @Test
    fun `resolveAnchorDate holds the tapped week while suppressed`() {
        val held = LocalDate.of(2026, 7, 12)
        val fallback = LocalDate.of(2026, 1, 1)
        // Suppressed: ignore the scrolled key, keep the held (tapped) anchor.
        val r = AgendaWeekBarLogic.resolveAnchorDate(
            topKey = "header_20260801",
            suppressed = true,
            heldAnchor = held,
            fallback = fallback
        )
        assertEquals(held, r)
    }

    @Test
    fun `resolveAnchorDate tracks the scrolled key when not suppressed`() {
        val fallback = LocalDate.of(2026, 1, 1)
        val r = AgendaWeekBarLogic.resolveAnchorDate(
            topKey = "header_20260801",
            suppressed = false,
            heldAnchor = LocalDate.of(2026, 7, 12),
            fallback = fallback
        )
        assertEquals(LocalDate.of(2026, 8, 1), r)
    }

    @Test
    fun `resolveAnchorDate uses fallback when suppressed but nothing held`() {
        val fallback = LocalDate.of(2026, 5, 5)
        val r = AgendaWeekBarLogic.resolveAnchorDate(
            topKey = null,
            suppressed = true,
            heldAnchor = null,
            fallback = fallback
        )
        assertEquals(fallback, r)
    }

    @Test
    fun `topmostAnchorKey skips an item peeking into the top content padding`() {
        // contentPaddingTop = 16px. The previous-week card is scrolled so only its
        // bottom 4px peek into the padding (offset -12, size 16 -> bottom at 4),
        // while the current week's header sits at the content top (offset 16).
        val items = listOf(
            AgendaWeekBarLogic.VisibleItem("room_1_1000_20260711", offset = -12, size = 16),
            AgendaWeekBarLogic.VisibleItem("header_20260712", offset = 16, size = 40)
        )
        assertEquals("header_20260712", AgendaWeekBarLogic.topmostAnchorKey(items, contentPaddingTopPx = 16))
    }

    @Test
    fun `topmostAnchorKey keeps an item that genuinely crosses the content-top line`() {
        // A header straddling the content-top line (offset 10, size 40 -> bottom 50 > 16) owns it.
        val items = listOf(
            AgendaWeekBarLogic.VisibleItem("header_20260712", offset = 10, size = 40),
            AgendaWeekBarLogic.VisibleItem("room_2_2000_20260712", offset = 50, size = 40)
        )
        assertEquals("header_20260712", AgendaWeekBarLogic.topmostAnchorKey(items, contentPaddingTopPx = 16))
    }

    @Test
    fun `topmostAnchorKey falls back to first item when none cross the line`() {
        val items = listOf(AgendaWeekBarLogic.VisibleItem("header_20260712", offset = 0, size = 4))
        assertEquals("header_20260712", AgendaWeekBarLogic.topmostAnchorKey(items, contentPaddingTopPx = 16))
        assertNull(AgendaWeekBarLogic.topmostAnchorKey(emptyList(), contentPaddingTopPx = 16))
    }

    @Test
    fun `cellContentDescription gives the full date with no state words for a plain day`() {
        val desc = AgendaWeekBarLogic.cellContentDescription(
            date = sat, isToday = false, isSelected = false,
            todayLabel = "Today", selectedLabel = "Selected"
        )
        assertEquals("Saturday, July 18", desc)
    }

    @Test
    fun `cellContentDescription appends today and selected states in order`() {
        assertEquals(
            "Saturday, July 18, Today",
            AgendaWeekBarLogic.cellContentDescription(sat, isToday = true, isSelected = false, "Today", "Selected")
        )
        assertEquals(
            "Saturday, July 18, Selected",
            AgendaWeekBarLogic.cellContentDescription(sat, isToday = false, isSelected = true, "Today", "Selected")
        )
        assertEquals(
            "Saturday, July 18, Today, Selected",
            AgendaWeekBarLogic.cellContentDescription(sat, isToday = true, isSelected = true, "Today", "Selected")
        )
    }

    @Test
    fun `weekend flags are independent of first-day setting`() {
        // Saturday & Sunday are weekend regardless of where the week starts.
        assertTrue(org.onekash.kashcal.ui.components.weekview.WeekViewUtils.isWeekend(LocalDate.of(2026, 7, 18)))
        assertTrue(org.onekash.kashcal.ui.components.weekview.WeekViewUtils.isWeekend(LocalDate.of(2026, 7, 19)))
        assertFalse(org.onekash.kashcal.ui.components.weekview.WeekViewUtils.isWeekend(LocalDate.of(2026, 7, 20)))
    }
}
