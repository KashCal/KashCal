package org.onekash.kashcal.domain.whatsnew

/**
 * Picks the release notes the What's New sheet shows.
 *
 * The caller stores the last shown version as BuildConfig.VERSION_CODE when the sheet is
 * dismissed, and seeds it before the first check ([WhatsNewSeeder]).
 */
object WhatsNewGate {

    /**
     * Returns the entries of [releases] with a versionCode above [lastShownVersion] and at most
     * [currentVersion], ascending. Returns none when [lastShownVersion] is 0, the value before
     * anything was stored or seeded.
     */
    fun releasesToShow(
        releases: List<ReleaseNote>,
        lastShownVersion: Int,
        currentVersion: Int,
    ): List<ReleaseNote> {
        if (lastShownVersion == 0) return emptyList()
        return releases
            .filter { it.versionCode in (lastShownVersion + 1)..currentVersion }
            .sortedBy { it.versionCode }
    }
}
