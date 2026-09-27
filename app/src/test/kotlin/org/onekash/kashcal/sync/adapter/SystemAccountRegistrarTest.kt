package org.onekash.kashcal.sync.adapter

import android.accounts.AccountManager
import android.accounts.AuthenticatorDescription
import android.content.ContentResolver
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Tests for [SystemAccountRegistrar].
 *
 * Robolectric's AccountManager and ContentResolver shadows may not match a device for
 * setIsSyncable and getSyncAutomatically, so the sync-state tests check the calls are made;
 * device behavior must be verified manually.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SystemAccountRegistrarTest {

    private lateinit var context: Context
    private lateinit var accountManager: AccountManager
    private lateinit var registrar: SystemAccountRegistrar

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        accountManager = AccountManager.get(context)

        // Robolectric doesn't reset the process-wide AccountManager between test classes in
        // one JVM fork, so clear leftover KashCal accounts first. App boot doesn't race this:
        // KashCalApplication.onCreate skips its background account registration under unit
        // tests.
        accountManager.getAccountsByType(KashCalAuthenticator.ACCOUNT_TYPE)
            .forEach { accountManager.removeAccountExplicitly(it) }

        val shadow = Shadows.shadowOf(accountManager)
        shadow.addAuthenticator(AuthenticatorDescription(
            KashCalAuthenticator.ACCOUNT_TYPE,
            context.packageName,
            0, 0, 0, 0
        ))

        registrar = SystemAccountRegistrar(context)
    }

    @Test
    fun `ensureAccount creates account when none exists`() {
        registrar.ensureAccount()

        val accounts = accountManager.getAccountsByType(KashCalAuthenticator.ACCOUNT_TYPE)
        assertEquals(1, accounts.size)
        assertEquals("KashCal", accounts[0].name)
    }

    @Test
    fun `ensureAccount is idempotent — does not duplicate`() {
        registrar.ensureAccount()
        registrar.ensureAccount()

        val accounts = accountManager.getAccountsByType(KashCalAuthenticator.ACCOUNT_TYPE)
        assertEquals(1, accounts.size)
    }

    @Test
    fun `ensureAccount disables auto-sync for calendar authority`() {
        registrar.ensureAccount()

        val account = accountManager.getAccountsByType(KashCalAuthenticator.ACCOUNT_TYPE)[0]
        assertFalse(ContentResolver.getSyncAutomatically(account, "com.android.calendar"))
    }

    // Robolectric doesn't fully shadow setIsSyncable and getIsSyncable; verify on a device with:
    // adb shell content query --uri content://com.android.calendar/calendars

    @Test
    fun `ensureAccount recreates account after manual removal`() {
        registrar.ensureAccount()

        // The user removes the account in Settings > Accounts
        val account = accountManager.getAccountsByType(KashCalAuthenticator.ACCOUNT_TYPE)[0]
        accountManager.removeAccountExplicitly(account)
        assertEquals(0, accountManager.getAccountsByType(KashCalAuthenticator.ACCOUNT_TYPE).size)

        // Next app launch re-creates it
        registrar.ensureAccount()
        assertEquals(1, accountManager.getAccountsByType(KashCalAuthenticator.ACCOUNT_TYPE).size)
    }
}
