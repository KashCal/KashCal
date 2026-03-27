package org.onekash.kashcal

import android.content.Intent
import android.content.pm.PackageManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Verifies that MainActivity's intent filters match system-sent intents.
 *
 * Issue #129: Tapping the date in the notification shade sends
 * ACTION_MAIN + CATEGORY_APP_CALENDAR. KashCal must resolve for this
 * intent to appear in the "default calendar app" picker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityIntentFilterTest {

    private val pm: PackageManager = RuntimeEnvironment.getApplication().packageManager

    @Test
    fun `resolves ACTION_MAIN with CATEGORY_APP_CALENDAR`() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_CALENDAR)
        }
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val match = resolved.any {
            it.activityInfo.name == "org.onekash.kashcal.MainActivity"
        }
        assertTrue(
            "MainActivity must resolve ACTION_MAIN + CATEGORY_APP_CALENDAR (issue #129)",
            match
        )
    }

    @Test
    fun `resolves ACTION_MAIN with CATEGORY_LAUNCHER`() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = pm.queryIntentActivities(intent, 0)
        val match = resolved.any {
            it.activityInfo.name == "org.onekash.kashcal.MainActivity"
        }
        assertTrue(
            "MainActivity must resolve ACTION_MAIN + CATEGORY_LAUNCHER",
            match
        )
    }
}