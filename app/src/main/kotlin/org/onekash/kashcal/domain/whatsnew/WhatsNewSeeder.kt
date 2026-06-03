package org.onekash.kashcal.domain.whatsnew

/**
 * Decides what value, if any, to seed into LAST_WHATSNEW_VERSION_SHOWN
 * before the gate runs.
 *
 * The DataStore default of 0 conflates two distinct populations: brand-new
 * installs and existing users whose DataStore predates this feature. Without
 * a seed, the first release that actually authors release-notes content
 * goes silent for *everyone* — both populations look identical to the gate.
 *
 * Fix: read KashCalApplication's previous_version_code (a pre-existing
 * SharedPreferences signal) and seed DataStore from it when empty. Existing
 * users get their actual prior versionCode; true fresh installs (no upgrade
 * history) still record current and stay silent.
 */
object WhatsNewSeeder {

    /**
     * @param dsLastShown current value of LAST_WHATSNEW_VERSION_SHOWN in
     *   DataStore (0 if never written).
     * @param prevVersion the *previous* versionCode captured by
     *   KashCalApplication before it was overwritten with current
     *   (0 if no upgrade history is available).
     * @param current BuildConfig.VERSION_CODE.
     *
     * @return the value to write to DataStore, or null when no seeding is
     *   needed. Returns null when DataStore already has a value.
     */
    fun decideSeed(dsLastShown: Int, prevVersion: Int, current: Int): Int? {
        if (dsLastShown > 0) return null
        // No prior upgrade record (true fresh install) or prev == current
        // (no upgrade on this run): record current so future upgrades are
        // detected, but don't surface any banner this launch.
        if (prevVersion <= 0 || prevVersion >= current) return current
        // Existing user from before this feature shipped: seed with their
        // last-known version so the gate fires for any content authored at
        // versionCodes (prevVersion + 1)..current.
        return prevVersion
    }
}
