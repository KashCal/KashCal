package org.onekash.kashcal.ui.components.attendees

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the AttendeeListSheet's grouping/sorting/filtering
 * helper. No Compose, no Robolectric needed.
 */
class AttendeeListSheetStateTest {

    @Test
    fun `empty input yields no sections`() {
        assertEquals(emptyList<AttendeeListSection>(), buildAttendeeListSections(emptyList()))
    }

    @Test
    fun `sections appear in canonical display order`() {
        val attendees = listOf(
            row("d@example.test", AttendeeStatus.Declined, sortOrder = 0),
            row("a@example.test", AttendeeStatus.Accepted, sortOrder = 1),
            row("p@example.test", AttendeeStatus.NeedsAction, sortOrder = 2),
            row("t@example.test", AttendeeStatus.Tentative, sortOrder = 3),
        )
        val sections = buildAttendeeListSections(attendees)
        assertEquals(
            listOf(
                AttendeeStatus.Accepted,
                AttendeeStatus.Tentative,
                AttendeeStatus.NeedsAction,
                AttendeeStatus.Declined,
            ),
            sections.map { it.status },
        )
    }

    @Test
    fun `you is pinned to top of its section`() {
        val attendees = listOf(
            row("alice@example.test", AttendeeStatus.Accepted, sortOrder = 0),
            row("bob@example.test", AttendeeStatus.Accepted, sortOrder = 1),
            row("you@example.test", AttendeeStatus.Accepted, sortOrder = 5, isYou = true),
            row("carol@example.test", AttendeeStatus.Accepted, sortOrder = 2),
        )
        val sections = buildAttendeeListSections(attendees)
        val going = sections.single { it.status == AttendeeStatus.Accepted }
        assertEquals("you@example.test", going.rows.first().bareAddress)
        assertEquals(
            listOf("alice@example.test", "bob@example.test", "carol@example.test"),
            going.rows.drop(1).map { it.bareAddress },
        )
    }

    @Test
    fun `non-you rows sort by wire-order sortOrder`() {
        val attendees = listOf(
            row("c@example.test", AttendeeStatus.Tentative, sortOrder = 2),
            row("a@example.test", AttendeeStatus.Tentative, sortOrder = 0),
            row("b@example.test", AttendeeStatus.Tentative, sortOrder = 1),
        )
        val maybe = buildAttendeeListSections(attendees).single()
        assertEquals(
            listOf("a@example.test", "b@example.test", "c@example.test"),
            maybe.rows.map { it.bareAddress },
        )
    }

    @Test
    fun `query filters by displayName case-insensitively`() {
        val attendees = listOf(
            row("alice@example.test", AttendeeStatus.Accepted, displayName = "Alice Wong", sortOrder = 0),
            row("bob@example.test", AttendeeStatus.Accepted, displayName = "Bob Smith", sortOrder = 1),
        )
        val sections = buildAttendeeListSections(attendees, query = "ALI")
        val rows = sections.flatMap { it.rows }
        assertEquals(1, rows.size)
        assertEquals("alice@example.test", rows.single().bareAddress)
    }

    @Test
    fun `query filters by bareAddress`() {
        val attendees = listOf(
            row("alice@oneco.test", AttendeeStatus.Accepted, displayName = "Alice", sortOrder = 0),
            row("bob@otherco.test", AttendeeStatus.Accepted, displayName = "Bob", sortOrder = 1),
        )
        val rows = buildAttendeeListSections(attendees, query = "oneco").flatMap { it.rows }
        assertEquals(1, rows.size)
        assertEquals("alice@oneco.test", rows.single().bareAddress)
    }

    @Test
    fun `empty section is dropped after filter empties its group`() {
        val attendees = listOf(
            row("alice@example.test", AttendeeStatus.Accepted, sortOrder = 0),
            row("bob@example.test", AttendeeStatus.Declined, sortOrder = 1),
        )
        val sections = buildAttendeeListSections(attendees, query = "alice")
        assertEquals(1, sections.size)
        assertEquals(AttendeeStatus.Accepted, sections.single().status)
    }

    @Test
    fun `whitespace-only query is treated as empty`() {
        val attendees = listOf(
            row("alice@example.test", AttendeeStatus.Accepted, sortOrder = 0),
            row("bob@example.test", AttendeeStatus.Tentative, sortOrder = 1),
        )
        val unfiltered = buildAttendeeListSections(attendees)
        val filtered = buildAttendeeListSections(attendees, query = "   ")
        assertEquals(unfiltered, filtered)
    }

    @Test
    fun `query with no matches yields no sections`() {
        val attendees = listOf(
            row("alice@example.test", AttendeeStatus.Accepted, sortOrder = 0),
        )
        assertTrue(buildAttendeeListSections(attendees, query = "zzz").isEmpty())
    }

    @Test
    fun `delegated section appears after declined`() {
        val attendees = listOf(
            row("d@example.test", AttendeeStatus.Delegated, sortOrder = 0),
            row("x@example.test", AttendeeStatus.Declined, sortOrder = 1),
            row("a@example.test", AttendeeStatus.Accepted, sortOrder = 2),
        )
        val statuses = buildAttendeeListSections(attendees).map { it.status }
        assertEquals(
            listOf(AttendeeStatus.Accepted, AttendeeStatus.Declined, AttendeeStatus.Delegated),
            statuses,
        )
    }

    private fun row(
        addr: String,
        status: AttendeeStatus,
        sortOrder: Int,
        isYou: Boolean = false,
        isOrganizer: Boolean = false,
        displayName: String = addr.substringBefore('@'),
    ) = AttendeeUiModel(
        displayName = displayName,
        bareAddress = addr,
        status = status,
        isYou = isYou,
        isOrganizer = isOrganizer,
        sortOrder = sortOrder,
    )
}

class AvatarInitialsTest {
    @Test fun `single word picks first letter uppercase`() = assertEquals("A", avatarInitials("alice"))
    @Test fun `two words pick first letter of each`() = assertEquals("AC", avatarInitials("Alice Chen"))
    @Test fun `three words pick first and last initial`() = assertEquals("AH", avatarInitials("Alice Chen Hernandez"))
    @Test fun `whitespace-only falls back to question mark`() = assertEquals("?", avatarInitials("   "))
    @Test fun `empty string falls back to question mark`() = assertEquals("?", avatarInitials(""))
    @Test fun `lowercase first letter is uppercased`() = assertEquals("M", avatarInitials("maria"))
}
