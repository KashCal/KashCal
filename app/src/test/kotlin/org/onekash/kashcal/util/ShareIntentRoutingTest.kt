package org.onekash.kashcal.util

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.viewmodels.PendingAction
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end behavior of ShareIntentRouter — the small mapper that turns an
 * incoming `Intent.ACTION_SEND` into the right `PendingAction`. Tests run at
 * this layer (rather than booting MainActivity) because the activity boot has
 * a heavy Hilt + Room + Compose fixture cost; the bug surface is the parser
 * → action mapping, which is mechanical and pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ShareIntentRoutingTest {

    private val nowMs = 1_704_067_200_000L

    @Test
    fun `short share intent maps to QuickAddFromText with referenceMs anchored to now`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Lunch tomorrow at 1pm")
        }

        val action = ShareIntentRouter.route(intent, nowMs)

        assertTrue(action is PendingAction.QuickAddFromText)
        action as PendingAction.QuickAddFromText
        assertEquals("Lunch tomorrow at 1pm", action.text)
        assertEquals(nowMs, action.referenceMs)
        assertNull(action.location)
    }

    @Test
    fun `short share intent with URL strips it into location`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Standup https://meet.example.com/x at 10am")
        }

        val action = ShareIntentRouter.route(intent, nowMs)

        assertTrue(action is PendingAction.QuickAddFromText)
        action as PendingAction.QuickAddFromText
        assertEquals("Standup at 10am", action.text)
        assertEquals("https://meet.example.com/x", action.location)
    }

    @Test
    fun `long share intent maps to CreateEventFromCalendarIntent with empty invitees`() {
        val firstLine = "Cross-functional product committee quarterly review"
        val raw = firstLine + "\n" + "Body line. ".repeat(60)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, raw)
        }

        val action = ShareIntentRouter.route(intent, nowMs)

        assertTrue(action is PendingAction.CreateEventFromCalendarIntent)
        action as PendingAction.CreateEventFromCalendarIntent
        assertEquals(emptyList<String>(), action.invitees)
        assertTrue(
            "title is the first line truncated to 80 chars",
            action.data.title!!.length <= 80
        )
        assertEquals(raw, action.data.description)
    }

    @Test
    fun `non-share intent returns null`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Lunch")
        }

        assertNull(ShareIntentRouter.route(intent, nowMs))
    }

    @Test
    fun `blank share intent returns null`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "   ")
        }

        assertNull(ShareIntentRouter.route(intent, nowMs))
    }

    @Test
    fun `EXTRA_SUBJECT used when EXTRA_TEXT missing`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Lunch tomorrow")
        }

        val action = ShareIntentRouter.route(intent, nowMs)

        assertTrue(action is PendingAction.QuickAddFromText)
        assertEquals("Lunch tomorrow", (action as PendingAction.QuickAddFromText).text)
    }
}
