package org.onekash.kashcal.ui.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.preferences.UserPreferencesRepository
import org.onekash.kashcal.ui.permission.NotificationPermissionManager.PermissionState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Unit tests for NotificationPermissionManager.
 *
 * Tests verify:
 * - Pre-Android 13 returns NotRequired
 * - Permission granted returns Granted
 * - First request returns NotYetRequested
 * - After first denial returns ShouldShowRationale
 * - After 2+ denials returns PermanentlyDenied
 * - onPermissionGranted resets denial count
 * - onPermissionDenied increments denial count
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class NotificationPermissionManagerTest {

    @MockK
    private lateinit var context: Context

    @MockK
    private lateinit var userPreferences: UserPreferencesRepository

    @MockK
    private lateinit var activity: Activity

    private lateinit var manager: NotificationPermissionManager

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxed = true)
        manager = NotificationPermissionManager(context, userPreferences)

        // Mock static methods for permission checking
        mockkStatic(ContextCompat::class)
        mockkStatic(ActivityCompat::class)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== Pre-Android 13 Tests ====================

    @Test
    @Config(sdk = [31]) // Android 12
    fun `returns NotRequired for pre-Android 13`() = runTest {
        // On Android 12 (SDK 31), POST_NOTIFICATIONS permission is not needed
        val state = manager.checkPermissionState(activity)
        assertEquals(PermissionState.NotRequired, state)
    }

    @Test
    @Config(sdk = [32]) // Android 12L
    fun `returns NotRequired for Android 12L`() = runTest {
        val state = manager.checkPermissionState(activity)
        assertEquals(PermissionState.NotRequired, state)
    }

    // ==================== Permission Granted Tests ====================

    @Test
    @Config(sdk = [33]) // Android 13
    fun `returns Granted when permission already granted on Android 13`() = runTest {
        // Given
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } returns PackageManager.PERMISSION_GRANTED

        // When
        val state = manager.checkPermissionState(activity)

        // Then
        assertEquals(PermissionState.Granted, state)
    }

    @Test
    @Config(sdk = [34]) // Android 14
    fun `returns Granted when permission already granted on Android 14`() = runTest {
        // Given
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } returns PackageManager.PERMISSION_GRANTED

        // When
        val state = manager.checkPermissionState(activity)

        // Then
        assertEquals(PermissionState.Granted, state)
    }

    // ==================== First Request Tests ====================

    @Test
    @Config(sdk = [33])
    fun `returns NotYetRequested on first request`() = runTest {
        // Given: Permission not granted, no rationale needed, denial count = 0
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } returns PackageManager.PERMISSION_DENIED

        every {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        } returns false

        coEvery { userPreferences.getNotificationPermissionDeniedCount() } returns 0

        // When
        val state = manager.checkPermissionState(activity)

        // Then
        assertEquals(PermissionState.NotYetRequested, state)
    }

    // ==================== Show Rationale Tests ====================

    @Test
    @Config(sdk = [33])
    fun `returns ShouldShowRationale after first denial`() = runTest {
        // Given: Permission denied, rationale should be shown
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } returns PackageManager.PERMISSION_DENIED

        every {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        } returns true

        coEvery { userPreferences.getNotificationPermissionDeniedCount() } returns 1

        // When
        val state = manager.checkPermissionState(activity)

        // Then
        assertEquals(PermissionState.ShouldShowRationale, state)
    }

    @Test
    @Config(sdk = [34])
    fun `returns ShouldShowRationale when system indicates rationale needed`() = runTest {
        // Given: shouldShowRequestPermissionRationale returns true
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } returns PackageManager.PERMISSION_DENIED

        every {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        } returns true

        coEvery { userPreferences.getNotificationPermissionDeniedCount() } returns 1

        // When
        val state = manager.checkPermissionState(activity)

        // Then
        assertEquals(PermissionState.ShouldShowRationale, state)
    }

    // ==================== Permanently Denied Tests ====================

    @Test
    @Config(sdk = [33])
    fun `returns PermanentlyDenied after 2+ denials`() = runTest {
        // Given: Permission denied, no rationale (user checked "Don't ask again"), denial count >= 2
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } returns PackageManager.PERMISSION_DENIED

        every {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        } returns false

        coEvery { userPreferences.getNotificationPermissionDeniedCount() } returns 2

        // When
        val state = manager.checkPermissionState(activity)

        // Then
        assertEquals(PermissionState.PermanentlyDenied, state)
    }

    @Test
    @Config(sdk = [34])
    fun `returns PermanentlyDenied with 3 denials`() = runTest {
        // Given
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } returns PackageManager.PERMISSION_DENIED

        every {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        } returns false

        coEvery { userPreferences.getNotificationPermissionDeniedCount() } returns 3

        // When
        val state = manager.checkPermissionState(activity)

        // Then
        assertEquals(PermissionState.PermanentlyDenied, state)
    }

    // ==================== Denial Count Management Tests ====================

    @Test
    fun `onPermissionGranted resets denial count`() = runTest {
        // When
        manager.onPermissionGranted()

        // Then
        coVerify { userPreferences.resetNotificationPermissionDeniedCount() }
    }

    @Test
    fun `onPermissionDenied increments denial count`() = runTest {
        // When
        manager.onPermissionDenied()

        // Then
        coVerify { userPreferences.incrementNotificationPermissionDeniedCount() }
    }

    // ==================== Convenience Method Tests ====================

    @Test
    @Config(sdk = [31])
    fun `isPermissionRequired returns false for pre-Android 13`() {
        assertFalse(manager.isPermissionRequired())
    }

    @Test
    @Config(sdk = [33])
    fun `isPermissionRequired returns true for Android 13+`() {
        assertTrue(manager.isPermissionRequired())
    }

    @Test
    @Config(sdk = [31])
    fun `isPermissionGranted returns true for pre-Android 13`() {
        // Pre-Android 13, permission is always "granted" (not required)
        assertTrue(manager.isPermissionGranted())
    }

    @Test
    @Config(sdk = [33])
    fun `isPermissionGranted returns true when granted on Android 13`() {
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } returns PackageManager.PERMISSION_GRANTED

        assertTrue(manager.isPermissionGranted())
    }

    @Test
    @Config(sdk = [33])
    fun `isPermissionGranted returns false when denied on Android 13`() {
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } returns PackageManager.PERMISSION_DENIED

        assertFalse(manager.isPermissionGranted())
    }
}
