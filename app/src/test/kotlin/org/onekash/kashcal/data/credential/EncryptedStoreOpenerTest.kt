package org.onekash.kashcal.data.credential

import android.content.SharedPreferences
import io.mockk.mockk
import java.io.CharConversionException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Tests `EncryptedStoreOpener`: which open failures reset the store, and how often it retries. */
class EncryptedStoreOpenerTest {

    private val store: SharedPreferences = mockk()

    /**
     * Runs the opener over [outcomes], one per create call: a store to return or an exception to
     * throw.
     */
    private class Scenario(vararg outcomes: Any) {
        private val queue = ArrayDeque(outcomes.toList())
        var creates = 0
        var resets = 0

        fun open() = EncryptedStoreOpener.open(
            create = {
                creates++
                when (val next = queue.removeFirst()) {
                    is Exception -> throw next
                    else -> next as SharedPreferences
                }
            },
            reset = { resets++ },
        )
    }

    @Test
    fun `a store that opens is returned without a retry or a reset`() {
        val scenario = Scenario(store)

        val result = scenario.open()

        assertSame(store, result.prefs)
        assertNull(result.error)
        assertEquals(1, scenario.creates)
        assertEquals(0, scenario.resets)
    }

    @Test
    fun `AEADBadTag once then success does not reset`() {
        val scenario = Scenario(AEADBadTagException(), store)

        val result = scenario.open()

        assertSame(store, result.prefs)
        assertNull(result.error)
        assertEquals(2, scenario.creates)
        assertEquals(0, scenario.resets)
    }

    @Test
    fun `AEADBadTag twice resets once and returns the recreated store`() {
        val scenario = Scenario(AEADBadTagException(), AEADBadTagException(), store)

        val result = scenario.open()

        assertSame(store, result.prefs)
        assertNull(result.error)
        assertEquals(3, scenario.creates)
        assertEquals(1, scenario.resets)
    }

    @Test
    fun `a failure after the reset gives no store and does not reset again`() {
        val afterReset = KeyStoreException("still broken")
        val scenario = Scenario(AEADBadTagException(), AEADBadTagException(), afterReset)

        val result = scenario.open()

        assertNull(result.prefs)
        assertSame(afterReset, result.error)
        assertEquals(3, scenario.creates)
        assertEquals(1, scenario.resets)
    }

    @Test
    fun `AEADBadTag wrapped as a cause counts as a keyset the master key no longer decrypts`() {
        listOf(
            GeneralSecurityException("wrapped", AEADBadTagException()),
            IOException("wrapped", AEADBadTagException()),
            SecurityException("wrapped", AEADBadTagException()),
        ).forEach { wrapped ->
            val scenario = Scenario(wrapped, wrapped, store)

            val result = scenario.open()

            assertSame("for $wrapped", store, result.prefs)
            assertEquals("for $wrapped", 1, scenario.resets)
        }
    }

    @Test
    fun `other failures keep the store unavailable without a retry or a reset`() {
        listOf(
            KeyStoreException("keystore busy"),
            ProviderException("keystore unavailable"),
            GeneralSecurityException("other"),
            IOException("io"),
            CharConversionException("keyset is not hex"),
            SecurityException("no AEADBadTag cause"),
            IllegalStateException("runtime"),
        ).forEach { failure ->
            val scenario = Scenario(failure)

            val result = scenario.open()

            assertNull("for $failure", result.prefs)
            assertSame("for $failure", failure, result.error)
            assertEquals("for $failure", 1, scenario.creates)
            assertEquals("for $failure", 0, scenario.resets)
        }
    }

    @Test
    fun `AEADBadTag then a different failure on the retry does not reset`() {
        val retryFailure = ProviderException("keystore unavailable")
        val scenario = Scenario(AEADBadTagException(), retryFailure)

        val result = scenario.open()

        assertNull(result.prefs)
        assertSame(retryFailure, result.error)
        assertEquals(2, scenario.creates)
        assertEquals(0, scenario.resets)
    }
}
