package org.onekash.kashcal.util

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavior of IcsShareIntentParser — the mapper that turns an incoming
 * `Intent.ACTION_SEND` carrying a calendar file (in EXTRA_STREAM) into the
 * shared `.ics` Uri. Tests run at this layer rather than booting MainActivity
 * because the activity boot has a heavy Hilt + Room + Compose fixture cost; the
 * bug surface is the intent → Uri mapping, which is mechanical and pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class IcsShareIntentParserTest {

    private val icsUri: Uri = Uri.parse("content://media/external/file/42")

    @Test
    fun `ACTION_SEND with text-calendar mime returns the EXTRA_STREAM uri`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/calendar"
            putExtra(Intent.EXTRA_STREAM, icsUri)
        }

        assertEquals(icsUri, IcsShareIntentParser.parse(intent))
    }

    @Test
    fun `ACTION_SEND with application-ics mime returns the uri`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/ics"
            putExtra(Intent.EXTRA_STREAM, icsUri)
        }

        assertEquals(icsUri, IcsShareIntentParser.parse(intent))
    }

    @Test
    fun `ACTION_SEND with text-x-vcalendar mime returns the uri`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/x-vcalendar"
            putExtra(Intent.EXTRA_STREAM, icsUri)
        }

        assertEquals(icsUri, IcsShareIntentParser.parse(intent))
    }

    // Defensive guard, not a primary path: the manifest only registers the three
    // concrete ICS mime types, so a generic-typed share won't actually resolve to
    // KashCal through the share sheet. This asserts the parser's own logic — if a
    // generic-typed SEND ever reaches us (e.g. via onNewIntent), an `.ics` suffix
    // is still honored.
    @Test
    fun `ACTION_SEND with generic mime but ics path suffix returns the uri`() {
        val suffixUri = Uri.parse("file:///storage/emulated/0/Download/invite.ics")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, suffixUri)
        }

        assertEquals(suffixUri, IcsShareIntentParser.parse(intent))
    }

    @Test
    fun `ACTION_SEND ics suffix match is case-insensitive`() {
        val upperUri = Uri.parse("file:///storage/emulated/0/Download/INVITE.ICS")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, upperUri)
        }

        assertEquals(upperUri, IcsShareIntentParser.parse(intent))
    }

    @Test
    fun `ACTION_SEND text-plain returns null so ShareIntentRouter handles it`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://x/note.txt"))
        }

        assertNull(IcsShareIntentParser.parse(intent))
    }

    @Test
    fun `ACTION_VIEW with ics mime returns null because it is not a SEND`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "text/calendar"
            data = icsUri
        }

        assertNull(IcsShareIntentParser.parse(intent))
    }

    @Test
    fun `ACTION_SEND with ics mime but no EXTRA_STREAM returns null`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/calendar"
        }

        assertNull(IcsShareIntentParser.parse(intent))
    }

    @Test
    fun `null intent returns null`() {
        assertNull(IcsShareIntentParser.parse(null))
    }

    // Guards the predicate that MainActivity's VIEW (open-with) path also delegates
    // to after this refactor — keeps the single source of truth honest.
    @Test
    fun `isIcsMimeType classifies the three calendar mimes as ics and others as not`() {
        assertTrue(IcsShareIntentParser.isIcsMimeType("text/calendar"))
        assertTrue(IcsShareIntentParser.isIcsMimeType("application/ics"))
        assertTrue(IcsShareIntentParser.isIcsMimeType("text/x-vcalendar"))
        assertFalse(IcsShareIntentParser.isIcsMimeType("text/plain"))
        assertFalse(IcsShareIntentParser.isIcsMimeType(null))
    }
}
