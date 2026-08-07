package org.onekash.kashcal.sync.adapter

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AuthenticatorDescription
import android.content.ContentResolver
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Tests for [ContactSystemAccountRegistrar].
 *
 * Unlike the singleton calendar account, contacts get ONE account per login,
 * NAMED AFTER THE LOGIN EMAIL, under the dedicated
 * `org.onekash.kashcal.contacts` type. Android surfaces the account *name* as
 * the Contacts source label, so email-naming is what the user sees.
 *
 * NOTE: Robolectric's AccountManager/ContentResolver shadows may not perfectly
 * replicate device behavior for setIsSyncable/getSyncAutomatically. These
 * verify the calls are made; actual device behavior is verified manually.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ContactSystemAccountRegistrarTest {

    private lateinit var context: Context
    private lateinit var accountManager: AccountManager
    private lateinit var registrar: ContactSystemAccountRegistrar

    private val email = "alice@example.test"
    private val other = "bob@example.test"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        accountManager = AccountManager.get(context)

        // AccountManager is a process-wide singleton Robolectric does not reset
        // between test classes in the same JVM fork. Clear any leftover contacts
        // accounts so each test starts from a known-empty state.
        accountManager.getAccountsByType(KashCalContactsAuthenticator.ACCOUNT_TYPE)
            .forEach { accountManager.removeAccountExplicitly(it) }

        val shadow = Shadows.shadowOf(accountManager)
        shadow.addAuthenticator(AuthenticatorDescription(
            KashCalContactsAuthenticator.ACCOUNT_TYPE,
            context.packageName,
            0, 0, 0, 0
        ))

        registrar = ContactSystemAccountRegistrar(context)
    }

    @Test
    fun `ensureAccount creates an account named after the login email`() {
        registrar.ensureAccount(email)

        val accounts = accountManager.getAccountsByType(KashCalContactsAuthenticator.ACCOUNT_TYPE)
        assertEquals(1, accounts.size)
        assertEquals(email, accounts[0].name)
    }

    @Test
    fun `ensureAccount is idempotent for the same login`() {
        registrar.ensureAccount(email)
        registrar.ensureAccount(email)

        val accounts = accountManager.getAccountsByType(KashCalContactsAuthenticator.ACCOUNT_TYPE)
        assertEquals(1, accounts.size)
    }

    @Test
    fun `two different logins create two distinct accounts`() {
        registrar.ensureAccount(email)
        registrar.ensureAccount(other)

        val names = accountManager
            .getAccountsByType(KashCalContactsAuthenticator.ACCOUNT_TYPE)
            .map { it.name }
            .toSet()
        assertEquals(setOf(email, other), names)
    }

    @Test
    fun `ensureAccount disables auto-sync for the contacts authority`() {
        registrar.ensureAccount(email)

        val account = accountManager.getAccountsByType(KashCalContactsAuthenticator.ACCOUNT_TYPE)[0]
        assertFalse(ContentResolver.getSyncAutomatically(account, "com.android.contacts"))
    }

    @Test
    fun `removeAccount removes only the matching login`() {
        registrar.ensureAccount(email)
        registrar.ensureAccount(other)

        registrar.removeAccount(email)

        val names = accountManager
            .getAccountsByType(KashCalContactsAuthenticator.ACCOUNT_TYPE)
            .map { it.name }
        assertEquals(listOf(other), names)
    }

    @Test
    fun `removeAccount is a no-op when the login has no account`() {
        // No account created for `email`; removal must not throw.
        registrar.removeAccount(email)

        assertTrue(
            accountManager.getAccountsByType(KashCalContactsAuthenticator.ACCOUNT_TYPE).isEmpty()
        )
    }

    @Test
    fun `removeAccount reports success when the account was actually removed`() {
        registrar.ensureAccount(email)

        assertTrue("removal of a present account succeeds", registrar.removeAccount(email))
    }

    @Test
    fun `removeAccount of an absent login reports success (nothing to remove)`() {
        // No matching account: there is nothing left behind, so the outcome is success.
        assertTrue(registrar.removeAccount(email))
    }

    @Test
    fun `removeAccount reports failure when AccountManager cannot remove the account`() {
        // A stuck removeAccountExplicitly (false return) must not be swallowed silently —
        // the caller needs to know the contacts account (and its RawContacts) survived.
        val am = mockk<AccountManager>()
        val stuck = Account(email, KashCalContactsAuthenticator.ACCOUNT_TYPE)
        every { am.getAccountsByType(KashCalContactsAuthenticator.ACCOUNT_TYPE) } returns arrayOf(stuck)
        every { am.removeAccountExplicitly(stuck) } returns false

        assertFalse(
            "a false removeAccountExplicitly surfaces as a failed removal",
            registrar.removeAccount(email, am),
        )
    }

    @Test
    fun `removeAccount reports failure when AccountManager throws`() {
        val am = mockk<AccountManager>()
        every { am.getAccountsByType(KashCalContactsAuthenticator.ACCOUNT_TYPE) } throws
            SecurityException("cannot manage accounts")

        assertFalse(registrar.removeAccount(email, am))
    }
}
