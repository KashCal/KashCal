package org.onekash.kashcal.domain.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the pure release-filter logic that decides which release-notes
 * entries to surface in the What's New sheet.
 *
 * Gate contract:
 *   - Fresh install (lastShown == 0) returns empty so the caller can record
 *     current and stay silent on first launch.
 *   - Otherwise returns every release whose versionCode is strictly greater
 *     than lastShown AND less than or equal to currentVersion, sorted
 *     ascending so the sheet reads oldest-to-newest.
 *
 * Caller is responsible for persisting lastShown after dismissal (or on
 * fresh install when this returns empty).
 */
class WhatsNewGateTest {

    private fun rel(v: Int) = ReleaseNote(versionCode = v, titleRes = 0, bulletsRes = 0)

    @Test
    fun `no releases authored yet on fresh install returns empty`() {
        val out = WhatsNewGate.releasesToShow(
            releases = emptyList(),
            lastShownVersion = 0,
            currentVersion = 295,
        )
        assertEquals(emptyList<ReleaseNote>(), out)
    }

    @Test
    fun `no releases authored yet for returning user returns empty`() {
        val out = WhatsNewGate.releasesToShow(
            releases = emptyList(),
            lastShownVersion = 290,
            currentVersion = 295,
        )
        assertEquals(emptyList<ReleaseNote>(), out)
    }

    @Test
    fun `fresh install suppresses content even when releases authored`() {
        // lastShown == 0 sentinel: never pester first-launch users with
        // history they have no context for.
        val out = WhatsNewGate.releasesToShow(
            releases = listOf(rel(295)),
            lastShownVersion = 0,
            currentVersion = 295,
        )
        assertEquals(emptyList<ReleaseNote>(), out)
    }

    @Test
    fun `upgrade across one release shows that release`() {
        val r = rel(295)
        val out = WhatsNewGate.releasesToShow(
            releases = listOf(r),
            lastShownVersion = 290,
            currentVersion = 295,
        )
        assertEquals(listOf(r), out)
    }

    @Test
    fun `upgrade across multiple releases shows all in ascending order`() {
        val r294 = rel(294)
        val r295 = rel(295)
        val out = WhatsNewGate.releasesToShow(
            releases = listOf(r294, r295),
            lastShownVersion = 290,
            currentVersion = 295,
        )
        assertEquals(listOf(r294, r295), out)
    }

    @Test
    fun `upgrade past versions still shows skipped releases`() {
        // User updated 290 -> 300 directly, releases existed at 294 and 295.
        // Both should still be surfaced even though current is 300.
        val r294 = rel(294)
        val r295 = rel(295)
        val out = WhatsNewGate.releasesToShow(
            releases = listOf(r294, r295),
            lastShownVersion = 290,
            currentVersion = 300,
        )
        assertEquals(listOf(r294, r295), out)
    }

    @Test
    fun `already seen latest returns empty`() {
        val out = WhatsNewGate.releasesToShow(
            releases = listOf(rel(294), rel(295)),
            lastShownVersion = 295,
            currentVersion = 295,
        )
        assertEquals(emptyList<ReleaseNote>(), out)
    }

    @Test
    fun `partially seen returns only unseen`() {
        val r295 = rel(295)
        val out = WhatsNewGate.releasesToShow(
            releases = listOf(rel(294), r295),
            lastShownVersion = 294,
            currentVersion = 295,
        )
        assertEquals(listOf(r295), out)
    }

    @Test
    fun `out-of-order input is sorted ascending`() {
        val r293 = rel(293)
        val r295 = rel(295)
        val out = WhatsNewGate.releasesToShow(
            releases = listOf(r295, r293),
            lastShownVersion = 290,
            currentVersion = 295,
        )
        assertEquals(listOf(r293, r295), out)
    }

    @Test
    fun `release with versionCode above current is hidden`() {
        // Author added Release(300, ...) but the user is on 295. Don't show
        // release notes for a version the user isn't running yet.
        val out = WhatsNewGate.releasesToShow(
            releases = listOf(rel(300)),
            lastShownVersion = 290,
            currentVersion = 295,
        )
        assertEquals(emptyList<ReleaseNote>(), out)
    }

    @Test
    fun `mix of past, current, and future releases filters correctly`() {
        val r294 = rel(294)
        val r295 = rel(295)
        val out = WhatsNewGate.releasesToShow(
            releases = listOf(rel(280), r294, r295, rel(310)),
            lastShownVersion = 290,
            currentVersion = 295,
        )
        // 280 already seen, 294 + 295 unseen and reachable, 310 not yet shipped
        assertEquals(listOf(r294, r295), out)
    }
}
