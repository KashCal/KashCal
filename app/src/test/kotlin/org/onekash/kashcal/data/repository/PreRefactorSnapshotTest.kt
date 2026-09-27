package org.onekash.kashcal.data.repository

import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler

/**
 * Records what an account removal that only deletes the account row through [AccountsDao]
 * leaves behind, and the credential key formats before and after `CredentialMigration`.
 *
 * The two reminder tests run a local copy of that removal, not the discovery services:
 * `ICloudAccountDiscoveryService.removeAccount` and `CalDavAccountDiscoveryService.removeAccount`
 * delegate to [AccountRepository.deleteAccount], which cancels the reminders, sync work and
 * pending operations the copy leaves (`PostRefactorVerificationTest` checks the delegation).
 * The sync-work, pending-operation and delegation placeholder tests assert nothing. The
 * credential-format and backup tests check only the key and file-name strings they list; they
 * read neither the prefs files nor the backup rules.
 */
class PreRefactorSnapshotTest {

    private lateinit var accountsDao: AccountsDao
    private lateinit var reminderScheduler: ReminderScheduler

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        accountsDao = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== Row-Only Removal Leaves Reminders ==========

    /**
     * Runs a copy of an iCloud removal that only deletes the row, and asserts it cancels no
     * reminder:
     * ```
     * override suspend fun removeAccount(accountId: Long) = withContext(Dispatchers.IO) {
     *     val account = accountsDao.getById(accountId)
     *     if (account != null) {
     *         accountsDao.delete(account)  // cascades, but cancels no reminder
     *     }
     * }
     * ```
     *
     * The cascade deletes the events, but their alarms stay in AlarmManager after sign-out.
     * `ICloudAccountDiscoveryService.removeAccount` delegates to [AccountRepository.deleteAccount],
     * which cancels them.
     */
    @Test
    fun `BUG - ICloudAccountDiscoveryService removeAccount does NOT cancel reminders`() {
        val accountId = 1L
        val account = Account(
            id = accountId,
            provider = AccountProvider.ICLOUD,
            email = "user@icloud.com",
            displayName = "iCloud"
        )

        coEvery { accountsDao.getById(accountId) } returns account

        // The row-only removal shown in this test's doc.
        runBlocking {
            withContext(Dispatchers.IO) {
                val acc = accountsDao.getById(accountId)
                if (acc != null) {
                    accountsDao.delete(acc)
                }
            }
        }

        // No reminder is cancelled.
        coVerify(exactly = 0) { reminderScheduler.cancelRemindersForEvent(any()) }
    }

    /**
     * Runs a copy of a CalDAV removal that deletes the credentials and the row, and asserts it
     * cancels no reminder:
     * ```
     * suspend fun removeAccount(accountId: Long) = withContext(Dispatchers.IO) {
     *     credentialManager.deleteCredentials(accountId)
     *     val account = accountsDao.getById(accountId)
     *     if (account != null) {
     *         accountsDao.delete(account)  // cancels no reminder
     *     }
     * }
     * ```
     *
     * The alarms stay in AlarmManager, as in the iCloud case.
     * `CalDavAccountDiscoveryService.removeAccount` delegates to [AccountRepository.deleteAccount].
     */
    @Test
    fun `BUG - CalDavAccountDiscoveryService removeAccount does NOT cancel reminders`() {
        val accountId = 2L
        val account = Account(
            id = accountId,
            provider = AccountProvider.CALDAV,
            email = "user@nextcloud.com",
            displayName = "Nextcloud"
        )

        coEvery { accountsDao.getById(accountId) } returns account

        // The removal from the doc, without the credential deletion, which doesn't affect
        // reminders.
        runBlocking {
            withContext(Dispatchers.IO) {
                val acc = accountsDao.getById(accountId)
                if (acc != null) {
                    accountsDao.delete(acc)
                }
            }
        }

        // No reminder is cancelled.
        coVerify(exactly = 0) { reminderScheduler.cancelRemindersForEvent(any()) }
    }

    // ========== Row-Only Removal Leaves Sync Work ==========

    /**
     * Placeholder that asserts nothing. A row-only removal cancels no sync work, so a queued job
     * may run for the deleted account and fail. [AccountRepository.deleteAccount] cancels it with
     * `workManager.cancelUniqueWork`.
     */
    @Test
    fun `BUG - Discovery services do NOT cancel WorkManager jobs on account removal`() {
        // deleteAccount cancels "sync_account_$accountId", the shared one-shot and expedited
        // work, and the periodic work once no syncable account remains.

        assertTrue(
            "WorkManager cleanup missing from discovery services - fixed in AccountRepository",
            true
        )
    }

    // ========== Row-Only Removal Leaves Pending Operations ==========

    /**
     * Placeholder that asserts nothing. pending_operations has no foreign key, so the cascade
     * that deletes the account's events leaves their pending operations in the table.
     * [AccountRepository.deleteAccount] deletes them before the cascade.
     */
    @Test
    fun `BUG - Discovery services do NOT delete pending operations on account removal`() {
        // deleteAccount calls pendingOperationsDao.deleteForEvent for each master event.

        assertTrue(
            "Pending operations cleanup missing - fixed in AccountRepository",
            true
        )
    }

    // ========== Credential Format Documentation ==========

