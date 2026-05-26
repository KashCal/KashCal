package org.onekash.kashcal.ui.screens

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.util.buildShareAvailabilityChooserIntent
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Verifies the chooser-intent shape used by the Share Availability flow:
 * ACTION_SEND with text/plain and EXTRA_TEXT, wrapped in a chooser. The
 * Activity-level glue (collecting the lambda from the sheet, calling
 * startActivity) is a thin wrapper around this builder; testing the builder
 * directly is the right grain because it's the only piece with logic and it
 * runs cleanly on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HomeScreenShareIntentTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `chooser intent wraps an ACTION_SEND intent with text plain and EXTRA_TEXT`() {
        val previewText = "Free over the next 7 days (09:00 – 17:00):\n\nMon May 25: 10:00 – 12:00"

        val chooser = buildShareAvailabilityChooserIntent(context, previewText)

        // Outer intent is ACTION_CHOOSER.
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)

        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull("Chooser must wrap an inner intent", inner)
        assertEquals(Intent.ACTION_SEND, inner!!.action)
        assertEquals("text/plain", inner.type)
        assertEquals(previewText, inner.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `chooser intent has a non-blank chooser title`() {
        val chooser = buildShareAvailabilityChooserIntent(context, "anything")
        val title = chooser.getCharSequenceExtra(Intent.EXTRA_TITLE)
        // Title is supplied via createChooser's title arg — it ends up in EXTRA_TITLE on the chooser intent.
        assertTrue("Chooser title must be present", title != null && title.isNotBlank())
    }
}
