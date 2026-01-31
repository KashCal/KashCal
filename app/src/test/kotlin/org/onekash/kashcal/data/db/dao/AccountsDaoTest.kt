package org.onekash.kashcal.data.db.dao

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Integration tests for AccountsDao.
 *
 * Tests CRUD operations and sync metadata updates.
 */
class AccountsDaoTest : BaseDaoTest() {

    private val accountsDao by lazy { database.accountsDao() }

    private fun createAccount(
        provider: AccountProvider = AccountProvider.ICLOUD,
        email: String = "test@example.com"
    ) = Account(
        provider = provider,
        email = email,
        displayName = "Test Account"
    )

    // ========== Insert Tests ==========

    @Test
    fun `insert returns generated id`() = runTest {
        val account = createAccount()
        val id = accountsDao.insert(account)
        assertTrue(id > 0)
    }

    @Test
    fun `insert and retrieve account`() = runTest {
        val account = createAccount()
        val id = accountsDao.insert(account)

        val retrieved = accountsDao.getById(id)
        assertNotNull(retrieved)
        assertEquals(AccountProvider.ICLOUD, retrieved!!.provider)
        assertEquals("test@example.com", retrieved.email)
    }

    @Test
    fun `insert multiple accounts`() = runTest {
        accountsDao.insert(createAccount(email = "user1@example.com"))
        accountsDao.insert(createAccount(email = "user2@example.com"))
        accountsDao.insert(createAccount(provider = AccountProvider.CALDAV, email = "user3@example.com"))

        val accounts = accountsDao.getAllOnce()
        assertEquals(3, accounts.size)
    }

    // ========== Query Tests ==========

    @Test
    fun `getById returns null for non-existent id`() = runTest {
        val result = accountsDao.getById(999L)
        assertNull(result)
    }

    @Test
    fun `getByProviderAndEmail finds account`() = runTest {
        accountsDao.insert(createAccount(provider = AccountProvider.ICLOUD, email = "test@icloud.com"))

        val result = accountsDao.getByProviderAndEmail(AccountProvider.ICLOUD, "test@icloud.com")
        assertNotNull(result)
        assertEquals(AccountProvider.ICLOUD, result!!.provider)
    }

    @Test
    fun `getByProviderAndEmail returns null for no match`() = runTest {
        accountsDao.insert(createAccount())

        val result = accountsDao.getByProviderAndEmail(AccountProvider.CALDAV, "other@gmail.com")
        assertNull(result)
    }

    @Test
    fun `getEnabledAccounts filters disabled`() = runTest {
        val enabledId = accountsDao.insert(createAccount(email = "enabled@test.com"))
        val disabledId = accountsDao.insert(createAccount(email = "disabled@test.com"))

        accountsDao.setEnabled(disabledId, false)

        val enabled = accountsDao.getEnabledAccounts()
        assertEquals(1, enabled.size)
        assertEquals(enabledId, enabled[0].id)
    }

    @Test
    fun `getAll returns Flow that updates`() = runTest {
        val flow = accountsDao.getAll()

        // Initially empty
        assertEquals(0, flow.first().size)

        // Add account
        accountsDao.insert(createAccount())

        // Flow should reflect new account
        assertEquals(1, flow.first().size)
    }

    // ========== Update Tests ==========

    @Test
    fun `update modifies account`() = runTest {
        val id = accountsDao.insert(createAccount())
        val original = accountsDao.getById(id)!!

        val updated = original.copy(displayName = "Updated Name")
        accountsDao.update(updated)

        val result = accountsDao.getById(id)
        assertEquals("Updated Name", result!!.displayName)
    }

    @Test
    fun `setEnabled toggles account state`() = runTest {
        val id = accountsDao.insert(createAccount())

        accountsDao.setEnabled(id, false)
        assertEquals(false, accountsDao.getById(id)!!.isEnabled)

        accountsDao.setEnabled(id, true)
        assertEquals(true, accountsDao.getById(id)!!.isEnabled)
    }

    // ========== Delete Tests ==========

    @Test
    fun `delete removes account`() = runTest {
        val account = createAccount()
        val id = accountsDao.insert(account)

        accountsDao.delete(account.copy(id = id))

        assertNull(accountsDao.getById(id))
    }

    @Test
    fun `deleteById removes account`() = runTest {
        val id = accountsDao.insert(createAccount())

        accountsDao.deleteById(id)

        assertNull(accountsDao.getById(id))
    }

    // ========== Sync Metadata Tests ==========

    @Test
    fun `recordSyncSuccess updates timestamps and resets failures`() = runTest {
        val id = accountsDao.insert(createAccount())
        val now = System.currentTimeMillis()

        // First record a failure
        accountsDao.recordSyncFailure(id, now - 1000)
        assertEquals(1, accountsDao.getById(id)!!.consecutiveSyncFailures)

        // Then record success
        accountsDao.recordSyncSuccess(id, now)

        val result = accountsDao.getById(id)!!
        assertEquals(now, result.lastSyncAt)
        assertEquals(now, result.lastSuccessfulSyncAt)
        assertEquals(0, result.consecutiveSyncFailures)
    }

    @Test
    fun `recordSyncFailure increments failure count`() = runTest {
        val id = accountsDao.insert(createAccount())
        val now = System.currentTimeMillis()

        accountsDao.recordSyncFailure(id, now)
        assertEquals(1, accountsDao.getById(id)!!.consecutiveSyncFailures)

        accountsDao.recordSyncFailure(id, now + 1000)
        assertEquals(2, accountsDao.getById(id)!!.consecutiveSyncFailures)

        accountsDao.recordSyncFailure(id, now + 2000)
        assertEquals(3, accountsDao.getById(id)!!.consecutiveSyncFailures)
    }

    @Test
    fun `updateCalDavUrls sets discovery URLs`() = runTest {
        val id = accountsDao.insert(createAccount())

        accountsDao.updateCalDavUrls(
            id,
            principalUrl = "https://caldav.icloud.com/123/principal/",
            homeSetUrl = "https://caldav.icloud.com/123/calendars/"
        )

        val result = accountsDao.getById(id)!!
        assertEquals("https://caldav.icloud.com/123/principal/", result.principalUrl)
        assertEquals("https://caldav.icloud.com/123/calendars/", result.homeSetUrl)
    }

    @Test
    fun `getAccountsWithSyncErrors returns failing accounts`() = runTest {
        val id1 = accountsDao.insert(createAccount(email = "ok@test.com"))
        val id2 = accountsDao.insert(createAccount(email = "failing@test.com"))

        accountsDao.recordSyncFailure(id2, System.currentTimeMillis())

        val failing = accountsDao.getAccountsWithSyncErrors()
        assertEquals(1, failing.size)
        assertEquals(id2, failing[0].id)
    }

    // ========== Unique Constraint Tests ==========

    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun `insert duplicate provider-email throws`() = runTest {
        accountsDao.insert(createAccount(provider = AccountProvider.ICLOUD, email = "dup@test.com"))
        accountsDao.insert(createAccount(provider = AccountProvider.ICLOUD, email = "dup@test.com"))
    }

    @Test
    fun `same email different provider allowed`() = runTest {
        accountsDao.insert(createAccount(provider = AccountProvider.ICLOUD, email = "same@test.com"))
        accountsDao.insert(createAccount(provider = AccountProvider.CALDAV, email = "same@test.com"))

        assertEquals(2, accountsDao.getAllOnce().size)
    }
}
