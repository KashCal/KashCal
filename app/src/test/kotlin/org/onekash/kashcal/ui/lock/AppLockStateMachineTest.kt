package org.onekash.kashcal.ui.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [AppLockStateMachine], which decides when the app-lock veil covers the UI.
 *
 * The grace window runs from backgrounding (onStop) to the next foregrounding (onStart) on a
 * monotonic elapsed clock the caller supplies, so these tests are deterministic with no Android
 * dependency.
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
        // The first onActivityCreated this machine sees stands for a cold start and a return
        // after process death alike.
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

        // Configuration change: onStop then an immediate onStart (gap about 0). The ViewModel
        // survives, and the re-run onActivityCreated is a no-op.
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

        // A background to the home screen and a return past grace.
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

        // Opening Settings or the system enrollment screen backgrounds the Activity, and the
        // user may linger past the grace window. Returning from internal navigation must not
        // challenge them.
        m.onBackground(1_000L)
        m.onForeground(enabled = true, nowElapsed = 1_000L + grace * 5, suppressRelock = true)
        assertFalse(m.isLocked)
    }

    @Test
    fun `cold start with no prior background never auto-locks via foreground alone`() {
        // After onActivityCreated(enabled=true) locks, onStart fires with no recorded
        // background; it must not change the lock.
        val m = machine()
        m.onActivityCreated(enabled = true)
        m.onForeground(enabled = true, nowElapsed = 0L)
        assertTrue(m.isLocked)
    }

    @Test
    fun `cold start stays locked when foreground sees a stale disabled flag`() {
        // The cold-start race: onActivityCreated reads the pref synchronously (enabled=true)
        // and locks, but onStart fires before the async enabled flag has loaded, so
        // onForeground sees a stale false. With no prior background that value must not clear
        // the lock, or the veil drops before the first frame and the unlock prompt never
        // fires on a cold start.
        val m = machine()
        m.onActivityCreated(enabled = true)
        assertTrue(m.isLocked)

        m.onForeground(enabled = false, nowElapsed = 0L)
        assertTrue(m.isLocked)
    }
}
