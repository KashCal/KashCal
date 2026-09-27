package org.onekash.kashcal.util

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * Maps an `Intent.ACTION_SEND` calendar-file share to the shared `.ics` [Uri], or null when
 * the intent isn't an ICS share.
 *
 * This is the share-sheet counterpart to the "Open with" (`ACTION_VIEW`) path: a shared `.ics`
 * (email attachment, file manager, browser download) arrives in `EXTRA_STREAM`, not
 * `intent.data`. Callers route the Uri into
 * [org.onekash.kashcal.ui.viewmodels.PendingAction.ImportIcsFile].
 *
 * The check trusts `intent.type` because the manifest's calendar-file `ACTION_SEND` filter
 * registers only the three ICS mime types, so a resolved share always carries one. The `.ics`
 * path-suffix check is a fallback for generic-typed intents that arrive by other means, such as
 * `onNewIntent`.
 *
 * Plain-text shares belong to [ShareIntentRouter], which must be consulted first.
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

        // A malicious sender's Bundle can throw on unparcel; never crash the share path.
        // Same guard as ShareTextIntentParser.readExtras.
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
