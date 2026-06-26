package org.onekash.kashcal.domain.availability

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * Tests for AvailabilityFormatter.
 *
 * Pure formatter; uses Robolectric only for context.getString string-resource lookup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AvailabilityFormatterTest {

    private lateinit var context: Context
    private lateinit var formatter: AvailabilityFormatter

    private val mon: LocalDate = LocalDate.of(2026, 5, 25)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        formatter = AvailabilityFormatter()
    }

    private fun block(day: LocalDate, sH: Int, sM: Int, eH: Int, eM: Int): FreeBlock {
        val start = LocalTime.of(sH, sM)
        val end = LocalTime.of(eH, eM)
        val duration = (end.toSecondOfDay() - start.toSecondOfDay()) / 60L
        return FreeBlock(day, start, end, duration)
    }

    // ========== Empty state ==========

    @Test
    fun `empty block list returns localized empty-state message`() {
        val out = formatter.format(
            blocks = emptyList(),
            startDay = mon,
            days = 7,
            workStartMin = 540,
            workEndMin = 1020,
            locale = Locale.US,
            is24Hour = false,
            context = context
        )
        // Empty-state string resource is used; not the header.
        assertFalse(out.contains("Free over the next"))
        assertTrue(out.isNotBlank())
    }

    // ========== Header presence ==========

    @Test
    fun `non-empty output contains header line with days and work hours`() {
        val out = formatter.format(
            blocks = listOf(block(mon, 10, 0, 12, 0)),
            startDay = mon,
            days = 1,
            workStartMin = 540,
            workEndMin = 1020,
            locale = Locale.US,
            is24Hour = false,
            context = context
        )
        // Header references the day count (1 day) somewhere in the first line.
        val firstLine = out.lineSequence().first()
        assertTrue("Header should reference day count: $firstLine", firstLine.contains("1"))
    }

    // ========== 12h vs 24h ==========

    @Test
    fun `12-hour clock produces AM PM markers`() {
        val out = formatter.format(
            blocks = listOf(block(mon, 14, 0, 17, 0)),
            startDay = mon,
            days = 1,
            workStartMin = 540,
            workEndMin = 1020,
            locale = Locale.US,
            is24Hour = false,
            context = context
        )
        // 14:00 -> "2 PM" or "2:00 PM" — at minimum we expect "PM" to appear.
        assertTrue("Expected PM marker in 12h output: $out", out.contains("PM"))
    }

    @Test
    fun `24-hour clock produces HH colon mm`() {
        val out = formatter.format(
            blocks = listOf(block(mon, 14, 0, 17, 0)),
            startDay = mon,
            days = 1,
            workStartMin = 540,
            workEndMin = 1020,
            locale = Locale.US,
            is24Hour = true,
            context = context
        )
        assertTrue("Expected '14:00' in 24h output: $out", out.contains("14:00"))
        assertFalse("24h output should not contain AM/PM: $out", out.contains("PM") || out.contains("AM"))
    }

    // ========== Day-of-week labels ==========

    @Test
    fun `day-of-week label uses locale-short name`() {
        // 2026-05-25 is a Monday.
        val out = formatter.format(
            blocks = listOf(block(mon, 10, 0, 12, 0)),
            startDay = mon,
            days = 1,
            workStartMin = 540,
            workEndMin = 1020,
            locale = Locale.US,
            is24Hour = false,
            context = context
        )
        // English short Monday is "Mon".
        assertTrue("Expected 'Mon' in output: $out", out.contains("Mon"))
    }

    // ========== Multi-day chronological ordering ==========

    @Test
    fun `multi-day output lists days in chronological order`() {
        val out = formatter.format(
            blocks = listOf(
                block(mon, 10, 0, 12, 0),
                block(mon.plusDays(1), 14, 0, 16, 0),
                block(mon.plusDays(2), 9, 0, 11, 0)
            ),
            startDay = mon,
            days = 3,
            workStartMin = 540,
            workEndMin = 1020,
            locale = Locale.US,
            is24Hour = true,
            context = context
        )
        val monIdx = out.indexOf("Mon")
        val tueIdx = out.indexOf("Tue")
        val wedIdx = out.indexOf("Wed")
        assertTrue(monIdx in 0 until tueIdx)
        assertTrue(tueIdx in 0 until wedIdx)
    }

    // ========== Multiple blocks per day ==========

    @Test
    fun `multiple blocks on same day appear on a single day line`() {
        val out = formatter.format(
            blocks = listOf(
                block(mon, 9, 0, 11, 0),
                block(mon, 13, 0, 17, 0)
            ),
            startDay = mon,
            days = 1,
            workStartMin = 540,
            workEndMin = 1020,
            locale = Locale.US,
            is24Hour = true,
            context = context
        )
        val mondayLines = out.lines().filter { it.contains("Mon") }
        assertEquals("Expected exactly one Monday line: $mondayLines", 1, mondayLines.size)
        // Both ranges appear on the same line.
        val mondayLine = mondayLines.first()
        assertTrue("Expected first range: $mondayLine", mondayLine.contains("09:00"))
        assertTrue("Expected second range: $mondayLine", mondayLine.contains("13:00"))
    }

    // ========== Days with no blocks omitted from body ==========

    @Test
    fun `days with no qualifying block are omitted from body`() {
        // 3-day window, but only Mon and Wed have blocks. Tue not in input.
        val out = formatter.format(
            blocks = listOf(
                block(mon, 10, 0, 12, 0),
                block(mon.plusDays(2), 9, 0, 11, 0)
            ),
            startDay = mon,
            days = 3,
            workStartMin = 540,
            workEndMin = 1020,
            locale = Locale.US,
            is24Hour = true,
            context = context
        )
        assertFalse("Tue should be omitted: $out", out.contains("Tue"))
        assertTrue(out.contains("Mon"))
        assertTrue(out.contains("Wed"))
    }

    // ========== End-of-day sentinel rendering ==========

    @Test
    fun `workEnd of 1440 renders as 24 colon 00 in 24h mode`() {
        val out = formatter.format(
            blocks = listOf(block(mon, 9, 0, 17, 0)),
            startDay = mon,
            days = 1,
            workStartMin = 0,
            workEndMin = 1440,
            locale = Locale.US,
            is24Hour = true,
            context = context
        )
        // Header should reference the 24:00 end-of-day, not "00:00".
        val firstLine = out.lineSequence().first()
        assertTrue("Expected 24:00 in 24h header: $firstLine", firstLine.contains("24:00"))
    }

    @Test
    fun `workEnd of 1440 renders as midnight in 12h mode`() {
        val out = formatter.format(
            blocks = listOf(block(mon, 9, 0, 17, 0)),
            startDay = mon,
            days = 1,
            workStartMin = 0,
            workEndMin = 1440,
            locale = Locale.US,
            is24Hour = false,
            context = context
        )
        val firstLine = out.lineSequence().first()
        assertTrue("Expected 12:00 AM in 12h header: $firstLine", firstLine.contains("12:00 AM"))
    }

    @Test
    fun `LocalTime MAX block end renders as end-of-day not 23 colon 59`() {
        val endOfDayBlock = FreeBlock(mon, LocalTime.of(22, 0), LocalTime.MAX, 120L)
        val out = formatter.format(
            blocks = listOf(endOfDayBlock),
            startDay = mon,
            days = 1,
            workStartMin = 540,
            workEndMin = 1440,
            locale = Locale.US,
            is24Hour = true,
            context = context
        )
        assertTrue("Expected 24:00 in body: $out", out.contains("24:00"))
        assertFalse("Should not show 23:59 fragment: $out", out.contains("23:59"))
    }
}
