package org.onekash.kashcal.domain.whatsnew

/**
 * Decides what to seed into LAST_WHATSNEW_VERSION_SHOWN before [WhatsNewGate] runs.
 *
 * The DataStore default of 0 looks the same for a new install and for a user who upgraded from a
 * version that never stored it, and the gate shows nothing for 0. The seed comes from
 * KashCalApplication's `previous_version_code` SharedPreferences value: an upgrading user gets
 * their prior versionCode, so notes for the versions in between show; a new install gets the
 * current version and sees nothing.
 */
object WhatsNewSeeder {

    /**
     * Returns the value to write, or null when [dsLastShown] is already set.
     *
     * @param dsLastShown the stored LAST_WHATSNEW_VERSION_SHOWN, 0 if never written.
     * @param prevVersion the versionCode KashCalApplication recorded before this one, 0 if none.
     * @param current BuildConfig.VERSION_CODE.
     */
    fun decideSeed(dsLastShown: Int, prevVersion: Int, current: Int): Int? {
        if (dsLastShown > 0) return null
        // No earlier version on record (a new install) or no upgrade: store current so later
        // upgrades are detected, and show nothing this launch.
        if (prevVersion <= 0 || prevVersion >= current) return current
        // An upgrade: the gate shows notes for versionCodes (prevVersion + 1)..current.
        return prevVersion
    }
}
