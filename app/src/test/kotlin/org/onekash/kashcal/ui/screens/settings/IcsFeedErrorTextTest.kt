package org.onekash.kashcal.ui.screens.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.data.ics.IcsFeedErrors
import org.onekash.kashcal.ui.util.UiMessage
import org.onekash.kashcal.ui.util.resolve
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Maps the error text a feed refresh stores to the text the feed row shows.
 *
 * The literal strings are the texts older versions stored, so rows already in the database keep
 * matching after an update.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class IcsFeedErrorTextTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun shown(stored: String) = icsFeedErrorMessage(stored).resolve(context)

    @Test
    fun `each fixed error maps to its string resource`() {
        val expected = mapOf(
            "Invalid ICS format" to UiMessage.ResId(R.string.ics_fetch_error_not_calendar),
            "Empty response from server" to UiMessage.ResId(R.string.ics_feed_error_empty_response),
            "Authentication required" to UiMessage.ResId(R.string.ics_feed_error_access_denied),
            "Calendar not found (404)" to UiMessage.ResId(R.string.ics_fetch_error_http, listOf(404)),
            "Calendar too large" to UiMessage.ResId(R.string.ics_feed_error_too_large),
            "Calendar too large (413)" to UiMessage.ResId(R.string.ics_feed_error_too_large),
            "Parsed 0 events from non-empty feed" to UiMessage.ResId(R.string.ics_feed_error_unreadable),
        )
        for ((stored, message) in expected) {
            assertEquals(stored, message, icsFeedErrorMessage(stored))
        }
    }

    @Test
    fun `the producers write the texts older versions stored`() {
        // The fixed texts are pinned through the mapper above; these are built or unmapped.
        assertEquals("HTTP 502: Bad Gateway", IcsFeedErrors.http(502, "Bad Gateway"))
        assertEquals("Network error: timeout", IcsFeedErrors.network("timeout"))
        assertEquals("SSL error: null", IcsFeedErrors.ssl(null))
        assertEquals("Failed after 2 retries", IcsFeedErrors.retriesExhausted(2))
        assertEquals("Unknown sync error", IcsFeedErrors.UNKNOWN)
    }

    @Test
    fun `an HTTP error shows its status code`() {
        assertEquals(UiMessage.ResId(R.string.ics_fetch_error_http, listOf(502)), icsFeedErrorMessage("HTTP 502: Bad Gateway"))
        assertEquals(
            context.getString(R.string.ics_fetch_error_http, 502),
            shown("HTTP 502: Bad Gateway")
        )
    }

    @Test
    fun `an HTTP error with no reason phrase still shows its status code`() {
        // OkHttp's Response.message is empty over HTTP/2.
        assertEquals(context.getString(R.string.ics_fetch_error_http, 500), shown("HTTP 500: "))
    }

    @Test
    fun `network and SSL errors map whatever their detail`() {
        assertEquals(context.getString(R.string.error_network_connection_failed), shown("Network error: null"))
        assertEquals(
            context.getString(R.string.error_network_connection_failed),
            shown("Network error: failed to connect to example.test/203.0.113.7 (port 443)")
        )
        assertEquals(context.getString(R.string.error_network_ssl), shown("SSL error: Chain validation failed"))
    }

    @Test
    fun `any other text is shown as stored`() {
        for (stored in listOf("Unknown sync error", "Failed after 2 retries", "HTTP/1.1 header parser received no bytes")) {
            assertEquals(UiMessage.Literal(stored), icsFeedErrorMessage(stored))
        }
    }

    @Test
    fun `one missing event is named`() {
        assertEquals("“Team sync” wasn't saved.", shown(IcsFeedErrors.notSaved(1, listOf("Team sync"))))
    }

    @Test
    fun `several missing events show the count and one title`() {
        assertEquals(
            UiMessage.Plural(R.plurals.ics_feed_error_events_not_saved_including, 3, listOf(3, "Team sync")),
            icsFeedErrorMessage(IcsFeedErrors.notSaved(3, listOf("", "Team sync", "Work review")))
        )
        assertEquals(
            "3 events weren't saved, including “Team sync”.",
            shown(IcsFeedErrors.notSaved(3, listOf("Team sync", "Work review")))
        )
    }

    @Test
    fun `missing events without a title show the count`() {
        assertEquals("1 event wasn't saved.", shown(IcsFeedErrors.notSaved(1, listOf(" "))))
        assertEquals("2 events weren't saved.", shown(IcsFeedErrors.notSaved(2, emptyList())))
    }

    @Test
    fun `a long or multi-line title is stored short and on one line`() {
        val stored = IcsFeedErrors.notSaved(1, listOf("Line one\nline two " + "x".repeat(200)))
        assertTrue(stored, stored.lines().size == 1)
        val title = (icsFeedErrorMessage(stored) as UiMessage.ResId).args.single() as String
        assertTrue(title, title.length <= 60 && title.startsWith("Line one line two"))
    }
}
