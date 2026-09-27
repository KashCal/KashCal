package org.onekash.kashcal.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the exact-alarm permission pair that reminders and the midnight widget
 * rollover depend on.
 *
 * USE_EXACT_ALARM only exists from API 33. On Android 12/12L (API 31/32) the only
 * way to schedule exact alarms is SCHEDULE_EXACT_ALARM, which the platform grants
 * at install on those versions. Without it, canScheduleExactAlarms() is false and
 * every reminder silently falls back to an inexact alarm that can fire minutes
 * late. Capping it at maxSdkVersion 32 keeps it off 13+, where USE_EXACT_ALARM
 * covers the app; from Android 14 SCHEDULE_EXACT_ALARM is no longer pre-granted to
 * new installs.
 *
 * Unit tests run at a single SDK level, so this can't be caught behaviourally;
 * the manifest is read directly instead.
 */
class ExactAlarmManifestTest {

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val USE_EXACT_ALARM = "android.permission.USE_EXACT_ALARM"
        const val SCHEDULE_EXACT_ALARM = "android.permission.SCHEDULE_EXACT_ALARM"

        fun manifestFile(): File {
            val relative = "src/main/AndroidManifest.xml"
            val candidates = listOf(File(relative), File("app/$relative"))
            return candidates.firstOrNull { it.isFile }
                ?: error(
                    "Could not locate AndroidManifest.xml from working dir " +
                        "'${File(".").absolutePath}'. Tried: " +
                        candidates.joinToString { it.path }
                )
        }

        fun usesPermissions(): Map<String, Element> {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(manifestFile())
            val nodes = doc.getElementsByTagName("uses-permission")
            return (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .associateBy { it.getAttributeNS(ANDROID_NS, "name") }
        }
    }

    @Test
    fun `USE_EXACT_ALARM is declared without an sdk cap`() {
        val element = usesPermissions()[USE_EXACT_ALARM]
        assertNotNull("Manifest must declare $USE_EXACT_ALARM for Android 13+", element)
        assertEquals(
            "$USE_EXACT_ALARM must not be capped by maxSdkVersion",
            "",
            element!!.getAttributeNS(ANDROID_NS, "maxSdkVersion")
        )
    }

    @Test
    fun `SCHEDULE_EXACT_ALARM is declared for Android 12 and 12L only`() {
        val element = usesPermissions()[SCHEDULE_EXACT_ALARM]
        assertNotNull(
            "Manifest must declare $SCHEDULE_EXACT_ALARM so Android 12/12L can schedule " +
                "exact alarms (USE_EXACT_ALARM does not exist before API 33)",
            element
        )
        assertEquals(
            "$SCHEDULE_EXACT_ALARM must be capped at maxSdkVersion 32 so 13+ relies on " +
                "$USE_EXACT_ALARM alone",
            "32",
            element!!.getAttributeNS(ANDROID_NS, "maxSdkVersion")
        )
    }

    @Test
    fun `manifest parse finds uses-permission entries`() {
        // Sanity check: an empty map would make the assertions above meaningless.
        assertNull(usesPermissions()[""])
        assertNotNull(usesPermissions()["android.permission.POST_NOTIFICATIONS"])
    }
}
