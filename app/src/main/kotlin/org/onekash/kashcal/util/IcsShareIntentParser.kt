package org.onekash.kashcal.util

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * Maps an `Intent.ACTION_SEND` calendar-file share to the shared `.ics` [Uri],
 * or null when the intent isn't an ICS share.
 *
 * This is the share-sheet counterpart to the "Open with" (`ACTION_VIEW`) path:
 * when another app shares an `.ics` (email attachment, file manager, browser
 * download), the file arrives in `EXTRA_STREAM` rather than `intent.data`.
 * Callers route the returned Uri into the existing
 * [org.onekash.kashcal.ui.viewmodels.PendingAction.ImportIcsFile] pipeline.
 *
 * The classification trusts `intent.type` because the manifest registers only
 * the three concrete ICS mime types for `ACTION_SEND`, so a resolved share
 * always carries one of them. The `.ics` path-suffix check is a defensive
 * fallback for generic-typed intents that reach us by other means (e.g.
 * `onNewIntent`); it isn't a primary share-sheet path.
 *
 * Plain-text shares are intentionally NOT handled here — they belong to
 * [ShareIntentRouter], which must be consulted first in the dispatch chain.
 */
object IcsShareIntentParser {

    private val ICS_MIME_TYPES = listOf(
        "text/calendar",
        "application/ics",
        "text/x-vcalendar"
    )

    fun isIcsMimeType(mimeType: String?): Boolean = mimeType in ICS_MIME_TYPES

    fun parse(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null

        // A malicious sender's Bundle can throw on unparcel; never crash the
        // share path. Mirrors ShareTextIntentParser.readExtras.
        val uri = try {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        } catch (e: RuntimeException) {
            null
        } ?: return null

        val isIcs = isIcsMimeType(intent.type) ||
            uri.path?.endsWith(".ics", ignoreCase = true) == true
        return if (isIcs) uri else null
    }
}
