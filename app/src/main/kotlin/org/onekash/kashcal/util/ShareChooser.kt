package org.onekash.kashcal.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.onekash.kashcal.MainActivity

/**
 * Builds a share chooser that hides KashCal from its own outbound shares.
 *
 * [MainActivity] registers as an `ACTION_SEND` target for plain text and calendar files, so
 * without [Intent.EXTRA_EXCLUDE_COMPONENTS] it appears in the chooser of a share started inside
 * KashCal, and picking it loops the share back into Quick Add or the import sheet.
 *
 * The extra exists since API 24; KashCal's minSdk is 31.
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
