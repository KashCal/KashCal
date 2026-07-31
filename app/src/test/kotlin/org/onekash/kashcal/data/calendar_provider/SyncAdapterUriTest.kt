package org.onekash.kashcal.data.calendar_provider

import android.provider.CalendarContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the sync-adapter ExtendedProperties write URI.
 *
 * Writing ExtendedProperties on a synced calendar silently no-ops unless the
 * writer identifies as a sync adapter: CALLER_IS_SYNCADAPTER=true plus the
 * owning calendar's account name/type as query params. This helper builds that
 * URI; these tests pin the three load-bearing params.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SyncAdapterUriTest {

    @Test
    fun `appends caller-is-sync-adapter true`() {
        val uri = syncAdapterExtendedPropertiesUri("alice@example.test", "com.example")
        assertEquals("true", uri.getQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER))
    }

    @Test
    fun `appends account name and type`() {
        val uri = syncAdapterExtendedPropertiesUri("alice@example.test", "com.example")
        assertEquals(
            "alice@example.test",
            uri.getQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME)
        )
        assertEquals(
            "com.example",
            uri.getQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE)
        )
    }

    @Test
    fun `builds on the ExtendedProperties content uri`() {
        val uri = syncAdapterExtendedPropertiesUri("alice@example.test", "com.example")
        assertTrue(
            uri.toString().startsWith(CalendarContract.ExtendedProperties.CONTENT_URI.toString())
        )
    }
}
