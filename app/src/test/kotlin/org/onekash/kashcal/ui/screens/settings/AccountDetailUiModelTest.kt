package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Unit tests for AccountDetailUiModel mapping from Account entity.
 */
class AccountDetailUiModelTest {

    private fun createAccount(
        id: Long = 1L,
        provider: AccountProvider = AccountProvider.CALDAV,
        email: String = "user@example.com",
        displayName: String? = "My Server",
        principalUrl: String? = "https://caldav.example.com/principal/",
        isEnabled: Boolean = true,
        lastSuccessfulSyncAt: Long? = 1704067200000L,
        consecutiveSyncFailures: Int = 0
    ) = Account(
        id = id,
        provider = provider,
        email = email,
        displayName = displayName,
        principalUrl = principalUrl,
        isEnabled = isEnabled,
        lastSuccessfulSyncAt = lastSuccessfulSyncAt,
        consecutiveSyncFailures = consecutiveSyncFailures
    )

    @Test
    fun `maps all fields correctly`() {
        val account = createAccount()
        val model = account.toDetailUiModel(calendarCount = 5)

        assertEquals(1L, model.accountId)
        assertEquals(AccountProvider.CALDAV, model.provider)
        assertEquals("My Server", model.displayName)
        assertEquals("u***@example.com", model.email) // masked
        assertEquals("https://caldav.example.com/principal/", model.principalUrl)
        assertEquals(5, model.calendarCount)
        assertEquals(true, model.isEnabled)
        assertEquals(1704067200000L, model.lastSuccessfulSyncAt)
        assertEquals(0, model.consecutiveSyncFailures)
    }

    @Test
    fun `null displayName falls back to provider displayName`() {
        val account = createAccount(displayName = null, provider = AccountProvider.ICLOUD)
        val model = account.toDetailUiModel(calendarCount = 3)

        assertEquals("iCloud", model.displayName)
    }

    @Test
    fun `null principalUrl maps to null`() {
        val account = createAccount(principalUrl = null)
        val model = account.toDetailUiModel(calendarCount = 1)

        assertNull(model.principalUrl)
    }

    @Test
    fun `null lastSuccessfulSyncAt maps to null`() {
        val account = createAccount(lastSuccessfulSyncAt = null)
        val model = account.toDetailUiModel(calendarCount = 0)

        assertNull(model.lastSuccessfulSyncAt)
    }

    @Test
    fun `consecutiveSyncFailures maps correctly when positive`() {
        val account = createAccount(consecutiveSyncFailures = 3)
        val model = account.toDetailUiModel(calendarCount = 2)

        assertEquals(3, model.consecutiveSyncFailures)
    }

    @Test
    fun `disabled account maps isEnabled false`() {
        val account = createAccount(isEnabled = false)
        val model = account.toDetailUiModel(calendarCount = 1)

        assertEquals(false, model.isEnabled)
    }

    @Test
    fun `email with short prefix masks correctly`() {
        val account = createAccount(email = "ab@test.com")
        val model = account.toDetailUiModel(calendarCount = 0)

        assertEquals("a***@test.com", model.email)
    }

    @Test
    fun `email with single char prefix stays unmasked`() {
        val account = createAccount(email = "a@b.com")
        val model = account.toDetailUiModel(calendarCount = 0)

        // maskEmail returns original for atIndex <= 1
        assertEquals("a@b.com", model.email)
    }

    @Test
    fun `iCloud provider maps correctly`() {
        val account = createAccount(provider = AccountProvider.ICLOUD, displayName = "iCloud")
        val model = account.toDetailUiModel(calendarCount = 3)

        assertEquals(AccountProvider.ICLOUD, model.provider)
        assertEquals("iCloud", model.displayName)
    }

    @Test
    fun `CalDAV provider with custom displayName`() {
        val account = createAccount(provider = AccountProvider.CALDAV, displayName = "Nextcloud Work")
        val model = account.toDetailUiModel(calendarCount = 7)

        assertEquals(AccountProvider.CALDAV, model.provider)
        assertEquals("Nextcloud Work", model.displayName)
    }
}
