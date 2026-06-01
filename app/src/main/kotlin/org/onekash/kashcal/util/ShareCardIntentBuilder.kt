package org.onekash.kashcal.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri

/**
 * Builds the payload [Intent] for the share-card flow.
 *
 * When both PNG and ICS URIs are present (the happy path), produces an
 * `ACTION_SEND_MULTIPLE` intent so the recipient gets both: a beautiful
 * image preview AND a tappable .ics file that adds to their calendar.
 *
 * When only the PNG is available (ICS export failed), falls back to plain
 * `ACTION_SEND image/png`. The card alone is still useful — recipients can
 * still read the date and add manually.
 *
 * `ClipData` carries both URIs alongside `EXTRA_STREAM` so receivers
 * running in separate processes/tasks see the temporary URI grants. This
 * matters for chooser activities that re-launch the picked target on a
 * different task than the original sender.
 */
object ShareCardIntentBuilder {

    fun buildPayload(pngUri: Uri, icsUri: Uri?): Intent {
        return if (icsUri != null) {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    arrayListOf(pngUri, icsUri),
                )
                clipData = ClipData(
                    ClipDescription("share-card", arrayOf("image/png", "text/calendar")),
                    ClipData.Item(pngUri),
                ).also { it.addItem(ClipData.Item(icsUri)) }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, pngUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
