package org.onekash.kashcal.ui.components.attendees

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for the read-side UI display model. Pure logic — no Compose
 * runtime. Pairs with [AttendeeChipRowComposeTest] (in androidTest/) which
 * covers the rendering side.
 */
@RunWith(RobolectricTestRunner::class)
class AttendeeUiModelTest {

    // ===== AttendeeStatus.fromPartstat — partstat translation =====

    @Test
    fun `fromPartstat ACCEPTED returns Accepted`() {
        assertEquals(AttendeeStatus.Accepted, AttendeeStatus.fromPartstat("ACCEPTED"))
    }

    @Test
    fun `fromPartstat DECLINED returns Declined`() {
        assertEquals(AttendeeStatus.Declined, AttendeeStatus.fromPartstat("DECLINED"))
    }

    @Test
    fun `fromPartstat TENTATIVE returns Tentative`() {
        assertEquals(AttendeeStatus.Tentative, AttendeeStatus.fromPartstat("TENTATIVE"))
    }

    @Test
    fun `fromPartstat DELEGATED returns Delegated`() {
        assertEquals(AttendeeStatus.Delegated, AttendeeStatus.fromPartstat("DELEGATED"))
    }

    @Test
    fun `fromPartstat NEEDS-ACTION returns NeedsAction`() {
        assertEquals(AttendeeStatus.NeedsAction, AttendeeStatus.fromPartstat("NEEDS-ACTION"))
    }

    @Test
    fun `fromPartstat null returns NeedsAction`() {
        assertEquals(AttendeeStatus.NeedsAction, AttendeeStatus.fromPartstat(null))
    }

    @Test
    fun `fromPartstat unknown x-extension returns NeedsAction`() {
        // TEXT-lenient default per RFC 5545 — servers may emit X-vendor extensions
        assertEquals(AttendeeStatus.NeedsAction, AttendeeStatus.fromPartstat("X-VENDOR-CUSTOM"))
    }

    // ===== AttendeeUiModel.fromRoom — base mapping =====

    @Test
    fun `fromRoom maps mailto address with CN to displayName from CN`() {
        val attendee = att(displayName = "Alice Smith", address = "mailto:alice@example.com")
        val models = AttendeeUiModel.fromRoom(
            attendees = listOf(attendee),
            currentAccount = null,
            organizerAddress = null
        )
        assertEquals(1, models.size)
        assertEquals("Alice Smith", models[0].displayName)
        assertEquals("alice@example.com", models[0].bareAddress)
        assertEquals(AttendeeStatus.NeedsAction, models[0].status)
        assertFalse(models[0].isYou)
        assertFalse(models[0].isOrganizer)
    }

    @Test
    fun `displayName fallback uses local-part of address when CN is null`() {
        val attendee = att(displayName = null, address = "mailto:bob.smith@example.com")
        val models = AttendeeUiModel.fromRoom(listOf(attendee), null, null)
        assertEquals("bob.smith", models[0].displayName)
    }

    @Test
    fun `displayName fallback uses local-part of address when CN is blank`() {
        val attendee = att(displayName = "   ", address = "mailto:carol@example.com")
        val models = AttendeeUiModel.fromRoom(listOf(attendee), null, null)
        assertEquals("carol", models[0].displayName)
    }

    @Test
    fun `displayName fallback uses raw address when no at sign and no CN`() {
        val attendee = att(displayName = null, address = "urn:uuid:1234-abcd")
        val models = AttendeeUiModel.fromRoom(listOf(attendee), null, null)
        assertEquals("urn:uuid:1234-abcd", models[0].displayName)
    }

    @Test
    fun `bareAddress strips mailto prefix`() {
        val attendee = att(address = "MAILTO:Alice@Example.COM")
        val models = AttendeeUiModel.fromRoom(listOf(attendee), null, null)
        // Lowercased per AddressNormalizer canonical
        assertEquals("alice@example.com", models[0].bareAddress)
    }

    // ===== Organizer detection (D5) =====

