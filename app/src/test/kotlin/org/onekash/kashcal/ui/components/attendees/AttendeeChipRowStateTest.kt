package org.onekash.kashcal.ui.components.attendees

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pure-logic tests for [AttendeeChipRowState.compute]. No Compose runtime —
 * lives in the unit-test pool. Rendering assertions live in
 * `app/src/androidTest/.../AttendeeChipRowComposeTest.kt`.
 */
@RunWith(RobolectricTestRunner::class)
class AttendeeChipRowStateTest {

    @Test
    fun `empty list yields Empty mode`() {
        val state = AttendeeChipRowState.compute(
            models = emptyList(),
            isCurrentUserOnList = false,
            expanded = false
        )
        assertEquals(AttendeeChipRowMode.Empty, state)
    }

    @Test
    fun `off-list non-empty yields LavenderCount with full count`() {
        val models = (1..5).map { model("u$it@example.test", sortOrder = it - 1) }
        val state = AttendeeChipRowState.compute(
            models = models,
            isCurrentUserOnList = false,
            expanded = false
        )
        assertEquals(AttendeeChipRowMode.LavenderCount(5), state)
    }

    @Test
    fun `on-list with 3 attendees collapsed shows all 3 inline with no hidden`() {
        val models = listOf(
            model("alice@example.test", sortOrder = 0, isYou = true),
            model("bob@example.test", sortOrder = 1),
            model("carol@example.test", sortOrder = 2)
        )
        val state = AttendeeChipRowState.compute(
            models = models,
            isCurrentUserOnList = true,
            expanded = false
        )
        val inline = state as AttendeeChipRowMode.Inline
        assertEquals(3, inline.visible.size)
        assertEquals(0, inline.hiddenCount)
        // You at index 0
        assertTrue(inline.visible[0].isYou)
    }

    @Test
    fun `on-list with 4 attendees collapsed shows 3 wire-first plus N hidden when no You`() {
        val models = (0..3).map {
            model("u$it@example.test", sortOrder = it)
        }
        val state = AttendeeChipRowState.compute(
            models = models,
            isCurrentUserOnList = false, // off list AND non-empty would be lavender; pretend on-list path
            expanded = false
        )
        // off list + non-empty → LavenderCount path is hit; flip the flag to test the on-list case
        // For this case use isCurrentUserOnList=true even though models all have isYou=false
        // to exercise the wire-first slice
        val onListState = AttendeeChipRowState.compute(
            models = models,
            isCurrentUserOnList = true,
            expanded = false
        )
        val inline = onListState as AttendeeChipRowMode.Inline
        assertEquals(3, inline.visible.size)
        assertEquals(1, inline.hiddenCount)
    }

    @Test
    fun `on-list with 5 attendees collapsed and You at sortOrder 4 keeps 4 visible`() {
        // F6 contract: keep You at index 0 + 3 wire-first others; hidden = total - 4
        val models = listOf(
            model("bob@example.test", sortOrder = 0),
            model("carol@example.test", sortOrder = 1),
            model("dave@example.test", sortOrder = 2),
            model("eve@example.test", sortOrder = 3),
            model("alice@example.test", sortOrder = 4, isYou = true)
        )
        val state = AttendeeChipRowState.compute(
            models = models,
            isCurrentUserOnList = true,
            expanded = false
        )
        val inline = state as AttendeeChipRowMode.Inline
        assertEquals(4, inline.visible.size)
        assertTrue(inline.visible[0].isYou)
        assertEquals(1, inline.hiddenCount)
    }

    @Test
    fun `on-list expanded shows all attendees with You at index 0 and hiddenCount 0`() {
        val models = listOf(
            model("bob@example.test", sortOrder = 0),
            model("carol@example.test", sortOrder = 1),
            model("alice@example.test", sortOrder = 2, isYou = true),
            model("dave@example.test", sortOrder = 3),
            model("eve@example.test", sortOrder = 4)
        )
        val state = AttendeeChipRowState.compute(
            models = models,
            isCurrentUserOnList = true,
            expanded = true
        )
        val inline = state as AttendeeChipRowMode.Inline
        assertEquals(5, inline.visible.size)
        assertTrue(inline.visible[0].isYou)
        assertEquals(0, inline.hiddenCount)
    }

    @Test
    fun `on-list with 3 attendees expanded same as collapsed (no hidden)`() {
        val models = listOf(
            model("alice@example.test", sortOrder = 0, isYou = true),
            model("bob@example.test", sortOrder = 1),
            model("carol@example.test", sortOrder = 2)
        )
        val state = AttendeeChipRowState.compute(
            models = models,
            isCurrentUserOnList = true,
            expanded = true
        )
        val inline = state as AttendeeChipRowMode.Inline
        assertEquals(3, inline.visible.size)
        assertEquals(0, inline.hiddenCount)
    }

    @Test
    fun `off-list collapsed yields LavenderCount`() {
        val models = (1..6).map { model("u$it@example.test", sortOrder = it - 1) }
        val state = AttendeeChipRowState.compute(
            models = models,
            isCurrentUserOnList = false,
            expanded = false
        )
        assertEquals(AttendeeChipRowMode.LavenderCount(6), state)
    }

    @Test
    fun `off-list expanded yields Inline with all chips visible and zero hidden`() {
        // v23.7.18: lavender chip tap flips expanded → Inline shows ALL chips
        // (a single tap moves the user from lavender pill to full attendee
        // list; the "Show less" disclosure collapses back to lavender). The
        // +N more two-step disclosure is on-list-only.
        val models = (1..6).map { model("u$it@example.test", sortOrder = it - 1) }
        val state = AttendeeChipRowState.compute(
            models = models,
            isCurrentUserOnList = false,
            expanded = true
        )
        val inline = state as AttendeeChipRowMode.Inline
        assertEquals(6, inline.visible.size)
        assertEquals(0, inline.hiddenCount)
        // No isYou chip in the visible set
        assertTrue(inline.visible.none { it.isYou })
    }

    @Test
    fun `off-list expanded with 2 attendees yields Inline with all visible and zero hidden`() {
        val models = (1..2).map { model("u$it@example.test", sortOrder = it - 1) }
        val state = AttendeeChipRowState.compute(
            models = models,
            isCurrentUserOnList = false,
            expanded = true
        )
        val inline = state as AttendeeChipRowMode.Inline
        assertEquals(2, inline.visible.size)
        assertEquals(0, inline.hiddenCount)
    }

    private fun model(
        addr: String,
        sortOrder: Int,
        isYou: Boolean = false,
        isOrganizer: Boolean = false
    ) = AttendeeUiModel(
        displayName = addr.substringBefore('@'),
        bareAddress = addr,
        status = AttendeeStatus.NeedsAction,
        isYou = isYou,
        isOrganizer = isOrganizer,
        sortOrder = sortOrder
    )
}
