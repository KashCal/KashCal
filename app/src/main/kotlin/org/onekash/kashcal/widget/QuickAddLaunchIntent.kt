package org.onekash.kashcal.widget

import android.content.Context
import android.content.Intent
import org.onekash.kashcal.MainActivity

/**
 * Build the intent that opens KashCal's Quick Add capture flow from outside an
 * Activity (e.g. the Quick Settings tile).
 *
 * It reuses the same `widget_action = create_event` extra the widgets and the
 * "New Event" app shortcut use, so [MainActivity.handleIncomingIntent] routes it
 * to Quick Add with no new dispatch code. No start timestamp is set, which is
 * what makes MainActivity show the Quick Add dialog rather than the full form.
 *
 * FLAG_ACTIVITY_NEW_TASK is required because the launch originates from a
 * non-Activity context (TileService); the singleTop MainActivity then receives
 * it via onNewIntent.
 */
fun buildQuickAddCaptureIntent(context: Context): Intent =
    Intent(Intent.ACTION_VIEW).apply {
        setClass(context, MainActivity::class.java)
        putExtra(EXTRA_ACTION, ACTION_CREATE_EVENT)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