    @Test
    fun `isOrganizer true when address canonical-matches event organizer`() {
        val attendees = listOf(
            att(address = "mailto:alice@example.com"),
            att(address = "mailto:bob@example.com")
        )
        val models = AttendeeUiModel.fromRoom(
            attendees = attendees,
            currentAccount = null,
            organizerAddress = "MAILTO:Alice@Example.com"
        )
        assertTrue(models[0].isOrganizer)
        assertFalse(models[1].isOrganizer)
    }

    @Test
    fun `isOrganizer false when organizerAddress is null`() {
        val attendees = listOf(att(address = "mailto:alice@example.com"))
        val models = AttendeeUiModel.fromRoom(attendees, null, null)
        assertFalse(models[0].isOrganizer)
    }

    // ===== isYou + matchesAttendee identity =====

    @Test
    fun `isYou true when attendee address matches account calendarUserAddresses`() {
        val account = acc(
            email = "alice@example.com",
            calendarUserAddresses = listOf("mailto:alice@example.com")
        )
        val attendees = listOf(
            att(address = "mailto:alice@example.com"),
            att(address = "mailto:bob@example.com")
        )
        val models = AttendeeUiModel.fromRoom(attendees, account, null)
        assertTrue(models[0].isYou)
        assertFalse(models[1].isYou)
    }

    // ===== isCurrentUserOnList =====

    @Test
    fun `isCurrentUserOnList true when account matches any attendee`() {
        val account = acc(
            email = "alice@example.com",
            calendarUserAddresses = listOf("mailto:alice@example.com")
        )
        val attendees = listOf(
            att(address = "mailto:bob@example.com"),
            att(address = "mailto:alice@example.com"),
            att(address = "mailto:carol@example.com")
        )
        assertTrue(AttendeeUiModel.isCurrentUserOnList(attendees, account))
    }

    @Test
    fun `isCurrentUserOnList false when account is on no attendee row`() {
        val account = acc(
            email = "alice@example.com",
            calendarUserAddresses = listOf("mailto:alice@example.com")
        )
        val attendees = listOf(
            att(address = "mailto:bob@example.com"),
            att(address = "mailto:carol@example.com")
        )
        assertFalse(AttendeeUiModel.isCurrentUserOnList(attendees, account))
    }

    // ===== Sort: You at index 0 (≤3 attendees, all visible) =====

    @Test
    fun `sortForCollapsedView promotes You to index 0 when total is 3`() {
        val account = acc(
            email = "alice@example.com",
            calendarUserAddresses = listOf("mailto:alice@example.com")
        )
        val attendees = listOf(
            att(address = "mailto:bob@example.com", sortOrder = 0),
            att(address = "mailto:alice@example.com", sortOrder = 1),
            att(address = "mailto:carol@example.com", sortOrder = 2)
        )
        val models = AttendeeUiModel.fromRoom(attendees, account, null)
        val sorted = AttendeeUiModel.sortForCollapsedView(models, expanded = false)
        assertEquals("alice@example.com", sorted[0].bareAddress)
        // Bob and Carol retain sortOrder
        assertEquals("bob@example.com", sorted[1].bareAddress)
        assertEquals("carol@example.com", sorted[2].bareAddress)
    }

    @Test
    fun `sortForCollapsedView preserves sortOrder when no You exists`() {
        val attendees = listOf(
            att(address = "mailto:bob@example.com", sortOrder = 0),
            att(address = "mailto:carol@example.com", sortOrder = 1)
        )
        val models = AttendeeUiModel.fromRoom(attendees, currentAccount = null, organizerAddress = null)
        val sorted = AttendeeUiModel.sortForCollapsedView(models, expanded = false)
        assertEquals("bob@example.com", sorted[0].bareAddress)
        assertEquals("carol@example.com", sorted[1].bareAddress)
    }

    // ===== F6 fix: You at index 0 when total ≥4 keeps 4 chips visible =====

