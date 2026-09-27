package org.onekash.kashcal.domain.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests [WhatsNewSeeder.decideSeed]. The DataStore default of 0 can't tell a new install from a
 * user whose DataStore predates What's New; without a seed, the first release with notes would
 * show nothing to everyone. The seed comes from KashCalApplication's `previous_version_code`
 * when DataStore holds 0.
 */
class WhatsNewSeederTest {

    @Test
    fun `already tracked returns null no-op`() {
        // dsLastShown > 0 means the user has been observed before; never overwrite.
        assertNull(WhatsNewSeeder.decideSeed(dsLastShown = 596, prevVersion = 596, current = 598))
        assertNull(WhatsNewSeeder.decideSeed(dsLastShown = 597, prevVersion = 597, current = 598))
        assertNull(WhatsNewSeeder.decideSeed(dsLastShown = 1, prevVersion = 0, current = 598))
    }

    @Test
    fun `true fresh install seeds to current and stays silent`() {
        // No DataStore record and no upgrade history: the first launch ever. Record current
        // so later upgrades are detected.
        assertEquals(598, WhatsNewSeeder.decideSeed(dsLastShown = 0, prevVersion = 0, current = 598))
    }

    @Test
    fun `pre-existing user before WhatsNew shipped seeds to actual prior version`() {
        // dsLastShown = 0 (the key didn't exist yet) but prevVersion shows an upgrade from a
        // real prior version. Seed to prevVersion so the gate shows notes for versionCodes
        // prevVersion+1..current.
        assertEquals(596, WhatsNewSeeder.decideSeed(dsLastShown = 0, prevVersion = 596, current = 598))
    }

    @Test
    fun `pre-existing user upgrading multiple versions seeds to last known`() {
        assertEquals(500, WhatsNewSeeder.decideSeed(dsLastShown = 0, prevVersion = 500, current = 598))
    }

    @Test
    fun `prev equals current means no upgrade happened on this run`() {
        // handleAppUpgrade writes prev only on a version change, as the old versionCode, so
        // it doesn't produce prev == current; the seeder treats it like a new install and
        // shows nothing.
        assertEquals(598, WhatsNewSeeder.decideSeed(dsLastShown = 0, prevVersion = 598, current = 598))
    }

    @Test
    fun `prev greater than current is capped at current`() {
        // A downgrade (sideloading an older APK) must not store a future versionCode that
        // would hide all notes forever.
        assertEquals(598, WhatsNewSeeder.decideSeed(dsLastShown = 0, prevVersion = 700, current = 598))
    }
}
