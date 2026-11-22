package org.onekash.kashcal.ui.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class BackupIntervalLabelTest {

    private val resources = RuntimeEnvironment.getApplication().resources

    private fun label(hours: Int) = formatBackupInterval(hours, resources)

    @Test
    fun `hours only`() {
        assertEquals("Every hour", label(1))
        assertEquals("Every 12 hours", label(12))
        assertEquals("Every 23 hours", label(23))
    }

    @Test
    fun `whole days`() {
        assertEquals("Every day", label(24))
        assertEquals("Every 7 days", label(168))
        assertEquals("Every 30 days", label(720))
    }

    @Test
    fun `counts above one show their number even where the one category covers them`() {
        // Russian plural rules put 21 in the "one" category, so a one-form without the number
        // would read "Every hour" for 21 hours.
        val ru = RuntimeEnvironment.getApplication().createConfigurationContext(
            android.content.res.Configuration().apply { setLocale(java.util.Locale.forLanguageTag("ru")) }
        ).resources
        assertTrue(formatBackupInterval(21, ru), formatBackupInterval(21, ru).contains("21"))
        assertTrue(formatBackupInterval(21 * 24, ru), formatBackupInterval(21 * 24, ru).contains("21"))
    }

    @Test
    fun `days and hours`() {
        assertEquals("Every 2 days 6 hours", label(54))
        assertEquals("Every day 1 hour", label(25))
        assertEquals("Every day 12 hours", label(36))
        assertEquals("Every 3 days 1 hour", label(73))
    }
}
