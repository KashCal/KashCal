package org.onekash.kashcal.ui.components.weekview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks how a time-grid event block shares its height: the title first, then the location, then
 * the time only when its whole range fits on a line, then tags when a line is still left.
 */
class EventBlockLinesTest {

    private fun lines(
        space: Int,
        titleNeeded: Int,
        locationNeeded: Int = 0,
        timeFits: Boolean = true,
        tagsAllowed: Boolean = false,
        titleLine: Int = 16,
        detailLine: Int = 16,
    ) = allocateEventBlockLines(
        spacePx = space,
        titleLineHeightPx = titleLine,
        titleLinesNeeded = titleNeeded,
        detailLineHeightPx = detailLine,
        locationLinesNeeded = locationNeeded,
        timeFitsWidth = timeFits,
        tagsAllowed = tagsAllowed,
    )

    @Test
    fun `the title takes every line it needs first`() {
        assertEquals(EventBlockLines(titleLines = 5, locationLines = 0, showTime = false, showTags = false), lines(space = 80, titleNeeded = 7, locationNeeded = 2))
    }

    @Test
    fun `the location gets the lines the title leaves`() {
        assertEquals(EventBlockLines(titleLines = 2, locationLines = 3, showTime = false, showTags = false), lines(space = 80, titleNeeded = 2, locationNeeded = 4))
    }

    @Test
    fun `the time comes after the location when a line is left and it fits`() {
        assertEquals(EventBlockLines(titleLines = 1, locationLines = 1, showTime = true, showTags = false), lines(space = 52, titleNeeded = 1, locationNeeded = 1))
    }

    @Test
    fun `a time that doesn't fit the width is left out, not cut`() {
        assertEquals(EventBlockLines(titleLines = 1, locationLines = 0, showTime = false, showTags = false), lines(space = 80, titleNeeded = 1, timeFits = false))
    }

    @Test
    fun `a block shorter than one line still shows one title line`() {
        assertEquals(EventBlockLines(titleLines = 1, locationLines = 0, showTime = false, showTags = false), lines(space = 10, titleNeeded = 3, locationNeeded = 1))
    }

    @Test
    fun `tags show only when allowed and a line is left after the text`() {
        assertEquals(true, lines(space = 64, titleNeeded = 1, timeFits = true, tagsAllowed = true).showTags)
        assertEquals(false, lines(space = 32, titleNeeded = 1, timeFits = true, tagsAllowed = true).showTags)
        assertEquals(false, lines(space = 64, titleNeeded = 1, timeFits = true, tagsAllowed = false).showTags)
    }

    @Test
    fun `each text uses its own line height`() {
        // A 20px title line and 14px detail lines: 2 title lines leave 30px, two location lines.
        assertEquals(
            EventBlockLines(titleLines = 2, locationLines = 2, showTime = false, showTags = false),
            lines(space = 70, titleNeeded = 2, locationNeeded = 3, titleLine = 20, detailLine = 14),
        )
    }
}
