package org.onekash.kashcal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents the resume-sync decision in MainActivity.onResume() as a truth table.
 *
 * The logic lives inline in onResume(); these tests run a copy of it, so they don't fail when
 * the activity changes. onResume() calls `syncOnResumeIfNeeded` except:
 * - on the first resume (cold start), where triggerStartupSync() runs instead
 * - on return from an activity opened through `launchInternalActivity` (SettingsActivity, the
 *   system enrollment and permission screens), where nothing outside the app changed
 */
class MainActivitySyncDecisionTest {

    /** Mirrors the two guards in MainActivity.onResume() around `syncOnResumeIfNeeded`. */
    private fun shouldSyncOnResume(
        isFirstResume: Boolean,
        returningFromInternalActivity: Boolean
    ): Boolean {
        if (isFirstResume) return false
        if (returningFromInternalActivity) return false
        return true
    }

    @Test
    fun `first resume does not sync`() {
        // Cold start: triggerStartupSync() handles this case
        assertFalse(shouldSyncOnResume(isFirstResume = true, returningFromInternalActivity = false))
    }

    @Test
    fun `returning from settings does not sync`() {
        // Internal navigation: nothing outside the app changed
        assertFalse(shouldSyncOnResume(isFirstResume = false, returningFromInternalActivity = true))
    }

    @Test
    fun `returning from external app does sync`() {
        // The user was outside the app; a shared calendar may have changed
        assertTrue(shouldSyncOnResume(isFirstResume = false, returningFromInternalActivity = false))
    }

    @Test
    fun `first resume from settings does not sync`() {
        // Edge case: both flags true (process death during Settings)
        assertFalse(shouldSyncOnResume(isFirstResume = true, returningFromInternalActivity = true))
    }

    @Test
    fun `returning from home screen does sync`() {
        // The user pressed Home, then returned; a shared calendar may have changed
        assertTrue(shouldSyncOnResume(isFirstResume = false, returningFromInternalActivity = false))
    }

    @Test
    fun `returning from another app does sync`() {
        // The user switched to another app, then returned; data may have changed
        assertTrue(shouldSyncOnResume(isFirstResume = false, returningFromInternalActivity = false))
    }
}
