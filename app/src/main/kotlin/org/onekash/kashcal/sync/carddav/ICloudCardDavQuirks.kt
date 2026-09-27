package org.onekash.kashcal.sync.carddav

/**
 * Supplies iCloud's CardDAV provider metadata, including the app-specific password requirement.
 *
 * iCloud serves each account's contacts from a numbered partition host (for example
 * `p42-contacts.icloud.com`) reached by discovery from `contacts.icloud.com`. The
 * addressbook-home-set and address book hrefs come back as absolute URLs on that partition host.
 * Unlike the CalDAV side, which canonicalizes partition hosts to one base, CardDAV must keep
 * those hrefs verbatim and resolve later requests against the home URL's host, which
 * [OkHttpCardDavClient.listAddressBooks] does. Verbatim hrefs are the [DefaultCardDavQuirks]
 * default, so this subclass overrides nothing else.
 */
class ICloudCardDavQuirks : DefaultCardDavQuirks(
    serverBaseUrl = "https://contacts.icloud.com",
    providerId = "icloud",
    displayName = "iCloud",
    requiresAppSpecificPassword = true,
    // Fixed bootstrap host, unrelated to the Apple ID email domain: never discover it via
    // SRV on that domain (see CardDavQuirks.discoverHostViaDns).
    discoverHostViaDns = false,
)
