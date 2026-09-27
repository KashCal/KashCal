package org.onekash.kashcal.data.repository

import android.util.Log
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.credential.AccountCredentials
import org.onekash.kashcal.data.credential.CredentialManager
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.AddressBookDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.adapter.ContactSystemAccountRegistrar
import org.onekash.kashcal.sync.contacts.ContactsProviderRepository
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton

/** Room, credential-store and contacts-account backed [AccountRepository]. */
@Singleton
class AccountRepositoryImpl @Inject constructor(
    private val accountsDao: AccountsDao,
    private val addressBookDao: AddressBookDao,
    private val calendarsDao: CalendarsDao,
    private val eventsDao: EventsDao,
    private val pendingOperationsDao: PendingOperationsDao,
    private val credentialManager: CredentialManager,
    private val reminderScheduler: ReminderScheduler,
    private val workManager: WorkManager,
    private val contactSystemAccountRegistrar: ContactSystemAccountRegistrar,
    private val contactsProviderRepository: ContactsProviderRepository
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

    override fun getAccountByIdFlow(id: Long): Flow<Account?> {
        return accountsDao.getByIdFlow(id)
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

    override suspend fun getAccountByProviderEmailAndHomeSetUrl(
        provider: AccountProvider,
        email: String,
        homeSetUrl: String
    ): Account? {
        return accountsDao.getByProviderEmailAndHomeSetUrl(provider, email, homeSetUrl)
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
     * Steps are listed on [AccountRepository.deleteAccount]. The order matters: sync work is
     * cancelled first so no sync runs during cleanup; reminders and pending operations go
     * before the cascade, which deletes the event IDs they are found by (a cascade alone leaves
     * the alarms in AlarmManager); the irreversible contacts purge goes last.
     */
    override suspend fun deleteAccount(accountId: Long) {
        Log.i(TAG, "Deleting account: $accountId")

        // Decide the purge before the cascade deletes the row; [contactsAccountToPurge] holds
        // the same-email sibling rule.
        val account = accountsDao.getById(accountId)
        val contactsAccountToRemove = account?.let { contactsAccountToPurge(it) }

        // 1. The one-shot and expedited jobs have one work name shared by all accounts. A
        //    running or enqueued one for this account would otherwise outlive it and bring
        //    back sync UI for an account the user removed. Other accounts re-enqueue on their
        //    next trigger.
        workManager.cancelUniqueWork("sync_account_$accountId")
        workManager.cancelUniqueWork(SyncScheduler.ONE_SHOT_SYNC_WORK)
        workManager.cancelUniqueWork(SyncScheduler.EXPEDITED_SYNC_WORK)
        Log.d(TAG, "Cancelled WorkManager jobs for account $accountId")

        // 2. Before the cascade, which deletes the event IDs these are found by.
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

        // 3. A failure is logged and ignored; the credentials may not exist.
        try {
            credentialManager.deleteCredentials(accountId)
            Log.d(TAG, "Deleted credentials for account $accountId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete credentials for account $accountId: ${e.message}")
        }

        // 4. Cascades as listed on [AccountRepository.deleteAccount].
        accountsDao.deleteById(accountId)
        Log.i(TAG, "Account $accountId deleted with cascade")

        // 4a. Periodic sync and contact sync are single jobs shared by all accounts, so they
        //     stay while any account can sync (CalDAV-capable, with stored credentials). After
        //     the last one, they would keep waking with nothing to do and bring back sync UI
        //     on a device that is now device-calendar-only.
        val remainingSyncable = accountsDao.getAllOnce().any {
            it.provider.supportsCalDAV && credentialManager.hasCredentials(it.id)
        }
        if (!remainingSyncable) {
            workManager.cancelUniqueWork(SyncScheduler.PERIODIC_SYNC_WORK)
            workManager.cancelUniqueWork(SyncScheduler.PERIODIC_CONTACT_SYNC_WORK)
            Log.d(TAG, "No syncable account remains; cancelled periodic sync work")
        }

        // 5. Last, because the purge (a synchronous Binder IPC to AccountManagerService) is
        //    irreversible: if any earlier step throws, the deletion aborts with the contacts
        //    intact, instead of leaving a live account whose synced contacts are already gone.
        contactsAccountToRemove?.let { email ->
            purgeContactsForEmail(email)
        }

        // Nothing here refreshes the widgets.
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

    override suspend fun updateCalendarUserAddresses(accountId: Long, addresses: List<String>) {
        accountsDao.updateCalendarUserAddresses(accountId, addresses)
    }

    override suspend fun updateScheduleOutboxUrl(accountId: Long, outboxUrl: String?) {
        accountsDao.updateScheduleOutboxUrl(accountId, outboxUrl)
    }

    override suspend fun setEnabled(accountId: Long, enabled: Boolean) {
        accountsDao.setEnabled(accountId, enabled)
    }

    override suspend fun setContactSyncEnabled(accountId: Long, enabled: Boolean): ContactPurgeOutcome {
        // The contacts system account is keyed by login email.
        val account = accountsDao.getById(accountId) ?: run {
            Log.w(TAG, "setContactSyncEnabled: account $accountId not found")
            return ContactPurgeOutcome.NOT_ATTEMPTED
        }
        // Register or purge before setting the flag, so on enable the account exists by the
        // time the flag reads true; enrolment is what stops Android purging RawContacts written
        // under it. The registrar is idempotent and swallows its own failures. On disable the
        // sibling rule of [contactsAccountToPurge] applies, as in deleteAccount.
        var outcome = ContactPurgeOutcome.NOT_ATTEMPTED
        if (enabled) {
            contactSystemAccountRegistrar.ensureAccount(account.email)
        } else {
            contactsAccountToPurge(account)?.let { email ->
                outcome = purgeContactsForEmail(email)
            }
        }
        accountsDao.setContactSyncEnabled(accountId, enabled)
        return outcome
    }

    /**
     * Returns the email of [account]'s contacts system account if it is safe to purge, or null
     * for a non-CardDAV account or when the account must be kept.
     *
     * The contacts account is keyed by email, but accounts are unique on provider, email and
     * home set URL, so two logins (for example iCloud and a CalDAV host) can share one
     * email-named contacts account holding both logins' contacts. The purge is all or nothing
     * and irreversible, so it is kept only while another same-email login is CardDAV-capable
     * with contact sync enabled. A sibling with contact sync off, or a non-CardDAV sibling that
     * never registered a contacts account, must not block the purge, or disabling or deleting the
     * last syncing login leaves its contacts stranded on the device.
     */
    private suspend fun contactsAccountToPurge(account: Account): String? =
        account
            .takeIf { it.provider.supportsCardDAV }
            ?.email
            ?.takeUnless { email ->
                accountsDao.getAllOnce().any {
                    it.id != account.id &&
                        it.email == email &&
                        it.provider.supportsCardDAV &&
                        it.contactSyncEnabled
                }
            }

    /**
     * Deletes the synced RawContacts of the contacts system account named [email], removes the
     * account, and clears same-email logins' address books ([clearContactSyncCursorsForEmail]).
     *
     * Removing the account should cascade to its RawContacts, but the cascade isn't guaranteed (a
     * declined removal, or a provider that leaves the rows account-less), which leaves orphaned
     * contacts after a disable or sign-out. So the rows are deleted first through
     * [ContactsProviderRepository.purgeAccount], scoped to `ACCOUNT_NAME` and our contacts
     * `ACCOUNT_TYPE`; it never removes a contact of another account (for example Google), even one
     * sharing a phone number. Then the account is removed and the rows are counted once. There is
     * no retry.
     *
     * Returns [ContactPurgeOutcome.PURGED] only when the delete succeeded and the count is 0,
     * else [ContactPurgeOutcome.INCOMPLETE]. The delete fails on revoked WRITE_CONTACTS, and
     * [ContactsProviderRepository.countRawContacts] returns 0 when it can't read, so a 0 alone
     * isn't proof.
     *
     * The address books are cleared whatever the count, because the purge is irreversible and
     * has dropped the account's RawContacts; clearing them is part of purging, not a step a
     * caller can forget.
     */
    private suspend fun purgeContactsForEmail(email: String): ContactPurgeOutcome {
        // Keep the delete's result: after a failed delete a count of 0 means "can't tell".
        val delete = contactsProviderRepository.purgeAccount(email)
        contactSystemAccountRegistrar.removeAccount(email)
        clearContactSyncCursorsForEmail(email)
        // Surviving rows are ones the account-scoped delete can't match (for example
        // account-less survivors). The removeAccount cascade is synchronous and the scoped
        // delete deterministic, so re-running the delete would clear nothing new; report
        // INCOMPLETE instead.
        val remaining = contactsProviderRepository.countRawContacts(email)
        if (remaining > 0) {
            Log.w(TAG, "Contacts purge left $remaining synced rows on the device")
        }
        return if (delete.isSuccess && remaining == 0) ContactPurgeOutcome.PURGED
        else ContactPurgeOutcome.INCOMPLETE
    }

    /**
     * Deletes the `address_books` rows, and with them the sync-tokens and ctags, of every
     * CardDAV-capable login sharing [email].
     *
     * After a purge, a kept row would hold a token the server still honors while the device
     * has none of the contacts it covers. Without the rows the next sync of that login takes a
     * full listing, which fetches everything. A non-CardDAV same-email login never wrote
     * contacts into the account, so it has no rows. Idempotent: a login with no books is a no-op
     * delete.
     */
    private suspend fun clearContactSyncCursorsForEmail(email: String) {
        accountsDao.getAllOnce()
            .filter { it.email == email && it.provider.supportsCardDAV }
            .forEach { addressBookDao.deleteByAccountId(it.id) }
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
