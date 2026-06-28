package org.onekash.kashcal.ui.lock

/**
 * Decides when KashCal's UI should be veiled behind the device lock.
 *
 * The machine is intentionally free of Android dependencies so its behavior can
 * be unit-tested deterministically. The hosting Activity/ViewModel feeds it
 * lifecycle edges and a monotonic elapsed-time value (e.g.
 * `SystemClock.elapsedRealtime()`); the machine owns the [isLocked] decision.
 *
 * Lock policy:
 * - When the feature is disabled, the UI is never locked.
 * - When enabled, the first foregrounding of the process (a cold start, which
 *   also covers a return after process death) locks before any content shows.
 * - After a genuine background-to-foreground gap longer than [graceMs], the UI
 *   re-locks; shorter gaps (quick app-switches, and configuration changes such
 *   as rotation, which produce a near-zero gap) do not.
 * - A successful unlock clears the lock; a cancelled/failed unlock leaves it.
 *
 * @param graceMs how long the app may sit in the background before a return
 *   re-locks it.
 */
class AppLockStateMachine(private val graceMs: Long = DEFAULT_GRACE_MS) {

    var isLocked: Boolean = false
        private set

    private var initialized = false
    private var lastBackgroundedElapsed: Long? = null

    /**
     * Called once per Activity creation. The first call of a process initializes
     * the lock to the enabled state (locking immediately on a cold start so no
     * content frame is shown unlocked). Subsequent calls — e.g. the Activity
     * being recreated on rotation — are no-ops so a configuration change never
     * re-locks an already-unlocked session.
     */
    fun onActivityCreated(enabled: Boolean) {
        if (initialized) return
        initialized = true
        isLocked = enabled
    }

    /** Record that the app went to the background at [nowElapsed]. */
    fun onBackground(nowElapsed: Long) {
        lastBackgroundedElapsed = nowElapsed
    }

    /**
     * The app returned to the foreground at [nowElapsed]. Re-locks if the
     * feature is enabled and the background gap exceeded [graceMs].
     *
     * @param suppressRelock set when returning from internal navigation
     *   (Settings, the system biometric-enrollment screen) so a user who just
     *   toggled the lock on — or who lingered on those screens past the grace
     *   window — is not immediately challenged.
     */
    fun onForeground(enabled: Boolean, nowElapsed: Long, suppressRelock: Boolean = false) {
        // A foreground with no recorded background is the cold-start onStart that
        // fires right after onActivityCreated. It must not touch the lock state:
        // the `enabled` flag here comes from an async StateFlow that may not have
        // loaded yet (still its `false` seed), so honoring it would clear the lock
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

    /** The user authenticated successfully; reveal the UI. */
    fun onUnlockSucceeded() {
        isLocked = false
    }

    /** The prompt was cancelled or errored; stay locked. */
    fun onUnlockCancelled() {
        // Intentionally no state change — the veil remains until a success.
    }

    companion object {
        /** Default grace window before a backgrounded app re-locks. */
        const val DEFAULT_GRACE_MS = 30_000L
    }
}
