package org.onekash.kashcal.ui.components.weekview

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.domain.model.DisplayEvent
import java.time.LocalDate

/**
 * Checks the cascade layout of overlapping events used by the Day, 3-day and Week grids: a later
 * event draws on top of the events it overlaps, indented a quarter of the column per level, when
 * every one of them keeps at least the cascade's minimum offset above it; otherwise it joins a
 * side-by-side split packed the way the side-by-side layout packs slots. A covered event's text
 * stops at the top of the first event drawn over it.
 */
class EventCascadeLayoutTest {

    private val date = LocalDate.of(2026, 10, 14)
    private val cascade = WeekViewUtils.OverlapLayout.Cascade(minOffset = 20.dp)

    private fun event(id: Long, hour: Int, minute: Int = 0, durationMinutes: Int = 60): DisplayEvent =
        roomDisplayEvent(id = id, title = "Event $id", date = date, hour = hour, minute = minute, durationMinutes = durationMinutes)

    private fun layout(
        vararg events: DisplayEvent,
        hourHeight: Float = 60f,
        overlap: WeekViewUtils.OverlapLayout = cascade,
        maxVisibleOverlap: Int = WeekViewUtils.MAX_VISIBLE_OVERLAP,
    ) =
        WeekViewUtils.positionEventsForDay(
            events = events.toList(), date = date, dayIndex = 0, hourHeight = hourHeight.dp,
            maxVisibleOverlap = maxVisibleOverlap, overlapLayout = overlap,
        ).associateBy { (it.displayEvent as DisplayEvent.Room).event.id }

    private fun assertPlaced(p: WeekViewUtils.PositionedEvent?, depth: Int, left: Float, width: Float) {
        requireNotNull(p)
        assertEquals("depth of ${p.displayEvent.title}", depth, p.cascadeDepth)
        assertEquals("left of ${p.displayEvent.title}", left, p.leftFraction, 0.001f)
        assertEquals("width of ${p.displayEvent.title}", width, p.widthFraction, 0.001f)
    }

    @Test
    fun `a later event cascades on top when the earlier one keeps a line above it`() {
        // 30 minutes at 60dp an hour is 30dp, at least the 20dp offset.
        val placed = layout(event(1, 9, durationMinutes = 90), event(2, 9, 30))
        assertPlaced(placed[1], depth = 0, left = 0f, width = 1f)
        assertPlaced(placed[2], depth = 1, left = 0.25f, width = 0.75f)
    }

    @Test
    fun `the same pair splits side by side when the hours are zoomed out`() {
        // At 30dp an hour, 30 minutes is 15dp, under the 20dp offset.
        val placed = layout(event(1, 9, durationMinutes = 90), event(2, 9, 30), hourHeight = 30f)
        assertPlaced(placed[1], depth = 0, left = 0f, width = 0.5f)
        assertPlaced(placed[2], depth = 0, left = 0.5f, width = 0.5f)
    }

    @Test
    fun `a smaller offset turns the zoomed-out pair into a cascade`() {
        val placed = layout(
            event(1, 9, durationMinutes = 90), event(2, 9, 30),
            hourHeight = 30f, overlap = WeekViewUtils.OverlapLayout.Cascade(minOffset = 12.dp),
        )
        assertPlaced(placed[2], depth = 1, left = 0.25f, width = 0.75f)
    }

    @Test
    fun `events starting together split side by side`() {
        val placed = layout(event(1, 10), event(2, 10))
        assertPlaced(placed[1], depth = 0, left = 0f, width = 0.5f)
        assertPlaced(placed[2], depth = 0, left = 0.5f, width = 0.5f)
    }

    @Test
    fun `three events starting together give two halves and one hidden, not thirds`() {
        val placed = layout(event(1, 10), event(2, 10), event(3, 10))
        assertEquals(listOf(0, 1, 2), (1L..3L).map { placed[it]!!.overlapIndex })
        for (id in 1L..3L) assertEquals("width of $id", 0.5f, placed[id]!!.widthFraction, 0.001f)
        val (visible, hidden) = WeekViewUtils.groupForDisplay(placed.values.toList(), maxVisibleOverlap = 2)
        assertEquals(2, visible.size)
        assertEquals(1, hidden)
    }

    @Test
    fun `cascades stop at two levels and a third joins the deepest split`() {
        val placed = layout(
            event(1, 9, durationMinutes = 180),
            event(2, 9, 30, durationMinutes = 150),
            event(3, 10, durationMinutes = 120),
            event(4, 10, 30, durationMinutes = 90),
        )
        assertPlaced(placed[1], depth = 0, left = 0f, width = 1f)
        assertPlaced(placed[2], depth = 1, left = 0.25f, width = 0.75f)
        // Event 4 would be depth 3, so it splits event 3's level-2 span [0.5, 1] with it.
        assertPlaced(placed[3], depth = 2, left = 0.5f, width = 0.25f)
        assertPlaced(placed[4], depth = 2, left = 0.75f, width = 0.25f)
        assertEquals(placed[3]!!.groupId, placed[4]!!.groupId)
        assertEquals(0.5f, placed[4]!!.groupLeftFraction, 0.001f)
    }

