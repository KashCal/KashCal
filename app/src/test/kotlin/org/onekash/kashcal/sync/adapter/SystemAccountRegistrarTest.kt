package org.onekash.kashcal.sync.adapter

import android.accounts.AccountManager
import android.accounts.AuthenticatorDescription
import android.content.ContentResolver
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Tests for [SystemAccountRegistrar].
 *
 * NOTE: Robolectric's AccountManager/ContentResolver shadows may not perfectly
 * replicate device behavior for setIsSyncable/getSyncAutomatically. Sync state
 * tests verify the calls are made correctly; actual device behavior must be
 * verified manually via the adb commands in the plan.
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

        // Register authenticator type so Robolectric's ShadowAccountManager
        // accepts addAccountExplicitly() calls for our account type.
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

    // setIsSyncable/getIsSyncable not fully shadowed by Robolectric — verify on device via:
    // adb shell content query --uri content://com.android.calendar/calendars

    @Test
    fun `ensureAccount recreates account after manual removal`() {
        registrar.ensureAccount()

        // Simulate user removing account from Settings > Accounts
        val account = accountManager.getAccountsByType(KashCalAuthenticator.ACCOUNT_TYPE)[0]
        accountManager.removeAccountExplicitly(account)
        assertEquals(0, accountManager.getAccountsByType(KashCalAuthenticator.ACCOUNT_TYPE).size)

        // Next app launch re-creates it
        registrar.ensureAccount()
        assertEquals(1, accountManager.getAccountsByType(KashCalAuthenticator.ACCOUNT_TYPE).size)
    }
}
