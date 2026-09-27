package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [SettingsDestination]'s depths, its forward/back decision and [SettingsDestination.from].
 *
 * The Settings transition slides the incoming screen in from the trailing edge when the user
 * drills into a detail and reverses when backing out to the root. These tests pin the pure
 * forward/back decision; the Compose slide itself is verified on device, not here.
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
