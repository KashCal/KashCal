package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SettingsDestination] navigation direction.
 *
 * The Settings root and its detail screens are swapped by [SettingsDestination];
 * the animated transition between them needs to know whether the user is drilling
 * *into* a detail (slide the incoming screen in from the trailing edge) or backing
 * *out* to the root (reverse). That forward/back decision is pure logic and is what
 * these tests pin down; the Compose slide itself is verified on device.
 */
class SettingsDestinationTest {

    @Test
    fun `root is the only depth-0 destination`() {
        assertEquals(0, SettingsDestination.Root.depth)
        assertTrue(SettingsDestination.Accounts.depth > 0)
        assertTrue(SettingsDestination.BirthdaysAnniversaries.depth > 0)
        assertTrue(SettingsDestination.Subscriptions.depth > 0)
        assertTrue(SettingsDestination.Tags.depth > 0)
        assertTrue(SettingsDestination.DeviceCalendars.depth > 0)
    }

    @Test
    fun `drilling from root into a detail slides forward`() {
        assertTrue(SettingsDestination.Root.isForwardTo(SettingsDestination.Accounts))
        assertTrue(SettingsDestination.Root.isForwardTo(SettingsDestination.BirthdaysAnniversaries))
        assertTrue(SettingsDestination.Root.isForwardTo(SettingsDestination.Subscriptions))
        assertTrue(SettingsDestination.Root.isForwardTo(SettingsDestination.Tags))
        assertTrue(SettingsDestination.Root.isForwardTo(SettingsDestination.DeviceCalendars))
    }

    @Test
    fun `backing from a detail out to root slides backward`() {
        assertFalse(SettingsDestination.Accounts.isForwardTo(SettingsDestination.Root))
        assertFalse(SettingsDestination.BirthdaysAnniversaries.isForwardTo(SettingsDestination.Root))
        assertFalse(SettingsDestination.Subscriptions.isForwardTo(SettingsDestination.Root))
        assertFalse(SettingsDestination.Tags.isForwardTo(SettingsDestination.Root))
        assertFalse(SettingsDestination.DeviceCalendars.isForwardTo(SettingsDestination.Root))
    }

    @Test
    fun `staying on the same destination is not treated as forward`() {
        assertFalse(SettingsDestination.Root.isForwardTo(SettingsDestination.Root))
        assertFalse(SettingsDestination.Accounts.isForwardTo(SettingsDestination.Accounts))
    }

    @Test
    fun `boolean flags resolve to the matching destination, detail winning over root`() {
        assertEquals(
            SettingsDestination.Root,
            SettingsDestination.from(
                accounts = false,
                birthdaysAnniversaries = false,
                subscriptions = false,
                tags = false,
                deviceCalendars = false,
            )
        )
        assertEquals(
            SettingsDestination.Accounts,
            SettingsDestination.from(
                accounts = true,
                birthdaysAnniversaries = false,
                subscriptions = false,
                tags = false,
                deviceCalendars = false,
            )
        )
        assertEquals(
            SettingsDestination.Tags,
            SettingsDestination.from(
                accounts = false,
                birthdaysAnniversaries = false,
                subscriptions = false,
                tags = true,
                deviceCalendars = false,
            )
        )
        assertEquals(
            SettingsDestination.DeviceCalendars,
            SettingsDestination.from(
                accounts = false,
                birthdaysAnniversaries = false,
                subscriptions = false,
                tags = false,
                deviceCalendars = true,
            )
        )
    }
}
