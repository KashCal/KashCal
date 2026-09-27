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
 * Tests that [AndroidPermissionChecker] reports each system grant, reports POST_NOTIFICATIONS
 * granted below API 33, and re-reads on every call with no caching (READ_CONTACTS revoked
 * between two calls).
 *
 * The class runs on SDK 34; the pre-Tiramisu test runs on SDK 32. hasWriteContactsPermission
 * has no test here.
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
        // SDK 32 is below Tiramisu (33) and at or above minSdk 31.
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

    // The pre-S (API below 31) branch of hasExactAlarmPermission() never runs in production,
    // since minSdk is 31. Robolectric also can't run this compileSdk at SDK 30 without parser
    // errors, so the branch deliberately has no Robolectric test.

    @Test
    fun `each query is fresh — revoking between calls reflects immediately`() {
        val app = shadowOf(context as android.app.Application)
        app.grantPermissions(Manifest.permission.READ_CONTACTS)
        assertTrue(checker.hasReadContactsPermission())

        app.denyPermissions(Manifest.permission.READ_CONTACTS)
        assertFalse(checker.hasReadContactsPermission())
    }
}
