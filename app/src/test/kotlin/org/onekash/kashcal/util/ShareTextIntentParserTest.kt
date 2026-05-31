package org.onekash.kashcal.util

import android.content.Intent
import android.text.SpannableString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ShareTextIntentParserTest {

    private val nowMs = 1_704_067_200_000L // 2024-01-01T00:00:00Z

    // ==================== Negative cases ====================

    @Test
    fun `null intent returns null`() {
        assertNull(ShareTextIntentParser.parse(null, nowMs))
    }

    @Test
    fun `wrong action returns null`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Lunch")
        }
        assertNull(ShareTextIntentParser.parse(intent, nowMs))
    }

    @Test
    fun `wrong mime type returns null`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_TEXT, "Lunch")
        }
        assertNull(ShareTextIntentParser.parse(intent, nowMs))
    }

    @Test
    fun `null mime type returns null`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, "Lunch")
        }
        assertNull(ShareTextIntentParser.parse(intent, nowMs))
    }

    @Test
    fun `blank EXTRA_TEXT and no EXTRA_SUBJECT returns null`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "   ")
        }
        assertNull(ShareTextIntentParser.parse(intent, nowMs))
    }

    @Test
    fun `missing both extras returns null`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
        }
        assertNull(ShareTextIntentParser.parse(intent, nowMs))
    }

    // ==================== Short path ====================

    @Test
    fun `EXTRA_TEXT preferred over EXTRA_SUBJECT`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Body text")
            putExtra(Intent.EXTRA_SUBJECT, "Subject text")
        }
        val result = ShareTextIntentParser.parse(intent, nowMs)
        assertTrue(result is ShareTextResult.Short)
        result as ShareTextResult.Short
        assertEquals("Body text", result.text)
        assertEquals(nowMs, result.referenceMs)
    }

    @Test
    fun `falls back to EXTRA_SUBJECT when EXTRA_TEXT absent`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Subject only")
        }
        val result = ShareTextIntentParser.parse(intent, nowMs)
        assertTrue(result is ShareTextResult.Short)
        assertEquals("Subject only", (result as ShareTextResult.Short).text)
    }

    @Test
    fun `falls back to EXTRA_SUBJECT when EXTRA_TEXT is whitespace`() {
        // Some senders set EXTRA_TEXT="" or whitespace for header-only shares.
        // Without this fallback, the subject is silently lost.
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "   ")
            putExtra(Intent.EXTRA_SUBJECT, "Real subject")
        }
        val result = ShareTextIntentParser.parse(intent, nowMs)
        assertTrue(result is ShareTextResult.Short)
        assertEquals("Real subject", (result as ShareTextResult.Short).text)
    }

    @Test
    fun `URL is extracted into location`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Standup https://meet.example.com/x at 10am")
        }
        val result = ShareTextIntentParser.parse(intent, nowMs)
        assertTrue(result is ShareTextResult.Short)
        result as ShareTextResult.Short
        assertEquals("https://meet.example.com/x", result.location)
        assertEquals("Standup at 10am", result.text)
    }

    @Test
    fun `Spannable EXTRA_TEXT is coerced via toString`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, SpannableString("Lunch tomorrow") as CharSequence)
        }
        val result = ShareTextIntentParser.parse(intent, nowMs)
        assertTrue(result is ShareTextResult.Short)
        assertEquals("Lunch tomorrow", (result as ShareTextResult.Short).text)
    }

    @Test
    fun `referenceMs equals the supplied nowMs`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Lunch")
        }
        val result = ShareTextIntentParser.parse(intent, 9_999_999L)
        assertEquals(9_999_999L, (result as ShareTextResult.Short).referenceMs)
    }

    // ==================== Long path ====================

    @Test
    fun `oversized input returns Long with title cap and full description`() {
        val firstLine = "Quarterly review with the cross-functional product committee"
        val body = "Body line that is somewhat verbose. ".repeat(20)
        val raw = "$firstLine\n$body"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, raw)
        }
        val result = ShareTextIntentParser.parse(intent, nowMs)
        assertTrue(result is ShareTextResult.Long)
        result as ShareTextResult.Long
        assertTrue("title <= 80 chars", result.title.length <= 80)
        assertEquals(raw, result.description)
        assertEquals(nowMs, result.referenceMs)
    }
}
