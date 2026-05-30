package org.onekash.kashcal.ui.components.attendees

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for [formatSummaryLine] — the priority machine that
 * picks which [SummaryLine] variant to render. Compose-side resolution
 * lives in [composeSummaryLine] and is exercised by render tests.
 */
class InviteesBlockSummaryLineTest {

    @Test
    fun `empty list yields Empty`() {
        val line = formatSummaryLine(emptyList(), isCurrentUserOnList = true, isCurrentUserOrganizer = false)
        assertEquals(SummaryLine.Empty, line)
    }

    @Test
    fun `you alone yields YouAlone`() {
        val line = formatSummaryLine(
            listOf(you()),
            isCurrentUserOnList = true,
            isCurrentUserOrganizer = false,
        )
        assertEquals(SummaryLine.YouAlone, line)
    }

    @Test
    fun `you organizing alone yields YouAlone`() {
        // Organizer with only their own attendee row (rare — usually the
        // organizer is synthesized off-list, but this branch fires when
        // the user added themselves explicitly).
        val line = formatSummaryLine(
            listOf(you().copy(isOrganizer = true)),
            isCurrentUserOnList = true,
            isCurrentUserOrganizer = true,
        )
        assertEquals(SummaryLine.YouAlone, line)
    }

    @Test
    fun `you organizing with others yields YouOrganizing(others=N)`() {
        val line = formatSummaryLine(
            listOf(you().copy(isOrganizer = true), other("a"), other("b"), other("c")),
            isCurrentUserOnList = true,
            isCurrentUserOrganizer = true,
        )
        assertEquals(SummaryLine.YouOrganizing(others = 3), line)
    }

    @Test
    fun `off-list with no organizer yields OffListTotal(total)`() {
        val line = formatSummaryLine(
            listOf(other("a"), other("b"), other("c")),
            isCurrentUserOnList = false,
            isCurrentUserOrganizer = false,
        )
        assertEquals(SummaryLine.OffListTotal(total = 3), line)
    }

    @Test
    fun `off-list with organizer yields OffListWithHost(total, name)`() {
        val line = formatSummaryLine(
            listOf(host("Maria Chen"), other("a"), other("b")),
            isCurrentUserOnList = false,
            isCurrentUserOrganizer = false,
        )
        assertEquals(SummaryLine.OffListWithHost(total = 3, organizerName = "Maria Chen"), line)
    }

    @Test
    fun `on-list with organizer-other and just you yields OrganizerPlusYou`() {
        val line = formatSummaryLine(
            listOf(host("Maria Chen"), you()),
            isCurrentUserOnList = true,
            isCurrentUserOrganizer = false,
        )
        assertEquals(SummaryLine.OrganizerPlusYou(organizerName = "Maria Chen"), line)
    }

    @Test
    fun `on-list with organizer-other and others yields OrganizerPlusYouPlusN`() {
        val line = formatSummaryLine(
            listOf(host("Maria Chen"), you(), other("a"), other("b")),
            isCurrentUserOnList = true,
            isCurrentUserOrganizer = false,
        )
        assertEquals(SummaryLine.OrganizerPlusYouPlusN("Maria Chen", others = 2), line)
    }

    @Test
    fun `on-list with no organizer surfaced and just you yields YouAlone`() {
        val line = formatSummaryLine(
            listOf(you()),
            isCurrentUserOnList = true,
            isCurrentUserOrganizer = false,
        )
        assertEquals(SummaryLine.YouAlone, line)
    }

    @Test
    fun `on-list with no organizer surfaced and others yields YouPlusN`() {
        val line = formatSummaryLine(
            listOf(you(), other("a"), other("b"), other("c"), other("d")),
            isCurrentUserOnList = true,
            isCurrentUserOrganizer = false,
        )
        assertEquals(SummaryLine.YouPlusN(others = 4), line)
    }

    @Test
    fun `on-list-but-no-you with organizer yields OrganizerOtherMore`() {
        // Edge case: caller said the user is on the list, but the
        // attendee list has no isYou=true row (stale projection).
        val line = formatSummaryLine(
            listOf(host("Maria Chen"), other("a"), other("b")),
            isCurrentUserOnList = true,
            isCurrentUserOrganizer = false,
        )
        assertEquals(SummaryLine.OrganizerOtherMore("Maria Chen", others = 2), line)
    }

    @Test
    fun `on-list-but-no-you without organizer falls back to OffListTotal`() {
        val line = formatSummaryLine(
            listOf(other("a"), other("b")),
            isCurrentUserOnList = true,
            isCurrentUserOrganizer = false,
        )
        assertEquals(SummaryLine.OffListTotal(total = 2), line)
    }

    @Test
    fun `organizer flag uses isOrganizer-not-isYou for organizer detection`() {
        // The user is on the list AS the organizer (rare; usually the
        // organizer chip is synthesized off-list). isCurrentUserOrganizer
        // = true forces YouOrganizing, even though attendees also has a
        // separate isOrganizer chip we'd otherwise prefer for
        // OrganizerPlusYou.
        val line = formatSummaryLine(
            listOf(you().copy(isOrganizer = true), other("a"), other("b")),
            isCurrentUserOnList = true,
            isCurrentUserOrganizer = true,
        )
        assertEquals(SummaryLine.YouOrganizing(others = 2), line)
    }

    private fun you(): AttendeeUiModel = AttendeeUiModel(
        displayName = "You",
        bareAddress = "you@example.test",
        status = AttendeeStatus.Accepted,
        isYou = true,
        isOrganizer = false,
        sortOrder = 0,
    )

    private fun host(name: String): AttendeeUiModel = AttendeeUiModel(
        displayName = name,
        bareAddress = "${name.replace(' ', '.').lowercase()}@example.test",
        status = AttendeeStatus.Accepted,
        isYou = false,
        isOrganizer = true,
        sortOrder = 0,
    )

    private fun other(name: String): AttendeeUiModel = AttendeeUiModel(
        displayName = name,
        bareAddress = "$name@example.test",
        status = AttendeeStatus.NeedsAction,
        isYou = false,
        isOrganizer = false,
        sortOrder = 1,
    )
}
