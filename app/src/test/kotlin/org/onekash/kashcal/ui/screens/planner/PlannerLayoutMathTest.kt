package org.onekash.kashcal.ui.screens.planner

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.domain.planner.clampPlannerZoom
import org.onekash.kashcal.ui.shared.PinchZoomAccumulator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Tests the Planner's grid shape, row fitting, zoom limits and day timestamps. */
class PlannerLayoutMathTest {

    @Test
    fun `a portrait phone gets two columns of four rows`() {
        assertEquals(PlannerGridShape(columns = 2, rows = 4), plannerGridShape(widthDp = 360, isCompactHeight = false))
    }

    @Test
    fun `a short landscape phone gets four columns of two rows`() {
        assertEquals(PlannerGridShape(columns = 4, rows = 2), plannerGridShape(widthDp = 800, isCompactHeight = true))
    }

    @Test
    fun `a wide window gets four columns, one at 599dp doesn't`() {
        assertEquals(PlannerGridShape(4, 2), plannerGridShape(widthDp = 600, isCompactHeight = false))
        assertEquals(PlannerGridShape(4, 2), plannerGridShape(widthDp = 700, isCompactHeight = false))
        assertEquals(PlannerGridShape(2, 4), plannerGridShape(widthDp = 599, isCompactHeight = false))
    }

    @Test
    fun `fitting stops at the first row that doesn't fit`() {
        // The box only measures rows up to the first that overflows; the rest can't matter.
        // 3 rows need 90 > 80, so only rows up to the overflow matter: 2 rows need 60.
        assertEquals(2, fitCount(listOf(30, 30, 30), availablePx = 80))
    }

    @Test
    fun `tiny pinch steps after the slop still add up`() {
        val acc = PinchZoomAccumulator(seed = 1.0f, clamp = ::clampPlannerZoom)
        var zoom = 1.0f
        repeat(125) { zoom = acc.apply(1.0008f) }
        assertEquals(1.105f, zoom, 0.002f)
    }

    @Test
    fun `everything that fits is shown`() {
        assertEquals(3, fitCount(listOf(30, 30, 30), availablePx = 90))
    }

    @Test
    fun `one row too many holds back no line for the count`() {
        // 4 rows need 120 > 100; the count sits beside the last row, so 3 rows show.
        assertEquals(3, fitCount(listOf(30, 30, 30, 30), availablePx = 100))
    }

    @Test
    fun `a row taller than the space shows nothing`() {
        assertEquals(0, fitCount(listOf(30, 30), availablePx = 10))
    }

    @Test
    fun `no rows fit no rows`() {
        assertEquals(0, fitCount(emptyList(), availablePx = 100))
    }

    @Test
    fun `zoom is clamped and a missing or broken value means no zoom`() {
        assertEquals(1.6f, clampPlannerZoom(2.0f))
        assertEquals(0.85f, clampPlannerZoom(0.5f))
        assertEquals(1.2f, clampPlannerZoom(1.2f))
        assertEquals(1.0f, clampPlannerZoom(0f))
        assertEquals(1.0f, clampPlannerZoom(-1f))
        assertEquals(1.0f, clampPlannerZoom(Float.NaN))
        assertEquals(1.0f, clampPlannerZoom(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `the pinch accumulator multiplies within one gesture and clamps`() {
        val acc = PinchZoomAccumulator(seed = 1.0f, clamp = ::clampPlannerZoom)
        acc.apply(1.1f)
        acc.apply(1.1f)
        assertEquals(1.21f, acc.apply(1.0f), 0.0001f)
        assertEquals(1.6f, PinchZoomAccumulator(seed = 1.5f, clamp = ::clampPlannerZoom).apply(2f))
        assertEquals(0.85f, PinchZoomAccumulator(seed = 1.0f, clamp = ::clampPlannerZoom).apply(0.1f))
    }

    @Test
    fun `the pinch accumulator keeps to the range its clamp gives`() {
        val clamp = { z: Float -> z.coerceIn(0.5f, 2f) }
        assertEquals(2f, PinchZoomAccumulator(seed = 1.0f, clamp = clamp).apply(3f))
        assertEquals(0.5f, PinchZoomAccumulator(seed = 1.0f, clamp = clamp).apply(0.1f))
        // The seed is clamped too.
        assertEquals(2f, PinchZoomAccumulator(seed = 9f, clamp = clamp).apply(1f))
    }

    @Test
    fun `a day timestamp keeps the current time of day`() {
        val zone = ZoneId.of("America/New_York")
        val now = LocalDate.of(2026, 10, 15).atTime(15, 50).atZone(zone).toInstant().toEpochMilli()
        val ms = plannerDayMs(dayCode = 20261020, nowMs = now, zone = zone)
        val local = Instant.ofEpochMilli(ms).atZone(zone)
        assertEquals(LocalDate.of(2026, 10, 20), local.toLocalDate())
        assertEquals(LocalTime.of(15, 50), local.toLocalTime())
    }

    @Test
    fun `a day timestamp on a clock-change day lands on that day`() {
        val zone = ZoneId.of("America/New_York")
        // 1 Nov 2026 is the US fall-back day; 01:30 happens twice.
        val now = LocalDate.of(2026, 10, 31).atTime(1, 30).atZone(zone).toInstant().toEpochMilli()
        val ms = plannerDayMs(dayCode = 20261101, nowMs = now, zone = zone)
        assertEquals(LocalDate.of(2026, 11, 1), Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())
    }
}
