package org.onekash.kashcal.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri

/**
 * Builds the payload [Intent] for the share-card flow.
 *
 * With both URIs it is an `ACTION_SEND_MULTIPLE`, so the recipient gets the card image and a
 * tappable .ics that adds the event to their calendar. With only the PNG (the ICS export
 * failed) it falls back to `ACTION_SEND` `image/png`; the card still shows the date.
 *
 * In the multiple case `ClipData` carries both URIs alongside `EXTRA_STREAM` so receivers in
 * other processes or tasks get the temporary URI grants. This matters for chooser activities
 * that re-launch the picked target on a different task than the sender.
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
