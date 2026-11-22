package org.onekash.kashcal.ui.model

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The name an exported calendar's file and calendar header use: the built-in calendar's
 * translated name, any other calendar's stored name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "de")
class OfflineCalendarExportNameTest {

    private val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private fun calendar(url: String, name: String) =
        Calendar(id = 1L, accountId = 1L, caldavUrl = url, displayName = name, color = 0)

    @Test
    fun `the built-in exports under its translated name`() {
        val builtIn = calendar(LocalCalendarInitializer.LOCAL_CALENDAR_URL, LocalCalendarInitializer.LOCAL_CALENDAR_DISPLAY_NAME)
        assertEquals("Lokal", builtIn.exportCalendarName(resources))
    }

    @Test
    fun `another calendar exports under its stored name`() {
        assertEquals("Arbeit", calendar("local://calendar/test", "Arbeit").exportCalendarName(resources))
    }
}
