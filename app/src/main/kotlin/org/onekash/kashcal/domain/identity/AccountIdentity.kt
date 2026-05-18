package org.onekash.kashcal.domain.identity

import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.util.AddressNormalizer

// Loose email-shape check for the empty-set fallback below — rejects
// non-email logins like Nextcloud's "alice" while accepting "alice@x.com".
private val EMAIL_SHAPE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

/**
 * Returns true when [address] (any RFC 5545 §3.3.3 CAL-ADDRESS form)
 * refers to this account. Falls back to [Account.email] when
 * [Account.calendarUserAddresses] is empty AND the login is email-shaped,
 * so PROPFIND failure or pre-A2.0 rows still match the typical
 * iCloud/Apple-ID case.
 */
fun Account.matchesAttendee(address: String): Boolean {
    val effective = calendarUserAddresses.ifEmpty {
        if (email.matches(EMAIL_SHAPE)) listOf(email) else emptyList()
    }
    if (effective.isEmpty()) return false
    val target = AddressNormalizer.canonical(address)
    return effective.any { stored ->
        AddressNormalizer.canonical(stored) == target
    }
}