    @Test
    fun `sortForCollapsedView with 5 attendees and You at sortOrder 4 keeps You plus 3 wire-first attendees`() {
        // F6: when "You" would otherwise be hidden, render 4 chips (You + first 3
        // by sortOrder, excluding You). Matches Google Calendar parity.
        val account = acc(
            email = "alice@example.com",
            calendarUserAddresses = listOf("mailto:alice@example.com")
        )
        val attendees = listOf(
            att(address = "mailto:bob@example.com", sortOrder = 0),
            att(address = "mailto:carol@example.com", sortOrder = 1),
            att(address = "mailto:dave@example.com", sortOrder = 2),
            att(address = "mailto:eve@example.com", sortOrder = 3),
            att(address = "mailto:alice@example.com", sortOrder = 4)
        )
        val models = AttendeeUiModel.fromRoom(attendees, account, null)
        val sorted = AttendeeUiModel.sortForCollapsedView(models, expanded = false)
        // F6 contract: collapsed view returns at most 4 entries when You was
        // hidden in the wire-order top-3, of which index 0 is You.
        assertEquals(4, sorted.size)
        assertEquals("alice@example.com", sorted[0].bareAddress)
        assertEquals("bob@example.com", sorted[1].bareAddress)
        assertEquals("carol@example.com", sorted[2].bareAddress)
        assertEquals("dave@example.com", sorted[3].bareAddress)
    }

    @Test
    fun `sortForCollapsedView with 5 attendees and no You returns first 3 by sortOrder`() {
        val attendees = listOf(
            att(address = "mailto:bob@example.com", sortOrder = 0),
            att(address = "mailto:carol@example.com", sortOrder = 1),
            att(address = "mailto:dave@example.com", sortOrder = 2),
            att(address = "mailto:eve@example.com", sortOrder = 3),
            att(address = "mailto:frank@example.com", sortOrder = 4)
        )
        val models = AttendeeUiModel.fromRoom(attendees, currentAccount = null, organizerAddress = null)
        val sorted = AttendeeUiModel.sortForCollapsedView(models, expanded = false)
        assertEquals(3, sorted.size)
        assertEquals("bob@example.com", sorted[0].bareAddress)
        assertEquals("carol@example.com", sorted[1].bareAddress)
        assertEquals("dave@example.com", sorted[2].bareAddress)
    }

    @Test
    fun `sortForCollapsedView when expanded returns all attendees regardless of count`() {
        val account = acc(
            email = "alice@example.com",
            calendarUserAddresses = listOf("mailto:alice@example.com")
        )
        val attendees = listOf(
            att(address = "mailto:bob@example.com", sortOrder = 0),
            att(address = "mailto:carol@example.com", sortOrder = 1),
            att(address = "mailto:dave@example.com", sortOrder = 2),
            att(address = "mailto:alice@example.com", sortOrder = 3)
        )
        val models = AttendeeUiModel.fromRoom(attendees, account, null)
        val sorted = AttendeeUiModel.sortForCollapsedView(models, expanded = true)
        assertEquals(4, sorted.size)
        // Even when expanded, You stays at index 0
        assertEquals("alice@example.com", sorted[0].bareAddress)
    }

    // ===== F5: identity edge cases =====

    @Test
    fun `fromRoom with null account marks every attendee isYou false`() {
        val attendees = listOf(
            att(address = "mailto:alice@example.com"),
            att(address = "mailto:bob@example.com")
        )
        val models = AttendeeUiModel.fromRoom(attendees, currentAccount = null, organizerAddress = null)
        assertTrue(models.all { !it.isYou })
    }

    @Test
    fun `isCurrentUserOnList false when currentAccount is null`() {
        val attendees = listOf(att(address = "mailto:alice@example.com"))
        assertFalse(AttendeeUiModel.isCurrentUserOnList(attendees, currentAccount = null))
    }

    @Test
    fun `fromRoom with non-email login and empty calendarUserAddresses marks isYou false`() {
        // F5 edge case 2 — Nextcloud "alice" username, server returned no addresses
        val account = acc(email = "alice", calendarUserAddresses = emptyList())
        val attendees = listOf(att(address = "mailto:alice@nextcloud.example"))
        val models = AttendeeUiModel.fromRoom(attendees, account, null)
        assertFalse(models[0].isYou)
        assertFalse(AttendeeUiModel.isCurrentUserOnList(attendees, account))
    }

