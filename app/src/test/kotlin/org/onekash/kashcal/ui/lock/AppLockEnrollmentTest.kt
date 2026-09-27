package org.onekash.kashcal.ui.lock

import androidx.biometric.BiometricManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests [decideEnrollmentAction] and [decideDisableAction], which map a
 * `BiometricManager.canAuthenticate(...)` result to what the app does when the user turns the
 * app lock on or off.
 */
class AppLockEnrollmentTest {

    @Test
    fun `success enables the lock`() {
        assertEquals(
            AppLockEnrollmentAction.Enable,
            decideEnrollmentAction(BiometricManager.BIOMETRIC_SUCCESS),
        )
    }

    @Test
    fun `none enrolled routes to system enrollment`() {
        assertEquals(
            AppLockEnrollmentAction.RouteToEnroll,
            decideEnrollmentAction(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED),
        )
    }

    @Test
    fun `no hardware is unsupported`() {
        assertEquals(
            AppLockEnrollmentAction.Unsupported,
            decideEnrollmentAction(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE),
        )
    }

    @Test
    fun `hardware unavailable is unsupported`() {
        assertEquals(
            AppLockEnrollmentAction.Unsupported,
            decideEnrollmentAction(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE),
        )
    }

    @Test
    fun `security update required is unsupported`() {
        assertEquals(
            AppLockEnrollmentAction.Unsupported,
            decideEnrollmentAction(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED),
        )
    }

    @Test
    fun `unsupported status is unsupported`() {
        assertEquals(
            AppLockEnrollmentAction.Unsupported,
            decideEnrollmentAction(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED),
        )
    }

    @Test
    fun `unknown status is unsupported`() {
        assertEquals(
            AppLockEnrollmentAction.Unsupported,
            decideEnrollmentAction(BiometricManager.BIOMETRIC_STATUS_UNKNOWN),
        )
    }

    // Disabling the lock removes protection, so it is challenged. The only exception is a
    // device with nothing enrolled, where a challenge can't be satisfied and would leave a
    // lock the user can never turn off.

    @Test
    fun `disable with a credential present challenges`() {
        assertEquals(
            AppLockDisableAction.Challenge,
            decideDisableAction(BiometricManager.BIOMETRIC_SUCCESS),
        )
    }

    @Test
    fun `disable with nothing enrolled disables directly`() {
        assertEquals(
            AppLockDisableAction.DisableDirectly,
            decideDisableAction(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED),
        )
    }

    @Test
    fun `disable still challenges when hardware is temporarily unavailable`() {
        // A transient HW_UNAVAILABLE must not silently drop the lock: the credential still
        // exists, so it challenges.
        assertEquals(
            AppLockDisableAction.Challenge,
            decideDisableAction(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE),
        )
    }

    @Test
    fun `disable challenges on unknown status`() {
        assertEquals(
            AppLockDisableAction.Challenge,
            decideDisableAction(BiometricManager.BIOMETRIC_STATUS_UNKNOWN),
        )
    }
}