    /**
     * Lists the old iCloud credential keys, which carry no account ID.
     *
     * Old format in `icloud_credentials`:
     * - apple_id = "user@icloud.com"
     * - app_password = "xxxx-xxxx-xxxx-xxxx"
     * - server_url = "https://caldav.icloud.com"
     * - principal_url = "https://caldav.icloud.com/12345/principal/"
     * - calendar_home_url = "https://caldav.icloud.com/12345/calendars/"
     *
     * This format holds only one iCloud account. `CredentialMigration` copies it to
     * `account_{id}_username` and so on in `unified_credentials`.
     */
    @Test
    fun `DOC - iCloud uses single-key format - apple_id not account_1_apple_id`() {
        // The old iCloud keys have no account ID prefix.
        val oldKeys = listOf(
            "apple_id",
            "app_password",
            "server_url",
            "principal_url",
            "calendar_home_url"
        )

        // No key has an account or caldav prefix.
        oldKeys.forEach { key ->
            assertFalse("Key '$key' should NOT have account prefix", key.startsWith("account_"))
            assertFalse("Key '$key' should NOT have caldav prefix", key.startsWith("caldav_"))
        }
    }

    /**
     * Lists the old CalDAV credential keys, which are keyed by account ID.
     *
     * Old format in `caldav_credentials`:
     * - caldav_{id}_server_url = "https://nextcloud.com/remote.php/dav"
     * - caldav_{id}_username = "admin"
     * - caldav_{id}_password = "app-password"
     * - caldav_{id}_trust_insecure = false
     *
     * This format holds any number of CalDAV accounts. `CredentialMigration` copies it to
     * `account_{id}_username` and so on in `unified_credentials`.
     */
    @Test
    fun `DOC - CalDAV uses account-keyed format - caldav_1_username`() {
        val accountId = 1L
        val expectedKeys = listOf(
            "caldav_${accountId}_server_url",
            "caldav_${accountId}_username",
            "caldav_${accountId}_password",
            "caldav_${accountId}_trust_insecure"
        )

        // Each key has the caldav_ prefix and the account ID.
        expectedKeys.forEach { key ->
            assertTrue("Key '$key' should have caldav_ prefix", key.startsWith("caldav_"))
            assertTrue("Key '$key' should contain account ID", key.contains("_${accountId}_"))
        }
    }

    /**
     * Lists the unified credential keys that `UnifiedCredentialManager` writes.
     *
     * Format in `unified_credentials`:
     * - account_{id}_username = "user@example.com"
     * - account_{id}_password = "password"
     * - account_{id}_server_url = "https://server.com"
     * - account_{id}_trust_insecure = false
     * - account_{id}_principal_url = "https://server.com/principal/"
     * - account_{id}_calendar_home_set = "https://server.com/calendars/"
     *
     * One format for iCloud and CalDAV accounts.
     */
    @Test
    fun `DOC - Unified format uses account_id prefix for all providers`() {
        val accountId = 42L
        val expectedKeys = listOf(
            "account_${accountId}_username",
            "account_${accountId}_password",
            "account_${accountId}_server_url",
            "account_${accountId}_trust_insecure",
            "account_${accountId}_principal_url",
            "account_${accountId}_calendar_home_set"
        )

        // Each key has the account_ prefix and the account ID.
        expectedKeys.forEach { key ->
            assertTrue("Key '$key' should have account_ prefix", key.startsWith("account_"))
            assertTrue("Key '$key' should contain account ID", key.contains("_${accountId}_"))
        }
    }

    // ========== Backup Rules Documentation ==========

    /**
     * Lists the credential files that backup_rules.xml and data_extraction_rules.xml exclude:
     * - icloud_credentials.xml
     * - caldav_credentials.xml
     * - unified_credentials.xml
     *
     * A restored copy couldn't be decrypted on a device with another Android Keystore master key.
     * The test checks only that each listed name ends in `_credentials.xml`; it doesn't read the
     * rules files.
     */
    @Test
    fun `DOC - backup_rules excludes all credential files`() {
        // The files the backup rules exclude.
        val excludedFiles = listOf(
            "icloud_credentials.xml",
            "caldav_credentials.xml",
            "unified_credentials.xml"
        )

        // All three are excluded in backup_rules.xml and in both sections of
        // data_extraction_rules.xml.
        excludedFiles.forEach { file ->
            assertTrue(
                "File '$file' should be excluded from backup",
                file.endsWith("_credentials.xml")
            )
        }
    }

    // ========== Delegation Placeholders ==========

    /**
     * Placeholder that asserts nothing. `PostRefactorVerificationTest` checks that
     * `ICloudAccountDiscoveryService.removeAccount` delegates to [AccountRepository.deleteAccount].
     */
    @Test
    fun `VERIFY AFTER MIGRATION - ICloudAccountDiscoveryService uses AccountRepository`() {
        // assertTrue(message, true) always passes.

        assertTrue(
            "Update after migration: Verify ICloudAccountDiscoveryService migration",
            true
        )
    }

    /**
     * Placeholder that asserts nothing. `PostRefactorVerificationTest` checks that
     * `CalDavAccountDiscoveryService.removeAccount` delegates to [AccountRepository.deleteAccount].
     */
    @Test
    fun `VERIFY AFTER MIGRATION - CalDavAccountDiscoveryService uses AccountRepository`() {
        // assertTrue(message, true) always passes.

        assertTrue(
            "Update after migration: Verify CalDavAccountDiscoveryService migration",
            true
        )
    }
}
