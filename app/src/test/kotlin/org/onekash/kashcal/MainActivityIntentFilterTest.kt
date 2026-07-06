package org.onekash.kashcal

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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

    private companion object {
        // The launcher/calendar filters moved from MainActivity onto the default activity-alias
        // (which targets MainActivity) so the app icon can be swapped. Either resolving is correct.
        val LAUNCHER_COMPONENT_NAMES = setOf(
            "org.onekash.kashcal.MainActivity",
            "org.onekash.kashcal.MainActivityDefault",
        )
    }

    @Test
    fun `resolves ACTION_MAIN with CATEGORY_APP_CALENDAR`() {
        // The launcher/calendar entry lives on the default activity-alias so the app icon can be
        // swapped; the alias targets MainActivity, so either name resolving satisfies issue #129.
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_CALENDAR)
        }
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val match = resolved.any {
            it.activityInfo.name in LAUNCHER_COMPONENT_NAMES
        }
        assertTrue(
            "A launcher entry must resolve ACTION_MAIN + CATEGORY_APP_CALENDAR (issue #129)",
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
            it.activityInfo.name in LAUNCHER_COMPONENT_NAMES
        }
        assertTrue(
            "A launcher entry must resolve ACTION_MAIN + CATEGORY_LAUNCHER",
            match
        )
    }

    @Test
    fun `resolves ACTION_VIEW for a CalendarProvider event item`() {
        // Transit apps and notification taps open a specific device event via
        // ACTION_VIEW on an event item URI. MainActivity must resolve so the event
        // opens in KashCal's quick view instead of bouncing to another calendar app.
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                Uri.parse("content://com.android.calendar/events/123"),
                "vnd.android.cursor.item/event"
            )
        }
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val match = resolved.any {
            it.activityInfo.name == "org.onekash.kashcal.MainActivity"
        }
        assertTrue(
            "MainActivity must resolve ACTION_VIEW on a CalendarProvider event item " +
                "(transit apps, notification taps)",
            match
        )
    }

    @Test
    fun `resolves ACTION_VIEW for a CalendarContract content URI`() {
        // Launchers and clock widgets open events/dates via ACTION_VIEW on a
        // content://com.android.calendar URI (no explicit MIME type). MainActivity
        // must resolve so these reach the CalendarContract intent handler.
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.calendar/events/123")
        }
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val match = resolved.any {
            it.activityInfo.name == "org.onekash.kashcal.MainActivity"
        }
        assertTrue(
            "MainActivity must resolve ACTION_VIEW on a content://com.android.calendar URI",
            match
        )
    }

    @Test
    fun `resolves ACTION_SEND with text plain`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
        }
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val match = resolved.any {
            it.activityInfo.name == "org.onekash.kashcal.MainActivity"
        }
        assertTrue(
            "MainActivity must resolve ACTION_SEND + text/plain so it appears in the system share sheet",
            match
        )
    }
}