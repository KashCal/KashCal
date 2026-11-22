package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncScopeTest {

    private fun calDav(id: Long, calendars: Int, enabled: Boolean = true) = CalDavAccountUiModel(
        id = id,
        email = "user$id@example.test",
        displayName = "Account $id",
        calendarCount = calendars,
        isEnabled = enabled,
    )

    @Test
    fun `nothing connected has no sync account`() {
        assertEquals(SyncScope(0, 0), syncScopeOf(ICloudConnectionState.NotConnected(), emptyList()))
    }

    @Test
    fun `connected and enabled iCloud counts with its calendars`() {
        val iCloud = ICloudConnectionState.Connected(accountId = 1, appleId = "user@example.test", calendarCount = 4)
        assertEquals(SyncScope(1, 4), syncScopeOf(iCloud, emptyList()))
    }

    @Test
    fun `iCloud set to Sync off does not count`() {
        val iCloud = ICloudConnectionState.Connected(
            accountId = 1, appleId = "user@example.test", calendarCount = 4, isEnabled = false,
        )
        assertEquals(SyncScope(0, 0), syncScopeOf(iCloud, emptyList()))
    }

    @Test
    fun `iCloud signing in does not count`() {
        assertEquals(SyncScope(0, 0), syncScopeOf(ICloudConnectionState.Connecting, emptyList()))
    }

    @Test
    fun `enabled CalDAV accounts are summed and Sync off ones skipped`() {
        val iCloud = ICloudConnectionState.Connected(accountId = 1, appleId = "user@example.test", calendarCount = 4)
        val accounts = listOf(calDav(2, 2), calDav(3, 5, enabled = false))
        assertEquals(SyncScope(2, 6), syncScopeOf(iCloud, accounts))
    }

    @Test
    fun `an enabled account with no calendars still counts as a sync account`() {
        assertEquals(SyncScope(1, 0), syncScopeOf(ICloudConnectionState.NotConnected(), listOf(calDav(2, 0))))
    }
}
