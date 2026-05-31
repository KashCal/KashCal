package org.onekash.kashcal.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.onekash.kashcal.MainActivity

/**
 * Chooser builder that hides KashCal from its own outbound share sheet.
 *
 * Without [Intent.EXTRA_EXCLUDE_COMPONENTS], KashCal's own MainActivity (which
 * registers as an `ACTION_SEND` `text/plain` target) appears in the chooser
 * users see when they tap "Share" inside KashCal. Tapping it loops the share
 * back into Quick Add — confusing, not destructive, but a polish defect.
 *
 * Available since API 24; KashCal's minSdk is 31.
 */
object ShareChooser {

    fun createKashCalChooser(context: Context, payload: Intent, title: CharSequence?): Intent {
        return Intent.createChooser(payload, title).apply {
            putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(context, MainActivity::class.java))
            )
        }
    }
}
