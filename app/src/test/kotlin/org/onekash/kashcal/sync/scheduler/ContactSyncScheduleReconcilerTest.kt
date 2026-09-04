package org.onekash.kashcal.sync.scheduler

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.preferences.UserPreferencesRepository
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Unit tests for [ContactSyncScheduleReconciler].
 *
 * The reconciler is the single place that answers "should the periodic
 * contact-sync job be armed right now, and at what interval?", derived from the
 * accounts in the database and the global sync-interval preference. App start
 * drives it so a login enrolled before contact sync shipped — or an install whose
 * job was lost — heals itself rather than depending on a user toggle.
 *
 * [accountRepository] and [userPreferences] are deliberately NOT relaxed: a
 * data-bearing mock that defaulted to an empty account list or a null interval
 * would silently exercise the "do nothing" branch and pass a test that had stopped
 * covering the real path. [syncScheduler] is a Unit-returning side-effect
 * collaborator (a scheduler), so a relaxed mock is the sanctioned choice there.
 */
class ContactSyncScheduleReconcilerTest {

    private lateinit var accountRepository: AccountRepository
    private lateinit var userPreferences: UserPreferencesRepository
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var reconciler: ContactSyncScheduleReconciler

    @Before
    fun setup() {
        accountRepository = mockk()
        userPreferences = mockk()
        syncScheduler = mockk(relaxed = true)
        every { userPreferences.syncIntervalMs } returns flowOf(FIFTEEN_MIN_MS)
        reconciler = ContactSyncScheduleReconciler(accountRepository, userPreferences, syncScheduler)
    }

    private fun account(
        id: Long,
        provider: AccountProvider = AccountProvider.ICLOUD,
        contactSyncEnabled: Boolean = true,
    ) = Account(id = id, provider = provider, email = "user$id@example.test", contactSyncEnabled = contactSyncEnabled)

    private fun givenAccounts(vararg accounts: Account) {
        coEvery { accountRepository.getEnabledAccounts() } returns accounts.toList()
    }

    @Test
    fun `at least one CardDAV contact-sync account arms the job once at the global interval`() = runTest {
        givenAccounts(account(1))

        reconciler.reconcile()

        verify(exactly = 1) { syncScheduler.ensureContactSyncScheduled(FIFTEEN_MIN) }
        verify(exactly = 0) { syncScheduler.cancelPeriodicSync() }
    }

    @Test
    fun `no contact-sync account arms nothing and never cancels`() = runTest {
        // A calendar-only login: enabled, but contact sync is off. The shared job is
        // not ours to touch here (calendar sync's lifecycle owns cancellation).
        givenAccounts(account(1, contactSyncEnabled = false))

        reconciler.reconcile()

        verify(exactly = 0) { syncScheduler.ensureContactSyncScheduled(any()) }
        verify(exactly = 0) { syncScheduler.cancelPeriodicSync() }
    }

    @Test
    fun `a contact-sync account on a non-CardDAV provider does not arm the job`() = runTest {
        // contactSyncEnabled can only mean CardDAV contacts; guard against a stray
        // flag on a provider that has no CardDAV to sync.
        givenAccounts(account(1, provider = AccountProvider.LOCAL, contactSyncEnabled = true))

        reconciler.reconcile()

        verify(exactly = 0) { syncScheduler.ensureContactSyncScheduled(any()) }
    }

    @Test
    fun `no accounts at all arms nothing`() = runTest {
        givenAccounts()

        reconciler.reconcile()

        verify(exactly = 0) { syncScheduler.ensureContactSyncScheduled(any()) }
        verify(exactly = 0) { syncScheduler.cancelPeriodicSync() }
    }

    @Test
    fun `the manual-only interval sentinel arms no periodic job`() = runTest {
        // Long.MAX_VALUE is "manual only": as with calendar sync, no periodic job in
        // that mode even though a syncable account exists.
        givenAccounts(account(1))
        every { userPreferences.syncIntervalMs } returns flowOf(Long.MAX_VALUE)

        reconciler.reconcile()

        verify(exactly = 0) { syncScheduler.ensureContactSyncScheduled(any()) }
    }

    @Test
    fun `reconcile is idempotent - repeat calls ask for the same interval`() = runTest {
        givenAccounts(account(1))

        reconciler.reconcile()
        reconciler.reconcile()

        verify(exactly = 2) { syncScheduler.ensureContactSyncScheduled(FIFTEEN_MIN) }
    }

    @Test
    fun `only one CardDAV account is needed among several accounts`() = runTest {
        givenAccounts(
            account(1, provider = AccountProvider.LOCAL, contactSyncEnabled = false),
            account(2, provider = AccountProvider.CALDAV, contactSyncEnabled = true),
        )

        reconciler.reconcile()

        verify(exactly = 1) { syncScheduler.ensureContactSyncScheduled(FIFTEEN_MIN) }
    }

    @Test
    fun `a failing scheduler does not propagate out of reconcile`() = runTest {
        // Callers are bare application-scope launches with no exception handler, so a
        // throw here would crash the process on a recoverable WorkManager failure.
        givenAccounts(account(1))
        every { syncScheduler.ensureContactSyncScheduled(any()) } throws IllegalStateException("WorkManager unavailable")

        reconciler.reconcile()

        verify(exactly = 1) { syncScheduler.ensureContactSyncScheduled(FIFTEEN_MIN) }
    }

    @Test
    fun `a failing account read does not propagate out of reconcile`() = runTest {
        coEvery { accountRepository.getEnabledAccounts() } throws IllegalStateException("disk I/O error")

        reconciler.reconcile()

        verify(exactly = 0) { syncScheduler.ensureContactSyncScheduled(any()) }
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        // Best-effort is right for a real failure, but a cancelled caller must not be
        // reported as finished with the schedule half-applied.
        givenAccounts(account(1))
        every { syncScheduler.ensureContactSyncScheduled(any()) } throws CancellationException("caller cancelled")

        val thrown = runCatching { reconciler.reconcile() }.exceptionOrNull()

        assertTrue("reconcile should let cancellation through; got $thrown", thrown is CancellationException)
    }

    @Test
    fun `concurrent reconciles are serialized`() = runTest {
        // Mutations and app start can overlap, so read-decide-apply must be one step;
        // otherwise a stale read could win. The account read yields mid-flight so an
        // unlocked pass would interleave its enter/exit markers.
        val callLog = mutableListOf<String>()
        coEvery { accountRepository.getEnabledAccounts() } coAnswers {
            callLog += "enter"
            yield()
            yield()
            callLog += "exit"
            listOf(account(1))
        }

        val first = launch { reconciler.reconcile() }
        val second = launch { reconciler.reconcile() }
        first.join()
        second.join()

        assertEquals(
            "interleaved enter/exit markers mean read-decide-apply is not atomic",
            listOf("enter", "exit", "enter", "exit"),
            callLog,
        )
    }

    private companion object {
        const val FIFTEEN_MIN_MS = 15L * 60 * 1000
        const val FIFTEEN_MIN = 15L
    }
}
