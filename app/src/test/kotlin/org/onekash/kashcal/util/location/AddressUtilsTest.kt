package org.onekash.kashcal.util.location

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import io.mockk.spyk
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AddressUtilsTest {

    // ==================== openInMaps Tests ====================

    @Test
    fun `openInMaps does nothing for blank address`() {
        val context = spyk(ApplicationProvider.getApplicationContext<android.content.Context>())

        openInMaps(context, "")
        openInMaps(context, "   ")

        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `openInMaps falls back to OpenStreetMap when no handlers`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // No handler registered: no maps app installed.

        openInMaps(context, "123 Main Street")

        val startedIntent = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertTrue(
            "Should open OpenStreetMap",
            startedIntent?.data?.toString()?.contains("openstreetmap.org") == true
        )
    }

    @Test
    fun `openInMaps encodes special characters in address`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // No handler registered, so the OpenStreetMap fallback URL carries the address.

        openInMaps(context, "123 Main St & Pike, Seattle WA")

        val startedIntent = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        val url = startedIntent?.data?.toString() ?: ""
        assertTrue("Should encode ampersand", url.contains("%26"))
        assertTrue("Should encode comma", url.contains("%2C"))
    }

    @Test
    fun `openInMaps adds FLAG_ACTIVITY_NEW_TASK`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // No handler registered: the fallback path.

        openInMaps(context, "123 Main Street")

        val startedIntent = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertTrue(
            "Should have FLAG_ACTIVITY_NEW_TASK",
            (startedIntent?.flags?.and(Intent.FLAG_ACTIVITY_NEW_TASK)) != 0
        )
    }

    @Test
    fun `openInMaps uses correct OpenStreetMap URL format`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // No handler registered: the fallback URL.

        openInMaps(context, "123 Main Street")

        val startedIntent = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        val url = startedIntent?.data?.toString() ?: ""
        assertTrue(
            "Should use OpenStreetMap search URL format",
            url.startsWith("https://www.openstreetmap.org/search?query=")
        )
        assertTrue(
            "Should use ACTION_VIEW",
            startedIntent?.action == Intent.ACTION_VIEW
        )
    }

    @Test
    fun `openInMaps shows chooser when geo handlers exist`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadowPm = Shadows.shadowOf(context.packageManager)

        // Register a fake maps app that handles geo: URIs
        val componentName = ComponentName("com.example.maps", "com.example.maps.MapsActivity")
        val intentFilter = IntentFilter(Intent.ACTION_VIEW).apply {
            addDataScheme("geo")
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val activityInfo = ActivityInfo().apply {
            packageName = "com.example.maps"
            name = "com.example.maps.MapsActivity"
        }
        shadowPm.addActivityIfNotPresent(componentName)
        shadowPm.addIntentFilterForActivity(componentName, intentFilter)

        openInMaps(context, "123 Main Street")

        val startedIntent = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertTrue(
            "Should use Intent.createChooser (ACTION_CHOOSER)",
            startedIntent?.action == Intent.ACTION_CHOOSER
        )

        val targetIntent = startedIntent?.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertTrue(
            "Wrapped intent should be geo: URI",
            targetIntent?.data?.scheme == "geo"
        )
        assertTrue(
            "Wrapped intent should use ACTION_VIEW",
            targetIntent?.action == Intent.ACTION_VIEW
        )
    }

    @Test
    fun `openInMaps geo URI contains encoded address`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadowPm = Shadows.shadowOf(context.packageManager)

        // A geo: handler, so openInMaps takes the chooser path.
        val componentName = ComponentName("com.example.maps", "com.example.maps.MapsActivity")
        val intentFilter = IntentFilter(Intent.ACTION_VIEW).apply {
            addDataScheme("geo")
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        shadowPm.addActivityIfNotPresent(componentName)
        shadowPm.addIntentFilterForActivity(componentName, intentFilter)

        openInMaps(context, "123 Main St & Pike")

        val startedIntent = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        val targetIntent = startedIntent?.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        val geoUri = targetIntent?.data?.toString() ?: ""

        assertTrue("Should start with geo:0,0?q=", geoUri.startsWith("geo:0,0?q="))
        assertTrue("Should encode ampersand in geo URI", geoUri.contains("%26"))
    }
}
