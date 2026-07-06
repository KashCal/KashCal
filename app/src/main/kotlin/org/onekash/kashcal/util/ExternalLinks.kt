package org.onekash.kashcal.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Canonical outbound web links and a safe launcher for them.
 *
 * Centralizing the URLs keeps them from drifting when duplicated across screens. [openUrl] guards
 * against [ActivityNotFoundException] so a device/profile with no browser (managed work profile,
 * kiosk build) never crashes when a link is tapped.
 */
object ExternalLinks {

    const val HOME = "https://kashcal.onekash.org/"
    const val DONATE = "https://kashcal.onekash.org/donate/"

    /**
     * Opens [url] in an external handler, returning false (and logging) if none exists instead of
     * throwing. Adds [Intent.FLAG_ACTIVITY_NEW_TASK] so it is safe from non-Activity contexts too.
     */
    fun openUrl(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w("ExternalLinks", "No handler for $url")
            false
        }
    }
}
