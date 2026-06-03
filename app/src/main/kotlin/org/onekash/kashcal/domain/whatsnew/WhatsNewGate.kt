package org.onekash.kashcal.domain.whatsnew

/**
 * Pure release-filter logic for the What's New sheet.
 *
 * The caller persists [lastShownVersion] to BuildConfig.VERSION_CODE after
 * dismissal, and also on fresh install (when this returns empty because of
 * the lastShown == 0 sentinel) so subsequent upgrades are detected normally.
 */
object WhatsNewGate {

    /**
     * @return the releases to surface, ascending by versionCode. Empty when:
     *   - [lastShownVersion] is 0 (fresh install or pre-existing user before
     *     this feature shipped — silent in both cases),
     *   - the user has already seen everything reachable on this version, or
     *   - the only entries authored target a versionCode the user isn't
     *     running yet.
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
