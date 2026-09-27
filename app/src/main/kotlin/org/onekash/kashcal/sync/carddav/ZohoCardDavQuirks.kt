package org.onekash.kashcal.sync.carddav

/**
 * Pins Zoho's CardDAV host, which differs from its CalDAV host.
 *
 * Zoho serves contacts from `contacts.zoho.com` and calendars from `calendar.zoho.com`. The
 * account's `homeSetUrl` points at the calendar host, so the generic path (contacts base
 * derived from it) would target the wrong host, and Zoho publishes no `_carddavs` SRV record
 * to find the right one. The contacts host is pinned as a bootstrap constant, as for iCloud.
 *
 * The login email is not a reliable signal (a Zoho account can sign in with a custom domain or a
 * Gmail-backed address), so this provider is selected by the account's server host (a
 * `.zoho.com` home host), not its email. The host is pinned and unrelated to the email domain,
 * so [discoverHostViaDns] is false: an email-domain SRV lookup could only misdirect it.
 *
 * Only the verified `contacts.zoho.com` (Zoho's global `.com` service) is pinned. Zoho's
 * regional data centers (`.eu`, `.in`, `.com.cn`) are untested, so a regional home host
 * deliberately falls through to generic discovery rather than a mirrored host not confirmed to
 * exist.
 */
class ZohoCardDavQuirks : DefaultCardDavQuirks(
    serverBaseUrl = "https://contacts.zoho.com",
    providerId = "zoho",
    displayName = "Zoho",
    requiresAppSpecificPassword = true,
    discoverHostViaDns = false,
)