    @Test
    fun `fromRoom uses email fallback when calendarUserAddresses empty but email is email-shaped`() {
        // Pre-A2.0 accounts didn't have calendarUserAddresses populated; matchesAttendee falls
        // back to email when email shape parses.
        val account = acc(email = "alice@example.com", calendarUserAddresses = emptyList())
        val attendees = listOf(att(address = "mailto:alice@example.com"))
        val models = AttendeeUiModel.fromRoom(attendees, account, null)
        assertTrue(models[0].isYou)
    }

    // ===== Bug 1: organizer-self synthesis =====

    @Test
    fun `fromRoom synthesizes You+Organizer chip when account matches organizer but not on attendee list`() {
        val account = acc(
            email = "alice@example.com",
            calendarUserAddresses = listOf("mailto:alice@example.com")
        )
        val attendees = listOf(
            att(address = "mailto:bob@example.com", sortOrder = 0),
            att(address = "mailto:carol@example.com", sortOrder = 1)
        )
        val models = AttendeeUiModel.fromRoom(
            attendees = attendees,
            currentAccount = account,
            organizerAddress = "mailto:alice@example.com"
        )
        assertEquals(3, models.size)
        // Synthesized organizer chip is in the result
        val you = models.firstOrNull { it.isYou }
        assertTrue(you != null && you.isOrganizer)
        assertEquals("alice@example.com", you?.bareAddress)
        assertEquals(AttendeeStatus.Accepted, you?.status)
    }

    @Test
    fun `synthesized organizer chip uses account displayName when present`() {
        val account = Account(
            id = 1,
            provider = AccountProvider.LOCAL,
            email = "alice@example.com",
            displayName = "Alice Anderson",
            calendarUserAddresses = listOf("mailto:alice@example.com")
        )
        val attendees = listOf(att(address = "mailto:bob@example.com", sortOrder = 0))
        val models = AttendeeUiModel.fromRoom(
            attendees = attendees,
            currentAccount = account,
            organizerAddress = "mailto:alice@example.com"
        )
        val you = models.firstOrNull { it.isYou }!!
        assertEquals("Alice Anderson", you.displayName)
    }

    @Test
    fun `synthesized organizer chip falls back to local-part when account displayName is null or blank`() {
        val account = acc(
            email = "alice@example.com",
            calendarUserAddresses = listOf("mailto:alice@example.com")
        )
        val attendees = listOf(att(address = "mailto:bob@example.com", sortOrder = 0))
        val models = AttendeeUiModel.fromRoom(
            attendees = attendees,
            currentAccount = account,
            organizerAddress = "mailto:alice@example.com"
        )
        val you = models.firstOrNull { it.isYou }!!
        assertEquals("alice", you.displayName)
    }

    @Test
    fun `fromRoom does NOT synthesize when organizer is already on attendee list`() {
        val account = acc(
            email = "alice@example.com",
            calendarUserAddresses = listOf("mailto:alice@example.com")
        )
        val attendees = listOf(
            att(address = "mailto:alice@example.com", sortOrder = 0),
            att(address = "mailto:bob@example.com", sortOrder = 1)
        )
        val models = AttendeeUiModel.fromRoom(
            attendees = attendees,
            currentAccount = account,
            organizerAddress = "mailto:alice@example.com"
        )
        assertEquals(2, models.size)
        val alice = models.first { it.bareAddress == "alice@example.com" }
        assertTrue(alice.isYou)
        assertTrue(alice.isOrganizer)
    }

    @Test
    fun `fromRoom does NOT synthesize when account does not match organizer`() {
        val account = acc(
            email = "alice@example.com",
            calendarUserAddresses = listOf("mailto:alice@example.com")
        )
        val attendees = listOf(att(address = "mailto:bob@example.com", sortOrder = 0))
        val models = AttendeeUiModel.fromRoom(
            attendees = attendees,
            currentAccount = account,
            organizerAddress = "mailto:carol@example.com" // different organizer
        )
        assertEquals(1, models.size)
        assertFalse(models[0].isYou)
    }

