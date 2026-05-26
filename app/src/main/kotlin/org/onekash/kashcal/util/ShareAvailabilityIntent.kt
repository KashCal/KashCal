package org.onekash.kashcal.util

import android.content.Context
import android.content.Intent
import org.onekash.kashcal.R

fun buildShareAvailabilityChooserIntent(context: Context, previewText: String): Intent {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, previewText)
    }
    return Intent.createChooser(
        sendIntent,
        context.getString(R.string.share_availability_chooser_title)
    )
}
