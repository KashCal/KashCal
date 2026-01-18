package org.onekash.kashcal.util.location

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import io.mockk.spyk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPackageManager

/**
 * Unit tests for AddressUtils.
 *
 * Tests the looksLikeAddress() function which detects if text
 * appears to be a street address using word boundary detection.
 *
 * Tests the openInMaps() function which opens addresses in maps
 * with chooser dialog and OpenStreetMap fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AddressUtilsTest {

    // ==================== Positive Cases - Should Detect as Address ====================

    @Test
    fun `street address with street word detected`() {
        assertTrue(looksLikeAddress("123 Main Street"))
        assertTrue(looksLikeAddress("456 Oak Ave"))
        assertTrue(looksLikeAddress("789 Elm Blvd"))
        assertTrue(looksLikeAddress("101 Pine Road"))
        assertTrue(looksLikeAddress("202 Cedar Dr"))
        assertTrue(looksLikeAddress("303 Maple Lane"))
        assertTrue(looksLikeAddress("404 Birch Way"))
        assertTrue(looksLikeAddress("505 Walnut Ct"))
        assertTrue(looksLikeAddress("606 Cherry Pl"))
        assertTrue(looksLikeAddress("707 Oak Pkwy"))
        assertTrue(looksLikeAddress("808 Pine Cir"))
        assertTrue(looksLikeAddress("909 Elm Ter"))
        assertTrue(looksLikeAddress("111 Main Hwy"))
    }

    @Test
    fun `address with city and state detected`() {
        assertTrue(looksLikeAddress("456 Oak Ave, San Francisco"))
        assertTrue(looksLikeAddress("789 Elm Blvd, CA 94102"))
        assertTrue(looksLikeAddress("123 Main St, New York, NY"))
        assertTrue(looksLikeAddress("100 Broadway, Suite 200, Seattle, WA"))
    }

    @Test
    fun `address with comma-separated parts detected`() {
        // Even without street word, comma-separated parts indicate address
        assertTrue(looksLikeAddress("Building 5, San Francisco, CA"))
        assertTrue(looksLikeAddress("Unit 12, Oakland"))
    }

    @Test
    fun `full address with zip code detected`() {
        assertTrue(looksLikeAddress("123 Main Street, San Francisco, CA 94102"))
        assertTrue(looksLikeAddress("456 Oak Avenue, New York, NY 10001"))
    }

    // ==================== Negative Cases - Should NOT Detect as Address ====================

    @Test
    fun `non-address location names not detected`() {
        assertFalse(looksLikeAddress("Zoom meeting"))
        assertFalse(looksLikeAddress("Conference Room B"))
        assertFalse(looksLikeAddress("Office"))
        assertFalse(looksLikeAddress("Home"))
        assertFalse(looksLikeAddress("Coffee Shop"))
    }

    @Test
    fun `titles with Dr not detected as drive`() {
        // "Dr" as title should not trigger "drive" detection
        assertFalse(looksLikeAddress("Dr. Smith's office"))
        assertFalse(looksLikeAddress("Dr Johnson"))
    }

    @Test
    fun `words containing street abbreviations not detected`() {
        // "St" in "Standup" should not trigger "street" detection
        assertFalse(looksLikeAddress("Standup meeting"))
        assertFalse(looksLikeAddress("Standard conference room"))
        assertFalse(looksLikeAddress("Statistics class"))
    }

    @Test
    fun `way in company name not detected`() {
        assertFalse(looksLikeAddress("My Way Productions"))
        assertFalse(looksLikeAddress("Subway Restaurant"))
        assertFalse(looksLikeAddress("Gateway Center"))
    }

    @Test
    fun `URLs and meeting links not detected`() {
        // URLs typically don't have both digits and street words
        assertFalse(looksLikeAddress("https://zoom.us/j/123456"))
        assertFalse(looksLikeAddress("meet.google.com/abc-defg-hij"))
    }

    @Test
    fun `text without numbers not detected`() {
        // Must have at least one digit
        assertFalse(looksLikeAddress("Main Street"))
        assertFalse(looksLikeAddress("Oak Avenue"))
        assertFalse(looksLikeAddress("San Francisco, CA"))
    }

    @Test
    fun `text without letters not detected`() {
        // Must have at least one letter
        assertFalse(looksLikeAddress("123456"))
        assertFalse(looksLikeAddress("94102"))
    }

    @Test
    fun `blank text not detected`() {
        assertFalse(looksLikeAddress(""))
        assertFalse(looksLikeAddress("   "))
    }

    // ==================== Edge Cases ====================

    @Test
    fun `mixed case street words detected`() {
        assertTrue(looksLikeAddress("123 Main STREET"))
        assertTrue(looksLikeAddress("456 Oak AVE"))
        assertTrue(looksLikeAddress("789 Elm Street"))
    }

    @Test
    fun `street abbreviations work correctly`() {
        assertTrue(looksLikeAddress("123 Main St"))    // st = street
        assertTrue(looksLikeAddress("456 Oak Ave"))    // ave = avenue
        assertTrue(looksLikeAddress("789 Elm Blvd"))   // blvd = boulevard
        assertTrue(looksLikeAddress("101 Pine Rd"))    // rd = road
        assertTrue(looksLikeAddress("202 Cedar Dr"))   // dr = drive
        assertTrue(looksLikeAddress("303 Maple Ln"))   // ln = lane
        assertTrue(looksLikeAddress("404 Birch Ct"))   // ct = court
        assertTrue(looksLikeAddress("505 Walnut Pl"))  // pl = place
        assertTrue(looksLikeAddress("606 Cherry Cir")) // cir = circle
    }

    @Test
    fun `apartment and suite numbers detected`() {
        assertTrue(looksLikeAddress("123 Main St, Apt 4B"))
        assertTrue(looksLikeAddress("456 Oak Ave Suite 100"))
        assertTrue(looksLikeAddress("789 Elm Blvd #5"))
    }

    @Test
    fun `international address formats with commas detected`() {
        // Comma-separated parts indicate address even without US street words
        assertTrue(looksLikeAddress("10 Downing Street, London"))
        assertTrue(looksLikeAddress("1 Infinite Loop, Cupertino"))
    }

    // ==================== openInMaps Tests ====================

    @Test
    fun `openInMaps does nothing for blank address`() {
        val context = spyk(ApplicationProvider.getApplicationContext<android.content.Context>())

        openInMaps(context, "")
        openInMaps(context, "   ")

        // Verify no startActivity calls were made
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `openInMaps falls back to OpenStreetMap when no handlers`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Don't add any handlers - this simulates no maps app installed

        openInMaps(context, "123 Main Street")

        // Verify OpenStreetMap URL was opened
        val startedIntent = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertTrue(
            "Should open OpenStreetMap",
            startedIntent?.data?.toString()?.contains("openstreetmap.org") == true
        )
    }

    @Test
    fun `openInMaps encodes special characters in address`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Don't add handlers - so we get the fallback URL which is easier to verify

        openInMaps(context, "123 Main St & Pike, Seattle WA")

        // Verify the URL was properly encoded
        val startedIntent = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        val url = startedIntent?.data?.toString() ?: ""
        assertTrue("Should encode ampersand", url.contains("%26"))
        assertTrue("Should encode comma", url.contains("%2C"))
    }

    @Test
    fun `openInMaps adds FLAG_ACTIVITY_NEW_TASK`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Don't add handlers - so we get the fallback path

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
        // Don't add handlers - so we get the fallback URL

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

        // Verify chooser intent was started
        val startedIntent = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertTrue(
            "Should use Intent.createChooser (ACTION_CHOOSER)",
            startedIntent?.action == Intent.ACTION_CHOOSER
        )

        // Verify the wrapped intent is a geo: intent
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

        // Register a handler so we take the chooser path
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
