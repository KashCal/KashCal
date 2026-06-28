package org.onekash.kashcal.ui.lock

import androidx.biometric.BiometricManager

/** What to do when the user tries to enable the app lock. */
enum class AppLockEnrollmentAction {
    /** A biometric or device credential is available — turn the lock on. */
    Enable,

    /**
     * The device can authenticate but nothing is enrolled — send the user to the
     * system enrollment flow rather than enabling a lock nothing can satisfy.
     */
    RouteToEnroll,

    /** No usable authentication on this device — don't enable; tell the user. */
    Unsupported,
}

/**
 * Translate a `BiometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`
 * result into the action to take.
 */
fun decideEnrollmentAction(canAuthenticateResult: Int): AppLockEnrollmentAction =
    when (canAuthenticateResult) {
        BiometricManager.BIOMETRIC_SUCCESS -> AppLockEnrollmentAction.Enable
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AppLockEnrollmentAction.RouteToEnroll
        else -> AppLockEnrollmentAction.Unsupported
    }

/** What to do when the user tries to disable the app lock. */
enum class AppLockDisableAction {
    /** A credential exists — challenge before turning the lock off. */
    Challenge,

    /**
     * No credential is enrolled, so a challenge is unsatisfiable. The device is
     * already unsecured and there is nothing left to gate on, so disable directly
     * rather than trapping the user with a lock they can never turn off.
     */
    DisableDirectly,
}

/**
 * Translate a `BiometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`
 * result into the action to take when DISABLING the lock. Only the
 * nothing-enrolled case skips the challenge; every other result (including
 * transient hardware-unavailable) keeps the protection and challenges, since
 * the lock is already on and must not be droppable without authentication.
 */
fun decideDisableAction(canAuthenticateResult: Int): AppLockDisableAction =
    when (canAuthenticateResult) {
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AppLockDisableAction.DisableDirectly
        else -> AppLockDisableAction.Challenge
    }
