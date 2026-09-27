package org.onekash.kashcal.ui.components.attendees

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests [formatSummaryLine], the first-match-wins choice of the [SummaryLine] variant to render.
 * The Compose-side resolution in [composeSummaryLine] isn't tested here.
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
        // The user's You row is also the organizer, whether the user's own attendee row or the
        // chip fromRoom synthesizes, and no one else is listed.
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
        // The caller says the user is on the list, but no row has isYou true (for example a
        // stale projection).
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
        // The You row carries isOrganizer. The organizer lookup skips You rows, and with
        // isCurrentUserOrganizer true the first branch gives YouOrganizing, not
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
