package org.onekash.kashcal.data.calendar_provider

import android.accounts.Account
import android.provider.CalendarContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the pure helpers backing
 * [AndroidCalendarProviderRepository.ensureCalendarVisible].
 *
 * Issue #170: on Xiaomi/MIUI, Google calendars ship with both SYNC_EVENTS=0
 * AND VISIBLE=0 by default. A typo in either key would silently break MIUI
 * users; these tests guard against that.
 *
 * Uses Robolectric because `ContentValues` and `Account` are Android framework
 * types that are stubbed-out no-ops in plain JVM tests (every `put` silently
 * drops, every `Account` constructor NPEs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class EnsureCalendarVisibleTest {

    @Test
    fun `buildCalendarVisibleValues writes SYNC_EVENTS=1 AND VISIBLE=1`() {
        val values = buildCalendarVisibleValues()

        assertEquals(1, values.getAsInteger(CalendarContract.Calendars.SYNC_EVENTS))
        assertEquals(1, values.getAsInteger(CalendarContract.Calendars.VISIBLE))
    }

    @Test
    fun `buildCalendarVisibleValues writes exactly these two keys`() {
        val values = buildCalendarVisibleValues()

        assertEquals(2, values.size())
        assertTrue(values.containsKey(CalendarContract.Calendars.SYNC_EVENTS))
        assertTrue(values.containsKey(CalendarContract.Calendars.VISIBLE))
    }

    @Test
    fun `shouldSkipRequestSync returns true for LOCAL account type`() {
        assertTrue(shouldSkipRequestSync(Account("local", CalendarContract.ACCOUNT_TYPE_LOCAL)))
    }

    @Test
    fun `shouldSkipRequestSync is case-insensitive for LOCAL`() {
        assertTrue(shouldSkipRequestSync(Account("local", "local")))
        assertTrue(shouldSkipRequestSync(Account("local", "Local")))
    }

    @Test
    fun `shouldSkipRequestSync returns false for Google account`() {
        assertFalse(shouldSkipRequestSync(Account("u@example.com", "com.google")))
    }

    @Test
    fun `shouldSkipRequestSync returns false for Samsung account`() {
        assertFalse(shouldSkipRequestSync(Account("device", "com.osp.app.signin")))
    }

    @Test
    fun `shouldSkipRequestSync returns false for Xiaomi Mi account`() {
        assertFalse(shouldSkipRequestSync(Account("xxxx", "com.xiaomi")))
    }
}
