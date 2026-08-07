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
     * 6. Purge the per-login contacts system account LAST — it is irreversible
     *    (see inline note), so it must not run before the reversible DB work.
     */
    override suspend fun deleteAccount(accountId: Long) {
        Log.i(TAG, "Deleting account: $accountId")

        // Resolve whether to purge this login's contacts system account BEFORE
        // the cascade wipes the row. The contacts account is keyed by email, but
        // accounts are unique on (provider, email, home_set_url) — so the same
        // email can back two logins (e.g. iCloud + a CalDAV host). Only
        // CardDAV-capable providers register a contacts account, so a LOCAL/ICS
        // sibling that happens to share the email must NOT block the purge (else
        // the contacts account leaks with nothing left to manage it), and a
        // remaining CardDAV sibling MUST block it (else we purge its contacts).
        val account = accountsDao.getById(accountId)
        val contactsAccountToRemove = account?.let { contactsAccountToPurge(it) }

        // 1. Cancel pending sync jobs (prevents orphaned WorkManager jobs).
        workManager.cancelUniqueWork("sync_account_$accountId")
        Log.d(TAG, "Cancelled WorkManager jobs for account $accountId")

        // 2. Cancel reminders and delete pending ops BEFORE cascade delete
        //    (we need event IDs which will be deleted by cascade).
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

        // 3. Delete credentials (silent failure OK - may not exist).
        try {
            credentialManager.deleteCredentials(accountId)
            Log.d(TAG, "Deleted credentials for account $accountId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete credentials for account $accountId: ${e.message}")
        }

        // 4. Cascade delete account → calendars → events → scheduled_reminders.
        //    Note: scheduled_reminders has FK to events with ON DELETE CASCADE.
        accountsDao.deleteById(accountId)
        Log.i(TAG, "Account $accountId deleted with cascade")

        // 5. Remove the dedicated contacts system account LAST. Deleting a login
        //    must remove its per-login contacts account too, which also purges
        //    any RawContacts Android holds under it. That purge is a synchronous
        //    Binder IPC to AccountManagerService and is irreversible — so it runs
        //    only after the DB deletes above, never before: if any earlier step
        //    throws, we abort with the contacts still intact rather than orphaning
        //    a live account whose synced contacts were already wiped.
        contactsAccountToRemove?.let { email ->
            purgeContactsForEmail(email)
        }

        // Note: Widget refresh happens automatically via Room Flow observers.
        // WidgetDataRepository observes calendar/event changes and triggers update.
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
        // The contacts system account is keyed by login email, so resolve it
        // before touching AccountManager.
        val account = accountsDao.getById(accountId) ?: run {
            Log.w(TAG, "setContactSyncEnabled: account $accountId not found")
            return ContactPurgeOutcome.NOT_ATTEMPTED
        }
        // Register/remove the system account BEFORE persisting the flag: the
        // registrar is idempotent and swallows its own failures, and enrolling
        // is what stops Android from purging RawContacts written under the
        // account. On enable we want the account to exist by the time the flag
        // reads true; on disable, removing it also purges its contacts — so the
        // same sibling-guard deleteAccount uses applies: the email-named account
        // is shared by same-email CardDAV logins, and removing it would wipe a
        // sibling's synced contacts. Only remove it when no CardDAV sibling
        // still relies on it.
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
     * The login email whose per-login contacts system account is safe to purge
     * for [account], or null when it must be kept.
     *
     * The contacts account is keyed by email, but accounts are unique on
     * (provider, email, home_set_url) — so the same email can back two logins
     * (e.g. iCloud + a CalDAV host) that share ONE email-named contacts account
     * holding the union of both logins' synced contacts. The purge is all-or-
     * nothing and irreversible, so keep the account only when another same-email
     * login is *still actively syncing contacts* into it — CardDAV-capable AND
     * contact-sync enabled. A sibling with contact sync turned off (or a
     * non-CardDAV sibling that never registered a contacts account) contributes
     * nothing to protect, so it must NOT block the purge — otherwise disabling or
     * deleting the last syncing login leaves its contacts stranded on the device.
     * Returns null for a non-contacts account (nothing to remove) or when a
     * still-syncing CardDAV sibling remains.
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
     * Remove the per-login contacts system account named [email] AND make sure its
     * synced RawContacts are actually gone from the device.
     *
     * Removing the account is *supposed* to cascade-delete the RawContacts Android
     * holds under it, but that cascade is not guaranteed (a declined removal, or a
     * provider that leaves the rows account-less), which is how a disable/sign-out
     * can leave hundreds of orphaned contacts on the device. So this does not trust
     * the cascade: it explicitly deletes our own account-scoped rows FIRST (the
     * scoped [ContactsProviderRepository.purgeAccount], which touches nothing but
     * `ACCOUNT_NAME` + our contacts `ACCOUNT_TYPE`), THEN removes the account, then
     * verifies the row count is zero and re-runs the scoped purge once if any
     * survived. Every delete is our-account-scoped, so this can never remove a
     * contact owned by another account (e.g. Google) — even one sharing a phone
     * number — regardless of what the OS cascade does.
     *
     * Returns an honest outcome instead of swallowing failures. Two failure modes
     * used to compound into a false "clean": [ContactsProviderRepository.purgeAccount]
     * returns [Result.failure] on revoked WRITE_CONTACTS, and
     * [ContactsProviderRepository.countRawContacts] returns 0 when READ is also
     * revoked ("can't tell"). Trusting that 0 reported success with rows still on the
     * device. So a post-purge 0 is treated as verified-clean ([ContactPurgeOutcome.PURGED])
     * ONLY when the scoped delete did not itself fail; a failed delete, or rows still
     * counted afterward, yields [ContactPurgeOutcome.INCOMPLETE].
     *
     * Whether or not the row count reaches zero, the purge is irreversible and has
     * dropped the account's RawContacts, so this ALSO clears the CardDAV delta cursors
     * of every same-email login in lockstep (see [clearContactSyncCursorsForEmail]) —
     * the cursor-clear is an invariant of purging, not something a caller may forget.
     */
    private suspend fun purgeContactsForEmail(email: String): ContactPurgeOutcome {
        // Delete our own rows explicitly before dropping the account, rather than
        // relying on the account-removal cascade to do it. Keep the delete's own
        // success/failure — a failure means WRITE_CONTACTS is gone and nothing was
        // deleted, so a later count of 0 can't be trusted as "verified empty".
        val delete = contactsProviderRepository.purgeAccount(email)
        contactSystemAccountRegistrar.removeAccount(email)
        clearContactSyncCursorsForEmail(email)
        // Verify the account removal + explicit purge actually cleared the device.
        // A count of 0 only proves clean if the delete that was supposed to clear it
        // actually ran: on revoked permission the delete failed AND the count can't
        // read — that 0 is "can't tell", not "empty". Any surviving rows are ones the
        // account-scoped predicate can't match (e.g. account-less survivors); the
        // removeAccount cascade is synchronous and the scoped delete deterministic, so
        // re-running it would clear nothing new — report INCOMPLETE instead.
        val remaining = contactsProviderRepository.countRawContacts(email)
        if (remaining > 0) {
            Log.w(TAG, "Contacts purge left $remaining synced rows on the device")
        }
        return if (delete.isSuccess && remaining == 0) ContactPurgeOutcome.PURGED
        else ContactPurgeOutcome.INCOMPLETE
    }

    /**
     * Drop the CardDAV delta sync cursors of every login sharing [email] whose
     * contacts lived under the just-purged, email-keyed contacts system account.
     *
     * A purge is irreversible and wipes the account's RawContacts, so any surviving
     * `address_books` row for a same-email login now points at a token the server
     * still honors while the device holds none of the contacts it covers. Clearing
     * the cursor forces the next sync of that login onto the full-listing path, which
     * re-fetches everything. Scoped to CardDAV-capable logins: a non-CardDAV
     * same-email sibling never wrote contacts into the account, so it has no cursor to
     * clear. Idempotent — a login with no books is a no-op delete.
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
