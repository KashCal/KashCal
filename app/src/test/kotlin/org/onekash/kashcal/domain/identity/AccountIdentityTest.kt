package org.onekash.kashcal.domain.identity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Tests for [Account.matchesAttendee] — the identity-match helper that
 * answers "is this attendee me?" across iCloud aliases, non-mailto
 * URI forms, and the fallback path when `calendar_user_addresses`
 * isn't populated yet.
 */
class AccountIdentityTest {

    private fun account(
        email: String = "alice@icloud.com",
        addresses: List<String> = emptyList()
    ): Account = Account(
        id = 1L,
        provider = AccountProvider.ICLOUD,
        email = email,
        calendarUserAddresses = addresses
    )

    // ---- Multi-address matching against calendar_user_addresses ----

    @Test
    fun `matches primary mailto in multi-alias set`() {
        val a = account(addresses = listOf("mailto:alice@icloud.com", "mailto:alice@me.com", "mailto:alice@mac.com"))
        assertTrue(a.matchesAttendee("mailto:alice@icloud.com"))
    }

    @Test
    fun `matches alias mailto in multi-alias set`() {
        val a = account(addresses = listOf("mailto:alice@icloud.com", "mailto:alice@me.com", "mailto:alice@mac.com"))
        assertTrue(a.matchesAttendee("mailto:alice@me.com"))
    }

    @Test
    fun `does not match unrelated mailto`() {
        val a = account(addresses = listOf("mailto:alice@icloud.com", "mailto:alice@me.com"))
        assertFalse(a.matchesAttendee("mailto:bob@example.com"))
    }

    @Test
    fun `matches urn entry in mixed-form set`() {
        val a = account(addresses = listOf("/123/principal/", "urn:uuid:123456789", "mailto:alice@icloud.com"))
        assertTrue(a.matchesAttendee("urn:uuid:123456789"))
    }

    @Test
    fun `matches path-relative entry in mixed-form set`() {
        val a = account(addresses = listOf("/123/principal/", "urn:uuid:123456789", "mailto:alice@icloud.com"))
        assertTrue(a.matchesAttendee("/123/principal/"))
    }

    // ---- Empty-set fallback ----

    @Test
    fun `falls back to email login when set is empty and login is email-shaped`() {
        val a = account(email = "alice@icloud.com", addresses = emptyList())
        assertTrue(a.matchesAttendee("mailto:alice@icloud.com"))
    }

    @Test
    fun `falls back to email login but rejects unrelated attendee`() {
        val a = account(email = "alice@icloud.com", addresses = emptyList())
        assertFalse(a.matchesAttendee("mailto:bob@example.com"))
    }

    @Test
    fun `does not fall back when login is not email-shaped`() {
        val a = account(email = "alice", addresses = emptyList())
        assertFalse(a.matchesAttendee("mailto:alice@example.com"))
    }

    // ---- Case sensitivity ----

    @Test
    fun `mailto match is case-insensitive across stored and queried scheme`() {
        val a = account(addresses = listOf("MAILTO:Alice@Example.COM"))
        assertTrue(a.matchesAttendee("mailto:alice@example.com"))
    }

    @Test
    fun `urn match is case-sensitive`() {
        val a = account(addresses = listOf("urn:uuid:ABC-DEF"))
        assertFalse(a.matchesAttendee("urn:uuid:abc-def"))
    }

    // ---- Fallback edge case: email-shaped login + URN-form attendee ----

    @Test
    fun `email-shape fallback only enables mailto matching, not URN matching`() {
        val a = account(email = "alice@icloud.com", addresses = emptyList())
        assertFalse(a.matchesAttendee("urn:uuid:123456789"))
    }
}