    @Test
    fun `fromRoom does NOT synthesize when organizerAddress is null`() {
        val account = acc(
            email = "alice@example.com",
            calendarUserAddresses = listOf("mailto:alice@example.com")
        )
        val attendees = listOf(att(address = "mailto:bob@example.com", sortOrder = 0))
        val models = AttendeeUiModel.fromRoom(
            attendees = attendees,
            currentAccount = account,
            organizerAddress = null
        )
        assertEquals(1, models.size)
        assertFalse(models[0].isYou)
    }

    @Test
    fun `synthesized organizer chip lands at index 0 after sortForCollapsedView`() {
        val account = acc(
            email = "alice@example.com",
            calendarUserAddresses = listOf("mailto:alice@example.com")
        )
        val attendees = listOf(
            att(address = "mailto:bob@example.com", sortOrder = 0),
            att(address = "mailto:carol@example.com", sortOrder = 1)
        )
        val models = AttendeeUiModel.fromRoom(
            attendees = attendees,
            currentAccount = account,
            organizerAddress = "mailto:alice@example.com"
        )
        val sorted = AttendeeUiModel.sortForCollapsedView(models, expanded = false)
        assertEquals("alice@example.com", sorted[0].bareAddress)
        assertTrue(sorted[0].isYou)
        assertTrue(sorted[0].isOrganizer)
    }

    @Test
    fun `isCurrentUserOnList returns true when account matches organizer even if attendees empty`() {
        val account = acc(
            email = "alice@example.com",
            calendarUserAddresses = listOf("mailto:alice@example.com")
        )
        assertTrue(
            AttendeeUiModel.isCurrentUserOnList(
                attendees = emptyList(),
                currentAccount = account,
                organizerAddress = "mailto:alice@example.com"
            )
        )
    }

    @Test
    fun `isCurrentUserOnList returns true when account matches organizer with attendees not matching`() {
        val account = acc(
            email = "alice@example.com",
            calendarUserAddresses = listOf("mailto:alice@example.com")
        )
        val attendees = listOf(att(address = "mailto:bob@example.com", sortOrder = 0))
        assertTrue(
            AttendeeUiModel.isCurrentUserOnList(
                attendees = attendees,
                currentAccount = account,
                organizerAddress = "mailto:alice@example.com"
            )
        )
    }

    // F2 — F6 collapsed-view rule still produces 4 chips when synthesis is present.
    @Test
    fun `sortForCollapsedView with synthesized organizer plus 4 real attendees keeps 4 chips with You at index 0`() {
        val account = acc(
            email = "alice@example.com",
            calendarUserAddresses = listOf("mailto:alice@example.com")
        )
        val attendees = listOf(
            att(address = "mailto:bob@example.com", sortOrder = 0),
            att(address = "mailto:carol@example.com", sortOrder = 1),
            att(address = "mailto:dave@example.com", sortOrder = 2),
            att(address = "mailto:eve@example.com", sortOrder = 3)
        )
        val models = AttendeeUiModel.fromRoom(
            attendees = attendees,
            currentAccount = account,
            organizerAddress = "mailto:alice@example.com"
        )
        // 4 real + 1 synthesized = 5 total
        assertEquals(5, models.size)
        val sorted = AttendeeUiModel.sortForCollapsedView(models, expanded = false)
        // F6 contract: 4 chips in collapsed view
        assertEquals(4, sorted.size)
        // Index 0 is the synthesized You+Organizer chip
        assertTrue(sorted[0].isYou && sorted[0].isOrganizer)
        assertEquals("alice@example.com", sorted[0].bareAddress)
        // Indices 1-3 are the first 3 real attendees by sortOrder
        assertEquals("bob@example.com", sorted[1].bareAddress)
        assertEquals("carol@example.com", sorted[2].bareAddress)
        assertEquals("dave@example.com", sorted[3].bareAddress)
        // Eve at sortOrder=3 is hidden
        assertTrue(sorted.none { it.bareAddress == "eve@example.com" })
    }

