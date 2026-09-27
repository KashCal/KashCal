package org.onekash.kashcal.util.location

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Opens [address] in a maps app through the app chooser, or on OpenStreetMap web when no app
 * handles geo: URIs (RFC 5870).
 *
 * The chooser always shows, so the user picks the app each time (#19). The handler check uses
 * queryIntentActivities because a try/catch can't see a missing app: Intent.createChooser
 * always resolves, and with no geo: handler it shows "No apps can perform this action" and
 * doesn't throw ActivityNotFoundException. On API 30+ the check sees geo: handlers only through
 * the manifest's `<queries>` entry.
 */
fun openInMaps(context: Context, address: String) {
    if (address.isBlank()) return

    val geoUri = Uri.parse("geo:0,0?q=${Uri.encode(address)}")
    val mapIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
        // Lets the intent start from a non-Activity context.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val hasHandler = context.packageManager.queryIntentActivities(
        mapIntent, PackageManager.MATCH_DEFAULT_ONLY
    ).isNotEmpty()

    if (hasHandler) {
        val chooserIntent = Intent.createChooser(mapIntent, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooserIntent)
    } else {
        val webUri = Uri.parse("https://www.openstreetmap.org/search?query=${Uri.encode(address)}")
        val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(webIntent)
    }
}
