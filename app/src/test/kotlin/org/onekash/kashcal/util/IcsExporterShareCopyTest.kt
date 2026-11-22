package org.onekash.kashcal.util

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Event
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The share card sends one occurrence as a file ([IcsExporter.exportShareCopy]). A Room event's
 * copy and a device event's copy both write a "UTC" zone in UTC form, as every Room upload does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsExporterShareCopyTest {

    // 8 Mar 2027 15:00Z.
    private val start = 1_804_518_000_000L

    private fun copy(calendarId: Long) = Event(
        uid = "share@example.test", calendarId = calendarId, title = "Shared",
        startTs = start, endTs = start + 3_600_000, timezone = "UTC", dtstamp = 0L,
    )

    @Test
    fun `a Room event's copy writes UTC form`() {
        val lines = IcsExporter().shareCopyText(copy(calendarId = 4), fromDevice = false).lines()
        assertTrue(lines.toString(), "DTSTART:20270308T150000Z" in lines)
    }

    @Test
    fun `a device event's copy writes UTC form`() {
        val lines = IcsExporter().shareCopyText(copy(calendarId = 0), fromDevice = true).lines()
        assertTrue(lines.toString(), "DTSTART:20270308T150000Z" in lines)
    }
}
