package org.onekash.kashcal.data.credential

import android.content.SharedPreferences
import javax.crypto.AEADBadTagException

/**
 * Opens an encrypted prefs store, recovering from a keyset the master key no longer decrypts.
 *
 * When the Keystore master key is lost or replaced, the store's keysets fail to decrypt and
 * opening throws `AEADBadTagException` (directly or as a cause) on every attempt, so the store
 * could never be used again (#370). The opener retries once, since a single failure may not
 * repeat; if the retry fails the same way it calls reset, which deletes the store and with it
 * the credentials no key can read any more, and opens once more. Every other failure, for example
 * a Keystore that is busy or unavailable, leaves the store untouched and reports it unavailable.
 * `EncryptedStoreOpenerTest` pins each path.
 */
internal object EncryptedStoreOpener {

    /** The opened store, or null with the [error] that kept it closed. */
    class Result(val prefs: SharedPreferences?, val error: Exception?)

    fun open(create: () -> SharedPreferences, reset: () -> Unit): Result {
        repeat(2) {
            try {
                return Result(create(), null)
            } catch (e: Exception) {
                if (!e.isKeysetMismatch()) return Result(null, e)
            }
        }
        reset()
        return try {
            Result(create(), null)
        } catch (e: Exception) {
            Result(null, e)
        }
    }

    private fun Throwable.isKeysetMismatch(): Boolean =
        generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is AEADBadTagException }

    private const val MAX_CAUSE_DEPTH = 8
}
