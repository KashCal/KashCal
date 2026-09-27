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
 * Checks the Share Availability chooser intent: ACTION_SEND with text/plain and EXTRA_TEXT,
 * wrapped in a chooser with a title.
 *
 * MainActivity's `onShare` lambda passes this intent to `startActivity` and shows a snackbar when
 * no activity can take it, so the builder is the piece with logic and is tested directly on the
 * JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HomeScreenShareIntentTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `chooser intent wraps an ACTION_SEND intent with text plain and EXTRA_TEXT`() {
        val previewText = "Free over the next 7 days (09:00 – 17:00):\n\nMon May 25: 10:00 – 12:00"

        val chooser = buildShareAvailabilityChooserIntent(context, previewText)

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
        // createChooser's title argument lands in EXTRA_TITLE on the chooser intent.
        assertTrue("Chooser title must be present", title != null && title.isNotBlank())
    }
}
