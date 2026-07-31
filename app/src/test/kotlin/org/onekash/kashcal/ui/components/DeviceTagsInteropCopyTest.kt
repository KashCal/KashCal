package org.onekash.kashcal.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the device-tag interop honesty copy to its resource key and asserts the
 * English text tells the user the two facts that matter: tags travel over CalDAV
 * sync (and reinstalls), and some device calendars may not preserve them.
 *
 * Anchored on the exact resource id rather than a substring scan of strings.xml
 * so it can't pass off some unrelated string that happens to mention "CalDAV".
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceTagsInteropCopyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `interop note names CalDAV travel, the device-calendar caveat, and local removal`() {
        val note = context.getString(R.string.tags_device_interop_note)

        assertTrue("copy must be non-blank", note.isNotBlank())
        assertTrue(
            "copy must state tags travel over CalDAV sync",
            note.contains("CalDAV", ignoreCase = true),
        )
        assertTrue(
            "copy must name the device-calendar caveat honestly",
            note.contains("device calendar", ignoreCase = true),
        )
        assertTrue(
            "copy must reassure that events keep their labels after removal",
            note.contains("keep their labels", ignoreCase = true),
        )
    }

    @Test
    fun `manage note is a short intro to the tag actions`() {
        val note = context.getString(R.string.tags_manage_note)

        assertTrue("copy must be non-blank", note.isNotBlank())
        assertTrue(
            "intro must stay short",
            note.length <= 60,
        )
    }
}
