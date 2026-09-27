package org.onekash.kashcal.domain.identity

import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.util.AddressNormalizer

/**
 * Returns this account's usable calendar-user-addresses, in preference order.
 *
 * Returns [Account.calendarUserAddresses] when populated (discovery puts `preferred="1"`
 * entries first); otherwise falls back to [Account.email] when the login is email-shaped, so
 * a failed discovery or an account with no discovered addresses still resolves the typical
 * iCloud Apple ID case. Empty when neither is available (the account is not
 * scheduling-enabled).
 *
 * The first email-shaped entry is emitted as ORGANIZER on locally authored events; identity
 * matching ([matchesAttendee]) scans the whole list.
 */
fun Account.effectiveAddresses(): List<String> =
    calendarUserAddresses.ifEmpty {
        // Only an email-shaped login is usable as an address (not Nextcloud's "alice").
        if (AddressNormalizer.isEmailShaped(email)) listOf(email) else emptyList()
    }

/**
 * Returns true when [address], in any RFC 5545 §3.3.3 CAL-ADDRESS form, is one of
 * [effectiveAddresses] once both sides are canonicalized.
 */
fun Account.matchesAttendee(address: String): Boolean {
    val effective = effectiveAddresses()
    if (effective.isEmpty()) return false
    val target = AddressNormalizer.canonical(address)
    return effective.any { stored ->
        AddressNormalizer.canonical(stored) == target
    }
}
