package org.onekash.kashcal.widget

import android.content.Context
import android.content.Intent
import org.onekash.kashcal.MainActivity

/**
 * Builds the intent that opens Quick Add from outside an Activity, such as the Quick Settings
 * tile.
 *
 * It carries the `widget_action = create_event` extra the widgets and the "New Event" app
 * shortcut use, so MainActivity's `handleIncomingIntent` routes it with no new dispatch code.
 * With no start timestamp, MainActivity shows the Quick Add dialog when Quick Add is enabled and
 * the full form otherwise.
 *
 * FLAG_ACTIVITY_NEW_TASK is required from a non-Activity context (TileService); the singleTop
 * MainActivity then receives it through onNewIntent.
 */
fun buildQuickAddCaptureIntent(context: Context): Intent =
    Intent(Intent.ACTION_VIEW).apply {
        setClass(context, MainActivity::class.java)
        putExtra(EXTRA_ACTION, ACTION_CREATE_EVENT)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
