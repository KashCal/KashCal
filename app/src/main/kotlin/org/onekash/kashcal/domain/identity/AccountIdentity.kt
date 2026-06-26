package org.onekash.kashcal.domain.identity

import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.util.AddressNormalizer

// Loose email-shape check for the empty-set fallback below — rejects
// non-email logins like Nextcloud's "alice" while accepting "alice@x.com".
private val EMAIL_SHAPE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

/**
 * This account's usable calendar-user-addresses, in preference order.
 * Returns [Account.calendarUserAddresses] when populated (discovery hoists a
 * `mailto:` to index 0); otherwise falls back to [Account.email] when the
 * login is email-shaped, so a PROPFIND failure or an account saved before
 * address discovery existed still resolves the typical iCloud/Apple-ID case.
 * Empty when neither is available (the account is not scheduling-enabled).
 *
 * `firstOrNull()` of this is the address to emit as ORGANIZER on locally
 * authored events; the whole list is what identity-matching scans.
 */
fun Account.effectiveAddresses(): List<String> =
    calendarUserAddresses.ifEmpty {
        if (email.matches(EMAIL_SHAPE)) listOf(email) else emptyList()
    }

/**
 * Returns true when [address] (any RFC 5545 §3.3.3 CAL-ADDRESS form)
 * refers to this account, canonicalizing both sides.
 */
fun Account.matchesAttendee(address: String): Boolean {
    val effective = effectiveAddresses()
    if (effective.isEmpty()) return false
    val target = AddressNormalizer.canonical(address)
    return effective.any { stored ->
        AddressNormalizer.canonical(stored) == target
    }
}
