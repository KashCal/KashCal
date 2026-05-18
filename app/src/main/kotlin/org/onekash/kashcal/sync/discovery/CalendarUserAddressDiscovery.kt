package org.onekash.kashcal.sync.discovery

import android.util.Log
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult

/**
 * Discover and persist the user's `calendar-user-address-set` (RFC 6638
 * §2.4.1) for the given account. Non-fatal: any HTTP, network, timeout,
 * malformed-XML, or empty-response failure logs a Pattern-15 warning and
 * persists an empty list. The helper's email-shape fallback in
 * `Account.matchesAttendee` covers the empty-set case for accounts where
 * the login is itself an email.
 *
 * Pattern-15 logging: count + masked first-4-chars sample only; never
 * the full address-set (PII).
 */
internal suspend fun persistCalendarUserAddresses(
    client: CalDavClient,
    principalUrl: String,
    accountId: Long,
    accountRepository: AccountRepository,
    tag: String
) {
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
}
