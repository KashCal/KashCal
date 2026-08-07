package org.onekash.kashcal.sync.carddav

/**
 * Zoho-specific CardDAV quirks.
 *
 * Zoho is reached through the generic CardDAV path, but its contacts collection
 * lives on a different host than its calendars: contacts are served from
 * `contacts.zoho.com` while the CalDAV endpoint is `calendar.zoho.com`. The
 * account's `homeSetUrl` therefore points at the *calendar* host, so deriving the
 * contacts base from it (the generic behavior) would target the wrong host, and
 * Zoho publishes no `_carddavs` SRV record to discover the right one. The contacts
 * host is instead pinned as a bootstrap constant, mirroring the iCloud precedent.
 *
 * The login email is not a reliable signal — a Zoho account can authenticate with
 * a custom domain or a Gmail-backed address — so this provider is selected by the
 * account's *server* host (a `.zoho.com` home host), not its email. Because the
 * host is pinned and unrelated to the email domain, [discoverHostViaDns] is false:
 * an email-domain SRV lookup could only misdirect it.
 *
 * Scope: only the verified `contacts.zoho.com` (Zoho's global `.com` service) is
 * pinned. Zoho's regional data centers (`.eu`, `.in`, `.com.cn`) are untested, so
 * a regional home host deliberately falls through to generic discovery rather than
 * being routed to a mirrored host we have not confirmed exists.
 *
 * Naming mirrors the existing behavior-descriptive quirks classes.
 */
class ZohoCardDavQuirks : DefaultCardDavQuirks(
    serverBaseUrl = "https://contacts.zoho.com",
    providerId = "zoho",
    displayName = "Zoho",
    requiresAppSpecificPassword = true,
    discoverHostViaDns = false,
)
