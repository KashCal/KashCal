package org.onekash.kashcal.ui.lock

import androidx.biometric.BiometricManager

/** What to do when the user tries to enable the app lock. */
enum class AppLockEnrollmentAction {
    /** A strong biometric or device credential is available: turn the lock on. */
    Enable,

    /**
     * Nothing is enrolled: send the user to the system enrollment flow instead of enabling a
     * lock nothing can satisfy.
     */
    RouteToEnroll,

    /** Any other result, such as no or unavailable hardware: don't enable; tell the user. */
    Unsupported,
}

/** Maps a `canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` result to the enable action. */
fun decideEnrollmentAction(canAuthenticateResult: Int): AppLockEnrollmentAction =
    when (canAuthenticateResult) {
        BiometricManager.BIOMETRIC_SUCCESS -> AppLockEnrollmentAction.Enable
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AppLockEnrollmentAction.RouteToEnroll
        else -> AppLockEnrollmentAction.Unsupported
    }

/** What to do when the user tries to disable the app lock. */
enum class AppLockDisableAction {
    /** Challenge before turning the lock off. */
    Challenge,

    /**
     * No credential is enrolled, so a challenge can't be satisfied. The device is already
     * unsecured, so disable directly; a challenge would leave a lock the user can never turn off.
     */
    DisableDirectly,
}

/**
 * Maps a `canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` result to the disable action.
 *
 * Only the nothing-enrolled case skips the challenge. Every other result, a transient
 * hardware-unavailable included, challenges: the lock is on and must not be dropped without
 * authentication.
 */
fun decideDisableAction(canAuthenticateResult: Int): AppLockDisableAction =
    when (canAuthenticateResult) {
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AppLockDisableAction.DisableDirectly
        else -> AppLockDisableAction.Challenge
    }
