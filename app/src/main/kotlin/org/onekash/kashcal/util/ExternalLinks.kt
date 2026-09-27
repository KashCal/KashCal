package org.onekash.kashcal.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Holds the app's outbound web links, so screens don't duplicate them, and a launcher for them.
 *
 * [openUrl] catches [ActivityNotFoundException], so a device or profile with no browser
 * (managed work profile, kiosk build) never crashes when a link is tapped.
 */
object ExternalLinks {

    const val HOME = "https://kashcal.onekash.org/"
    const val DONATE = "https://kashcal.onekash.org/donate/"
    const val PRIVACY = "https://kashcal.onekash.org/docs/privacy/overview"

    /**
     * Opens [url] in an external handler, or logs and returns false when none exists. Adds
     * [Intent.FLAG_ACTIVITY_NEW_TASK] so it works from a non-Activity context too.
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
