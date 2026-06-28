package org.onekash.kashcal.ui.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision logic for the app-lock veil: when does the UI become locked?
 *
 * The grace window is measured between backgrounding (onStop) and the next
 * foregrounding (onStart) using a monotonic elapsed clock supplied by the
 * caller, so these tests are deterministic without any Android dependency.
 */
class AppLockStateMachineTest {

    private val grace = 30_000L

    private fun machine() = AppLockStateMachine(graceMs = grace)

    @Test
    fun `disabled is never locked on create`() {
        val m = machine()
        m.onActivityCreated(enabled = false)
        assertFalse(m.isLocked)
    }

    @Test
    fun `disabled stays unlocked even after a long background gap`() {
        val m = machine()
        m.onActivityCreated(enabled = false)
        m.onBackground(0L)
        m.onForeground(enabled = false, nowElapsed = grace * 10)
        assertFalse(m.isLocked)
    }

    @Test
    fun `enabled locks on cold start or process death`() {
        val m = machine()
        // The first (and only) onActivityCreated of a process represents both a
        // cold start and a return after process death.
        m.onActivityCreated(enabled = true)
        assertTrue(m.isLocked)
    }

    @Test
    fun `quick app switch within grace does not re-lock`() {
        val m = machine()
        m.onActivityCreated(enabled = true)
        m.onUnlockSucceeded()
        assertFalse(m.isLocked)

        m.onBackground(1_000L)
        m.onForeground(enabled = true, nowElapsed = 1_000L + (grace - 1))
        assertFalse(m.isLocked)
    }

    @Test
    fun `background beyond grace re-locks on return`() {
        val m = machine()
        m.onActivityCreated(enabled = true)
        m.onUnlockSucceeded()
        assertFalse(m.isLocked)

        m.onBackground(1_000L)
        m.onForeground(enabled = true, nowElapsed = 1_000L + grace)
        assertTrue(m.isLocked)
    }

    @Test
    fun `unlock success clears the lock`() {
        val m = machine()
        m.onActivityCreated(enabled = true)
        assertTrue(m.isLocked)
        m.onUnlockSucceeded()
        assertFalse(m.isLocked)
    }

    @Test
    fun `cancel or error keeps the app locked`() {
        val m = machine()
        m.onActivityCreated(enabled = true)
        assertTrue(m.isLocked)
        m.onUnlockCancelled()
        assertTrue(m.isLocked)
    }

    @Test
    fun `rotation preserves unlocked state (near-zero background gap)`() {
        val m = machine()
        m.onActivityCreated(enabled = true)
        m.onUnlockSucceeded()
        assertFalse(m.isLocked)

        // Config change: onStop then an immediate onStart (gap ~0). The VM
        // survives; onActivityCreated re-runs but is a no-op (already init).
        m.onBackground(5_000L)
        m.onActivityCreated(enabled = true)
        m.onForeground(enabled = true, nowElapsed = 5_000L)
        assertFalse(m.isLocked)
    }

    @Test
    fun `rotation preserves locked state`() {
        val m = machine()
        m.onActivityCreated(enabled = true)
        assertTrue(m.isLocked)

        m.onBackground(5_000L)
        m.onActivityCreated(enabled = true)
        m.onForeground(enabled = true, nowElapsed = 5_000L)
        assertTrue(m.isLocked)
    }

    @Test
    fun `re-enabling then a long real background locks again`() {
        val m = machine()
        m.onActivityCreated(enabled = true)
        m.onUnlockSucceeded()

        // Genuine background-to-home and return past grace.
        m.onBackground(10_000L)
        m.onForeground(enabled = true, nowElapsed = 10_000L + grace + 1)
        assertTrue(m.isLocked)
    }

    @Test
    fun `return from internal navigation does not re-lock even past grace`() {
        val m = machine()
        m.onActivityCreated(enabled = true)
        m.onUnlockSucceeded()
        assertFalse(m.isLocked)

        // Opening Settings (or the system enrollment screen) backgrounds the
        // Activity; the user may linger well past the grace window. Returning
        // from internal navigation must not challenge them.
        m.onBackground(1_000L)
        m.onForeground(enabled = true, nowElapsed = 1_000L + grace * 5, suppressRelock = true)
        assertFalse(m.isLocked)
    }

    @Test
    fun `cold start with no prior background never auto-locks via foreground alone`() {
        // After onActivityCreated(enabled=true) locks, onStart fires with no
        // recorded background — it must not flip the state either way.
        val m = machine()
        m.onActivityCreated(enabled = true)
        m.onForeground(enabled = true, nowElapsed = 0L)
        assertTrue(m.isLocked)
    }

    @Test
    fun `cold start stays locked when foreground sees a stale disabled flag`() {
        // Reproduces the cold-start race: onActivityCreated reads the real pref
        // synchronously (enabled=true) and locks, but onStart fires before the
        // async enabled flag has loaded, so onForeground observes a stale false.
        // With no prior background, that stale value must NOT clear the lock —
        // otherwise the veil drops before the first frame and the unlock prompt
        // never fires on a genuine cold start.
        val m = machine()
        m.onActivityCreated(enabled = true)
        assertTrue(m.isLocked)

        m.onForeground(enabled = false, nowElapsed = 0L)
        assertTrue(m.isLocked)
    }
}