    @Test
    fun `an event reuses a slot whose earlier event has ended`() {
        // A 9:00-9:20, B 9:10-11:00, C 9:25-9:45. B starts 10 minutes after A, so it splits
        // with A; C overlaps only B, 15 minutes after it, so it joins that split and takes A's
        // slot, now free, instead of hiding behind the count.
        val placed = layout(
            event(1, 9, durationMinutes = 20),
            event(2, 9, 10, durationMinutes = 110),
            event(3, 9, 25, durationMinutes = 20),
        )
        assertEquals(0, placed[1]!!.overlapIndex)
        assertEquals(1, placed[2]!!.overlapIndex)
        assertEquals(0, placed[3]!!.overlapIndex)
        assertEquals(placed[1]!!.groupId, placed[3]!!.groupId)
        assertPlaced(placed[3], depth = 0, left = 0f, width = 0.5f)
        val (_, hidden) = WeekViewUtils.groupForDisplay(placed.values.toList(), maxVisibleOverlap = 2)
        assertEquals(0, hidden)
    }

    @Test
    fun `a short event's minimum-height window counts as overlap`() {
        // A 15-minute event draws 20dp tall, so a 9:15 start still overlaps it and is only 15dp
        // below it: they split.
        val placed = layout(event(1, 9, durationMinutes = 15), event(2, 9, 15))
        assertPlaced(placed[1], depth = 0, left = 0f, width = 0.5f)
        assertPlaced(placed[2], depth = 0, left = 0.5f, width = 0.5f)
    }

    @Test
    fun `an event inside a longer one cascades on top of it`() {
        val placed = layout(event(1, 13, durationMinutes = 240), event(2, 14, durationMinutes = 30))
        assertPlaced(placed[1], depth = 0, left = 0f, width = 1f)
        assertPlaced(placed[2], depth = 1, left = 0.25f, width = 0.75f)
    }

    @Test
    fun `events that don't overlap stay full width at the top level`() {
        val placed = layout(event(1, 9), event(2, 11))
        assertPlaced(placed[1], depth = 0, left = 0f, width = 1f)
        assertPlaced(placed[2], depth = 0, left = 0f, width = 1f)
        assertTrue(placed[1]!!.groupId != placed[2]!!.groupId)
    }

    @Test
    fun `an event on top comes after the events it covers`() {
        val ordered = WeekViewUtils.positionEventsForDay(
            listOf(event(2, 9, 30), event(1, 9, durationMinutes = 90)), date, dayIndex = 0, hourHeight = 60.dp,
            overlapLayout = cascade,
        ).map { (it.displayEvent as DisplayEvent.Room).event.id }
        assertEquals(listOf(1L, 2L), ordered)
    }

    @Test
    fun `the side-by-side layout is unchanged for the same pair`() {
        val placed = layout(event(1, 9, durationMinutes = 90), event(2, 9, 30), overlap = WeekViewUtils.OverlapLayout.SideBySide)
        assertPlaced(placed[1], depth = 0, left = 0f, width = 0.5f)
        assertPlaced(placed[2], depth = 0, left = 0.5f, width = 0.5f)
    }

    @Test
    fun `a covered event's text stops at the top of the event drawn over it`() {
        val placed = layout(event(1, 9, durationMinutes = 90), event(2, 9, 30))
        assertEquals(90f, placed[1]!!.height.value, 0.01f)
        assertEquals(30f, placed[1]!!.textHeight.value, 0.01f)
        assertEquals("the event on top keeps its whole height", placed[2]!!.height, placed[2]!!.textHeight)
    }

    @Test
    fun `text stops at the first of several events drawn over it`() {
        val placed = layout(
            event(1, 9, durationMinutes = 180),
            event(2, 9, 30, durationMinutes = 150),
            event(3, 10, durationMinutes = 120),
        )
        assertEquals(30f, placed[1]!!.textHeight.value, 0.01f)
        assertEquals(30f, placed[2]!!.textHeight.value, 0.01f)
        assertEquals(placed[3]!!.height, placed[3]!!.textHeight)
    }

    @Test
    fun `an event beside the one on top keeps its text`() {
        // Four 9:00 events split the column in quarters; a 10:00 event cascades over [0.25, 1].
        // The first quarter only touches it, so its text runs the whole block.
        val quarters = (1L..4L).map { event(it, 9, durationMinutes = 180) }
        val placed = layout(*quarters.toTypedArray(), event(5, 10), maxVisibleOverlap = 4)
        assertEquals(0.25f, placed[5]!!.leftFraction, 0.001f)
        assertEquals(placed[1]!!.height, placed[1]!!.textHeight)
        for (id in 2L..4L) assertEquals("text of $id", 60f, placed[id]!!.textHeight.value, 0.01f)
    }

    @Test
    fun `an event behind the count doesn't shorten the text under it`() {
        // Cap 1: the 10:00 pair cascades over event 1 and the shorter one goes behind "+1",
        // placed past the column's end.
        val placed = layout(
            event(1, 9, durationMinutes = 180), event(2, 10, durationMinutes = 30), event(3, 10, durationMinutes = 120),
            maxVisibleOverlap = 1,
        )
        assertEquals(1, placed[2]!!.overlapIndex)
        assertEquals(60f, placed[1]!!.textHeight.value, 0.01f)
        assertEquals(placed[3]!!.height, placed[3]!!.textHeight)
    }

    @Test
    fun `side by side every event keeps its whole height for text`() {
        val placed = layout(event(1, 9, durationMinutes = 90), event(2, 9, 30), overlap = WeekViewUtils.OverlapLayout.SideBySide)
        for (p in placed.values) assertEquals(p.height, p.textHeight)
    }
}
