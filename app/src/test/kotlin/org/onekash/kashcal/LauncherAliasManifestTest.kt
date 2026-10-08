package org.onekash.kashcal

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.appicon.DayIcons
import org.onekash.kashcal.ui.appicon.LauncherAliases
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Checks the launcher activity-aliases in the manifest: every alias the icon switch toggles
 * exists, only the default one is enabled at install, each keeps the app label and the
 * long-press shortcuts, and each day alias shows its own day icon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LauncherAliasManifestTest {

    private val context = RuntimeEnvironment.getApplication()
    private val pm: PackageManager = context.packageManager
    private val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.GET_META_DATA

    private fun info(suffix: String) =
        pm.getActivityInfo(ComponentName(context, context.packageName + suffix), flags)

    @Test
    fun `every alias exists and only the default alias is enabled at install`() {
        LauncherAliases.ALL.forEach { suffix ->
            assertEquals(
                "$suffix enabled at install",
                suffix == ".MainActivityDefault",
                info(suffix).enabled,
            )
        }
    }

    @Test
    fun `every alias carries the app shortcuts and keeps the app label`() {
        val appName = context.getString(R.string.app_name)
        LauncherAliases.ALL
            .filter { it != ".MainActivitySupporterCalendar" } // labeled "Calendar" by design
            .forEach { suffix ->
                val info = info(suffix)
                assertEquals(
                    "$suffix shortcuts",
                    R.xml.shortcuts,
                    info.metaData?.getInt("android.app.shortcuts"),
                )
                assertEquals("$suffix label", appName, info.loadLabel(pm).toString())
            }
        assertEquals(
            R.xml.shortcuts,
            info(".MainActivitySupporterCalendar").metaData?.getInt("android.app.shortcuts"),
        )
    }

    @Test
    fun `each day alias shows that day's icon`() {
        (1..31).forEach { day ->
            assertEquals("day $day icon", DayIcons.mipmapFor(day), info(".MainActivityDay$day").icon)
        }
    }

    @Test
    fun `every alias is a launcher entry once enabled`() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val names = pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
            .filter { it.activityInfo.packageName == context.packageName }
            .map { it.activityInfo.name }
            .toSet()
        LauncherAliases.ALL.forEach { suffix ->
            assertTrue("$suffix resolves MAIN + LAUNCHER", context.packageName + suffix in names)
        }
    }
}
