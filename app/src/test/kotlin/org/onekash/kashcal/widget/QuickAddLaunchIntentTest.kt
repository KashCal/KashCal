package org.onekash.kashcal.widget

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.MainActivity
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the Quick Settings tile produces exactly the intent the existing
 * MainActivity route consumes to open Quick Add. This is the unit-testable seam
 * for the otherwise framework-bound TileService.
 */
@RunWith(RobolectricTestRunner::class)
class QuickAddLaunchIntentTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `intent uses ACTION_VIEW targeting MainActivity`() {
        val intent = buildQuickAddCaptureIntent(context)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
    }

    @Test
    fun `intent carries the create_event widget action the route consumes`() {
        val intent = buildQuickAddCaptureIntent(context)
        assertEquals(ACTION_CREATE_EVENT, intent.getStringExtra(EXTRA_ACTION))
    }

    @Test
    fun `intent sets FLAG_ACTIVITY_NEW_TASK for the non-activity TileService launch`() {
        val intent = buildQuickAddCaptureIntent(context)
        assertTrue(
            "NEW_TASK is required when launching from a TileService (non-activity context)",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0
        )
    }

    @Test
    fun `intent does not pre-set a start timestamp so MainActivity opens Quick Add not the full form`() {
        val intent = buildQuickAddCaptureIntent(context)
        // Sentinel: a present start_ts would route to the full event form instead.
        assertFalse(intent.hasExtra(EXTRA_CREATE_EVENT_START_TS))
    }

    @Test
    fun `extra value matches the constant, not a hardcoded literal drift`() {
        // Guards against the route and the tile drifting apart.
        assertEquals("create_event", ACTION_CREATE_EVENT)
        assertEquals("widget_action", EXTRA_ACTION)
        assertNotEquals(ACTION_CREATE_EVENT, ACTION_GO_TO_TODAY)
    }
}
