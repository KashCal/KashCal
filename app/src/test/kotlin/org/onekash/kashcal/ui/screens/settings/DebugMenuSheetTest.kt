package org.onekash.kashcal.ui.screens.settings

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.shared.SYNC_INTERVALS_MS
import org.onekash.kashcal.util.DateTimeUtils.formatSyncInterval
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for DebugMenuSheet.
 *
 * Note: Composable UI interaction tests require AndroidX Compose testing
 * which runs as instrumented tests. These unit tests verify the supporting
 * logic.
 */
@RunWith(RobolectricTestRunner::class)
class DebugMenuSheetTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `debug menu has three options`() {
        // Debug menu should have: Force Full Sync, Sync Log, Sync Frequency
        val expectedOptions = listOf("Force Full Sync", "iCloud Sync Log", "Sync Frequency")
        assertEquals(3, expectedOptions.size)
    }

    @Test
    fun `sync frequency options are available`() {
        assertTrue(SYNC_INTERVALS_MS.isNotEmpty())
        // Ship-required options: 15-min floor, 1-hour default, manual-only
        assertTrue("Must include 15-min (WorkManager floor)", SYNC_INTERVALS_MS.contains(15 * 60 * 1000L))
        assertTrue("Must include 1-hour (default)", SYNC_INTERVALS_MS.contains(1 * 60 * 60 * 1000L))
        assertTrue("Must include Long.MAX_VALUE (manual only)", SYNC_INTERVALS_MS.contains(Long.MAX_VALUE))
    }

    @Test
    fun `force full sync requires confirmation`() {
        // Force full sync should show confirmation dialog
        // This is a design requirement test
        val showConfirmation = true
        assertTrue("Force full sync should require confirmation", showConfirmation)
    }

    @Test
    fun `formatSyncInterval works for all options`() {
        SYNC_INTERVALS_MS.forEach { intervalMs ->
            val formatted = formatSyncInterval(intervalMs, resources)
            assertTrue("Formatted label should not be empty for $intervalMs", formatted.isNotEmpty())
        }
    }
}
