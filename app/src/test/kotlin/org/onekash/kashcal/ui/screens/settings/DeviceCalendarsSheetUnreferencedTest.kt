package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards against re-introducing the `DeviceCalendarsSheet` composable
 * (replaced by `DeviceCalendarsScreen`).
 */
class DeviceCalendarsSheetUnreferencedTest {

    @Test
    fun deviceCalendarsSheet_fileDeleted() {
        val sheet = File("src/main/kotlin/org/onekash/kashcal/ui/screens/settings/DeviceCalendarsSheet.kt")
        assertFalse(
            "DeviceCalendarsSheet.kt should be deleted after Device Calendars promotion to full screen",
            sheet.exists()
        )
    }

    @Test
    fun deviceCalendarsSheet_symbolUnreferenced_inMainSource() {
        val mainSrc = File("src/main")
        assertTrue("Expected src/main to exist", mainSrc.isDirectory)

        val offenders = mainSrc.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                f.readText().contains("DeviceCalendarsSheet")
            }
            .map { it.relativeTo(mainSrc).path }
            .toList()

        assertTrue(
            "DeviceCalendarsSheet symbol must be unreferenced in src/main, found: $offenders",
            offenders.isEmpty()
        )
    }
}
