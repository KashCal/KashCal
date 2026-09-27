package org.onekash.kashcal.ui.lock

/**
 * Decides when KashCal's UI is veiled behind the device lock.
 *
 * Free of Android dependencies so `AppLockStateMachineTest` can drive it. The host feeds it
 * lifecycle edges and a monotonic elapsed time (`SystemClock.elapsedRealtime()`); the machine
 * owns [isLocked].
 *
 * Lock policy:
 * - When the feature is disabled, the UI is never locked.
 * - When enabled, the first [onActivityCreated] this machine sees locks before any content
 *   shows. Its owner, `AppLockViewModel`, is new after a cold start, a process death or a
 *   finished Activity, and survives rotation.
 * - A background-to-foreground gap of at least [graceMs] re-locks. Shorter gaps (quick app
 *   switches, and configuration changes such as rotation, which give a near-zero gap) don't.
 * - A successful unlock clears the lock; a cancelled or failed unlock leaves it.
 *
 * @param graceMs how long the app may sit in the background before a return re-locks it.
 */
class AppLockStateMachine(private val graceMs: Long = DEFAULT_GRACE_MS) {

    var isLocked: Boolean = false
        private set

    private var initialized = false
    private var lastBackgroundedElapsed: Long? = null

    /**
     * Sets the initial lock on the first call, from the Activity's `onCreate`.
     *
     * The first call locks when [enabled], so no content frame shows unlocked. Later calls, for
     * example the Activity recreated on rotation, are no-ops, so a configuration change never
     * re-locks an unlocked session.
     */
    fun onActivityCreated(enabled: Boolean) {
        if (initialized) return
        initialized = true
        isLocked = enabled
    }

    /** Records that the app went to the background at [nowElapsed]. */
    fun onBackground(nowElapsed: Long) {
        lastBackgroundedElapsed = nowElapsed
    }

    /**
     * Handles a return to the foreground at [nowElapsed]: re-locks when the feature is enabled
     * and the background gap is at least [graceMs], and unlocks when it is disabled.
     *
     * @param suppressRelock set when returning from an activity the app launched itself, for
     *   example Settings or the system biometric-enrollment screen, so a user who just turned
     *   the lock on, or lingered there past the grace window, isn't challenged at once.
     */
    fun onForeground(enabled: Boolean, nowElapsed: Long, suppressRelock: Boolean = false) {
        // A foreground with no recorded background is the cold-start onStart right after
        // onActivityCreated. It must not touch the lock: `enabled` comes from an async StateFlow
        // that may still hold its `false` seed, so honoring it would clear the lock
        // onActivityCreated just set and drop the veil before the prompt can fire.
        val backgroundedAt = lastBackgroundedElapsed ?: return
        if (!enabled) {
            isLocked = false
            return
        }
        if (suppressRelock) return
        if (nowElapsed - backgroundedAt >= graceMs) {
            isLocked = true
        }
    }

    /** Clears the lock after a successful authentication. */
    fun onUnlockSucceeded() {
        isLocked = false
    }

    /** Keeps the lock after the prompt was cancelled or failed. */
    fun onUnlockCancelled() {
        // No state change: the veil stays until a success.
    }

    companion object {
        /** Default grace window before a backgrounded app re-locks. */
        const val DEFAULT_GRACE_MS = 30_000L
    }
}
