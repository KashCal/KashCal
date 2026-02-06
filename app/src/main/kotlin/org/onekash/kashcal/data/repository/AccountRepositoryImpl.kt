package org.onekash.kashcal.data.repository

import android.util.Log
import androidx.room.Transaction
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.credential.AccountCredentials
import org.onekash.kashcal.data.credential.CredentialManager
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of AccountRepository.
 *
 * Handles full account lifecycle including cleanup on deletion:
 * - WorkManager job cancellation
 * - Reminder cancellation
 * - Pending operation cleanup
 * - Credential deletion
 * - Cascade delete via Room FK constraints
 */
@Singleton
class AccountRepositoryImpl @Inject constructor(
    private val accountsDao: AccountsDao,
    private val calendarsDao: CalendarsDao,
    private val eventsDao: EventsDao,
    private val pendingOperationsDao: PendingOperationsDao,
    private val credentialManager: CredentialManager,
    private val reminderScheduler: ReminderScheduler,
    private val workManager: WorkManager
) : AccountRepository {

    companion object {
        private const val TAG = "AccountRepository"
    }

    // ========== Reactive Queries (Flow) ==========

    override fun getAllAccountsFlow(): Flow<List<Account>> {
        return accountsDao.getAll()
    }

    override fun getAccountsByProviderFlow(provider: AccountProvider): Flow<List<Account>> {
        return accountsDao.getByProviderFlow(provider)
    }

    override fun getAccountCountByProviderFlow(provider: AccountProvider): Flow<Int> {
        return accountsDao.getAccountCountByProvider(provider)
    }

    // ========== One-Shot Queries ==========

    override suspend fun getAccountById(id: Long): Account? {
        return accountsDao.getById(id)
    }

    override suspend fun getAccountByProviderAndEmail(
        provider: AccountProvider,
        email: String
    ): Account? {
        return accountsDao.getByProviderAndEmail(provider, email)
    }

    override suspend fun getEnabledAccounts(): List<Account> {
        return accountsDao.getEnabledAccounts()
    }

    override suspend fun getAllAccounts(): List<Account> {
        return accountsDao.getAllOnce()
    }

    override suspend fun getAccountsByProvider(provider: AccountProvider): List<Account> {
        return accountsDao.getByProvider(provider)
    }

    override suspend fun countByDisplayName(displayName: String, excludeAccountId: Long?): Int {
        return accountsDao.countByDisplayName(displayName, excludeAccountId)
    }

    // ========== Write Operations ==========

    override suspend fun createAccount(account: Account): Long {
        return accountsDao.insert(account)
    }

    override suspend fun updateAccount(account: Account) {
        accountsDao.update(account)
    }

    /**
     * Delete account with comprehensive cleanup.
     *
     * BUG FIX: Previous implementations forgot to cancel reminders,
     * leaving orphaned alarms in AlarmManager.
     *
     * Order of operations matters:
     * 1. Cancel WorkManager jobs (prevents sync during cleanup)
     * 2. Cancel reminders BEFORE cascade delete (need event IDs)
     * 3. Delete pending operations BEFORE cascade delete (need event IDs)
     * 4. Delete credentials (independent, can fail silently)
     * 5. Cascade delete via Room FK constraints
     */
    @Transaction
    override suspend fun deleteAccount(accountId: Long) {
        Log.i(TAG, "Deleting account: $accountId")

        // 1. Cancel pending sync jobs (prevents orphaned WorkManager jobs)
        workManager.cancelUniqueWork("sync_account_$accountId")
        Log.d(TAG, "Cancelled WorkManager jobs for account $accountId")

        // 2. Cancel reminders and delete pending ops BEFORE cascade delete
        // (We need event IDs which will be deleted by cascade)
        val calendars = calendarsDao.getByAccountIdOnce(accountId)
        var remindersCancelled = 0
        var pendingOpsDeleted = 0

        for (calendar in calendars) {
            val events = eventsDao.getAllMasterEventsForCalendar(calendar.id)
            for (event in events) {
                reminderScheduler.cancelRemindersForEvent(event.id)
                remindersCancelled++
                pendingOperationsDao.deleteForEvent(event.id)
                pendingOpsDeleted++
            }
        }
        Log.d(TAG, "Cancelled $remindersCancelled reminders, deleted $pendingOpsDeleted pending ops")

        // 3. Delete credentials (silent failure OK - may not exist)
        try {
            credentialManager.deleteCredentials(accountId)
            Log.d(TAG, "Deleted credentials for account $accountId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete credentials for account $accountId: ${e.message}")
        }

        // 4. Cascade delete account → calendars → events → scheduled_reminders
        // Note: scheduled_reminders has FK to events with ON DELETE CASCADE
        accountsDao.deleteById(accountId)
        Log.i(TAG, "Account $accountId deleted with cascade")

        // Note: Widget refresh happens automatically via Room Flow observers
        // WidgetDataRepository observes calendar/event changes and triggers update
    }

    // ========== Sync Metadata ==========

    override suspend fun recordSyncSuccess(accountId: Long, timestamp: Long) {
        accountsDao.recordSyncSuccess(accountId, timestamp)
    }

    override suspend fun recordSyncFailure(accountId: Long, timestamp: Long) {
        accountsDao.recordSyncFailure(accountId, timestamp)
    }

    override suspend fun updateCalDavUrls(
        accountId: Long,
        principalUrl: String?,
        homeSetUrl: String?
    ) {
        accountsDao.updateCalDavUrls(accountId, principalUrl, homeSetUrl)
    }

    override suspend fun setEnabled(accountId: Long, enabled: Boolean) {
        accountsDao.setEnabled(accountId, enabled)
    }

    // ========== Credentials (Delegated) ==========

    override suspend fun saveCredentials(accountId: Long, credentials: AccountCredentials): Boolean {
        return credentialManager.saveCredentials(accountId, credentials)
    }

    override suspend fun getCredentials(accountId: Long): AccountCredentials? {
        return credentialManager.getCredentials(accountId)
    }

    override suspend fun hasCredentials(accountId: Long): Boolean {
        return credentialManager.hasCredentials(accountId)
    }

    override suspend fun deleteCredentials(accountId: Long) {
        credentialManager.deleteCredentials(accountId)
    }
}
