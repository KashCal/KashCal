package org.onekash.kashcal.domain.identity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Tests for [Account.canEditAsOrganizer] — the read-only-mode predicate
 * that decides whether an attendee sees a disabled form sheet.
 */
class EventEditPolicyTest {

    private fun account(
        email: String = "self@example.test",
        addresses: List<String> = listOf("mailto:self@example.test")
    ) = Account(
        id = 1L,
        provider = AccountProvider.CALDAV,
        email = email,
        calendarUserAddresses = addresses
    )

    private fun event(organizer: String?) = Event(
        id = 1L,
        uid = "uid",
        calendarId = 1L,
        title = "Test",
        startTs = 0L,
        endTs = 0L,
        dtstamp = 0L,
        organizerEmail = organizer,
        syncStatus = SyncStatus.SYNCED
    )

    @Test
    fun `null account cannot edit`() {
        val account: Account? = null
        assertFalse(account.canEditAsOrganizer(event(organizer = "boss@example.test")))
    }

    @Test
    fun `null organizer means lone-author event - user can edit`() {
        // Local-only events without an ORGANIZER property: user implicitly owns.
        assertTrue(account().canEditAsOrganizer(event(organizer = null)))
    }

    @Test
    fun `blank organizer treated like null`() {
        assertTrue(account().canEditAsOrganizer(event(organizer = "")))
        assertTrue(account().canEditAsOrganizer(event(organizer = "   ")))
    }

    @Test
    fun `organizer matches account - can edit`() {
        assertTrue(account().canEditAsOrganizer(event(organizer = "self@example.test")))
    }

    @Test
    fun `organizer matches via mailto prefix - can edit`() {
        assertTrue(account().canEditAsOrganizer(event(organizer = "mailto:self@example.test")))
    }

    @Test
    fun `organizer matches account alias - can edit`() {
        val acct = account(addresses = listOf("mailto:self@example.test", "mailto:self@icloud.example"))
        assertTrue(acct.canEditAsOrganizer(event(organizer = "mailto:self@icloud.example")))
    }

    @Test
    fun `organizer is someone else - cannot edit`() {
        assertFalse(account().canEditAsOrganizer(event(organizer = "boss@example.test")))
    }

    @Test
    fun `organizer is someone else with mailto prefix - cannot edit`() {
        assertFalse(account().canEditAsOrganizer(event(organizer = "mailto:boss@example.test")))
    }
}