    // F2 (cont.) — confirm AttendeeChipRowState.compute reports hiddenCount=1 for the same shape.
    @Test
    fun `compute returns Inline with hiddenCount=1 when synthesized organizer plus 4 real attendees collapsed`() {
        val account = acc(
            email = "alice@example.com",
            calendarUserAddresses = listOf("mailto:alice@example.com")
        )
        val attendees = listOf(
            att(address = "mailto:bob@example.com", sortOrder = 0),
            att(address = "mailto:carol@example.com", sortOrder = 1),
            att(address = "mailto:dave@example.com", sortOrder = 2),
            att(address = "mailto:eve@example.com", sortOrder = 3)
        )
        val models = AttendeeUiModel.fromRoom(attendees, account, "mailto:alice@example.com")
        val isOnList = AttendeeUiModel.isCurrentUserOnList(
            attendees, account, "mailto:alice@example.com"
        )
        val mode = AttendeeChipRowState.compute(
            models = models,
            isCurrentUserOnList = isOnList,
            expanded = false
        )
        val inline = mode as AttendeeChipRowMode.Inline
        assertEquals(4, inline.visible.size)
        assertEquals(1, inline.hiddenCount)
    }

    // F5 — multi-alias edge case.
    @Test
    fun `fromRoom does NOT synthesize when account has multiple aliases and one alias is on attendee list`() {
        val account = acc(
            email = "alice@me.com",
            calendarUserAddresses = listOf("mailto:alice@me.com", "mailto:alice@icloud.com")
        )
        // Organizer = me.com alias; attendees include the icloud.com alias — different
        // but both belong to the same account. Synthesis must skip because the user
        // is already represented via the icloud.com attendee row.
        val attendees = listOf(
            att(address = "mailto:bob@example.com", sortOrder = 0),
            att(address = "mailto:alice@icloud.com", sortOrder = 1)
        )
        val models = AttendeeUiModel.fromRoom(
            attendees = attendees,
            currentAccount = account,
            organizerAddress = "mailto:alice@me.com"
        )
        // No synthesized chip — only the 2 real attendees.
        assertEquals(2, models.size)
        val you = models.first { it.bareAddress == "alice@icloud.com" }
        assertTrue(you.isYou)
        // alice@icloud.com is NOT the organizer (me.com is), so this attendee is just the user, not the organizer
        assertFalse(you.isOrganizer)
    }

    // R1 — canonical-case test.
    @Test
    fun `synthesized organizer chip canonicalizes uppercase MAILTO and mixed-case email`() {
        val account = acc(
            email = "alice@example.com",
            calendarUserAddresses = listOf("mailto:alice@example.com")
        )
        val attendees = listOf(att(address = "mailto:bob@example.com", sortOrder = 0))
        val models = AttendeeUiModel.fromRoom(
            attendees = attendees,
            currentAccount = account,
            organizerAddress = "MAILTO:Alice@Example.COM"
        )
        val you = models.first { it.isYou }
        // canonical lowercase form, mailto prefix stripped (matches AddressNormalizer)
        assertEquals("alice@example.com", you.bareAddress)
    }

    @Test
    fun `isCurrentUserOnList returns false when neither attendees nor organizer match`() {
        val account = acc(
            email = "alice@example.com",
            calendarUserAddresses = listOf("mailto:alice@example.com")
        )
        val attendees = listOf(att(address = "mailto:bob@example.com", sortOrder = 0))
        assertFalse(
            AttendeeUiModel.isCurrentUserOnList(
                attendees = attendees,
                currentAccount = account,
                organizerAddress = "mailto:carol@example.com"
            )
        )
    }

    // ===== Helpers =====

    private fun att(
        id: Long = 0,
        eventId: Long = 1,
        address: String,
        displayName: String? = null,
        partstat: String? = null,
        sortOrder: Int = 0
    ) = Attendee(
        id = id,
        eventId = eventId,
        address = address,
        displayName = displayName,
        partstat = partstat,
        sortOrder = sortOrder
    )

    private fun acc(
        id: Long = 1,
        email: String,
        calendarUserAddresses: List<String>
    ) = Account(
        id = id,
        provider = AccountProvider.LOCAL,
        email = email,
        calendarUserAddresses = calendarUserAddresses
    )
}
