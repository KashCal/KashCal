package org.onekash.kashcal.domain.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the seed decision that fixes the "first WhatsNew release goes
 * silent for everyone" trap. The DataStore-default-of-0 sentinel can't tell
 * a true fresh install from a pre-existing user whose DataStore predates
 * this feature — so we seed from the application-level upgrade signal
 * (KashCalApplication's previous_version_code) when DataStore is empty.
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
        // No DataStore record AND no upgrade history: this is the first launch
        // ever. Record current so future upgrades are detected.
        assertEquals(598, WhatsNewSeeder.decideSeed(dsLastShown = 0, prevVersion = 0, current = 598))
    }

    @Test
    fun `pre-existing user before WhatsNew shipped seeds to actual prior version`() {
        // dsLastShown = 0 (key didn't exist before) but prevVersion shows the
        // user upgraded from a real prior version. Seed to prevVersion so the
        // gate fires for any release notes authored at versionCodes prevVersion+1..current.
        assertEquals(596, WhatsNewSeeder.decideSeed(dsLastShown = 0, prevVersion = 596, current = 598))
    }

    @Test
    fun `pre-existing user upgrading multiple versions seeds to last known`() {
        assertEquals(500, WhatsNewSeeder.decideSeed(dsLastShown = 0, prevVersion = 500, current = 598))
    }

    @Test
    fun `prev equals current means no upgrade happened on this run`() {
        // handleAppUpgrade rewrites prev=current when no upgrade. From the
        // seeder's perspective this is identical to a fresh install — silent.
        assertEquals(598, WhatsNewSeeder.decideSeed(dsLastShown = 0, prevVersion = 598, current = 598))
    }

    @Test
    fun `prev greater than current is capped at current`() {
        // Defensive: a downgrade (sideload of older APK) shouldn't store a
        // future versionCode that filters out all content forever.
        assertEquals(598, WhatsNewSeeder.decideSeed(dsLastShown = 0, prevVersion = 700, current = 598))
    }
}
