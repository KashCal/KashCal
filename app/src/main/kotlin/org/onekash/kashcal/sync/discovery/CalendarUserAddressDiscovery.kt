package org.onekash.kashcal.sync.discovery

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult

/**
 * Discovers and persists the account's `calendar-user-address-set` (RFC 6638 §2.4.1).
 *
 * Never fails the sync. A failed request (HTTP, network, timeout, malformed XML, empty reply)
 * logs a warning and persists an empty list; an empty list falls back to an email-shaped login
 * in [org.onekash.kashcal.domain.identity.effectiveAddresses]. A throw, such as a failed write,
 * is logged and persists nothing.
 *
 * Logs only the count and the first 4 characters of one address, never the full set (PII).
 */
internal suspend fun persistCalendarUserAddresses(
    client: CalDavClient,
    principalUrl: String,
    accountId: Long,
    accountRepository: AccountRepository,
    tag: String
) {
    try {
        val result = client.discoverCalendarUserAddresses(principalUrl)
        val addresses = if (result.isSuccess()) {
            (result as CalDavResult.Success).data
        } else {
            val error = result as CalDavResult.Error
            Log.w(tag, "calendar-user-address-set discovery failed (HTTP ${error.code}); persisting empty list")
            emptyList()
        }
        val sample = addresses.firstOrNull()?.take(4) ?: ""
        Log.i(tag, "Discovered ${addresses.size} CUAs (sample=${sample}***) for account $accountId")
        accountRepository.updateCalendarUserAddresses(accountId, addresses)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A write failure (e.g. SQLiteException) must not abort the sync.
        Log.w(tag, "calendar-user-address-set discovery failed for account $accountId: ${e.message}")
    }
}
