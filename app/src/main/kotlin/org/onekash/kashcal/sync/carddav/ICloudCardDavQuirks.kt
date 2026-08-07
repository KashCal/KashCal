package org.onekash.kashcal.sync.carddav

/**
 * iCloud-specific CardDAV quirks.
 *
 * iCloud's contacts service serves each account from a numbered partition host
 * (for example `p42-contacts.icloud.com`) reached after discovery from the
 * `contacts.icloud.com` entry point. The addressbook-home-set and address book
 * collection hrefs come back as absolute URLs pointing at that partition host,
 * so — unlike the CalDAV side, which canonicalizes partition hosts back to a
 * single base — the CardDAV read path must keep those absolute hrefs verbatim
 * and resolve subsequent requests against the home URL's host (the client
 * derives its base host from the home-set URL for exactly this reason). That
 * verbatim-href behavior is already the [DefaultCardDavQuirks] default, so this
 * subclass only supplies iCloud's provider metadata — notably the app-specific
 * password requirement.
 *
 * Naming mirrors the existing `ICloudQuirks` precedent (behavior-descriptive,
 * not a hardware/OS-vendor label).
 */
class ICloudCardDavQuirks : DefaultCardDavQuirks(
    serverBaseUrl = "https://contacts.icloud.com",
    providerId = "icloud",
    displayName = "iCloud",
    requiresAppSpecificPassword = true,
    // Fixed bootstrap host, unrelated to the Apple ID email domain — never
    // discover it via SRV on that domain (see DefaultCardDavQuirks.discoverHostViaDns).
    discoverHostViaDns = false,
)
