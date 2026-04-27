package org.onekash.kashcal.ui.permission

import android.Manifest
import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Verifies [AndroidPermissionChecker] reads permission state from the
 * system at the moment of each call — no caching, correct SDK gates.
 *
 * Tests run with two SDK targets (Android 13 / TIRAMISU for POST_NOTIFICATIONS,
 * and default / current for exact-alarm behavior) to exercise the version gates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AndroidPermissionCheckerTest {

    private lateinit var context: Context
    private lateinit var checker: AndroidPermissionChecker

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        checker = AndroidPermissionChecker(context)
    }

    @After
    fun tearDown() {
        val app = shadowOf(context as android.app.Application)
        app.denyPermissions(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        )
    }

    @Test
    fun `hasNotificationPermission returns true when POST_NOTIFICATIONS granted`() {
        shadowOf(context as android.app.Application)
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(checker.hasNotificationPermission())
    }

    @Test
    fun `hasNotificationPermission returns false when POST_NOTIFICATIONS denied`() {
        shadowOf(context as android.app.Application)
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(checker.hasNotificationPermission())
    }

    @Test
    @Config(sdk = [32])
    fun `hasNotificationPermission returns true on pre-Tiramisu regardless of grant`() {
        // minSdk=31, so 32 is the lowest pre-Tiramisu slot available.
        shadowOf(context as android.app.Application)
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(checker.hasNotificationPermission())
    }

    @Test
    fun `hasReadContactsPermission returns true when granted`() {
        shadowOf(context as android.app.Application)
            .grantPermissions(Manifest.permission.READ_CONTACTS)

        assertTrue(checker.hasReadContactsPermission())
    }

    @Test
    fun `hasReadContactsPermission returns false when denied`() {
        shadowOf(context as android.app.Application)
            .denyPermissions(Manifest.permission.READ_CONTACTS)

        assertFalse(checker.hasReadContactsPermission())
    }

    @Test
    fun `hasCalendarReadPermission returns true when granted`() {
        shadowOf(context as android.app.Application)
            .grantPermissions(Manifest.permission.READ_CALENDAR)

        assertTrue(checker.hasCalendarReadPermission())
    }

    @Test
    fun `hasCalendarReadPermission returns false when denied`() {
        shadowOf(context as android.app.Application)
            .denyPermissions(Manifest.permission.READ_CALENDAR)

        assertFalse(checker.hasCalendarReadPermission())
    }

    @Test
    fun `hasCalendarWritePermission returns true when granted`() {
        shadowOf(context as android.app.Application)
            .grantPermissions(Manifest.permission.WRITE_CALENDAR)

        assertTrue(checker.hasCalendarWritePermission())
    }

    @Test
    fun `hasCalendarWritePermission returns false when denied`() {
        shadowOf(context as android.app.Application)
            .denyPermissions(Manifest.permission.WRITE_CALENDAR)

        assertFalse(checker.hasCalendarWritePermission())
    }

    @Test
    fun `hasExactAlarmPermission returns true when AlarmManager allows`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        assertTrue(checker.hasExactAlarmPermission())
    }

    @Test
    fun `hasExactAlarmPermission returns false when AlarmManager denies`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertFalse(checker.hasExactAlarmPermission())
    }

    // Note: pre-S SDK (API < 31) branch of hasExactAlarmPermission() is defensive
    // — the app's minSdk = 31, so pre-S code paths never execute in production.
    // Robolectric also can't downsample compileSdk=36 to SDK 30 without parser
    // errors, so this branch is deliberately not covered by a Robolectric test.

    @Test
    fun `each query is fresh — revoking between calls reflects immediately`() {
        val app = shadowOf(context as android.app.Application)
        app.grantPermissions(Manifest.permission.READ_CONTACTS)
        assertTrue(checker.hasReadContactsPermission())

        app.denyPermissions(Manifest.permission.READ_CONTACTS)
        assertFalse(checker.hasReadContactsPermission())
    }
}
