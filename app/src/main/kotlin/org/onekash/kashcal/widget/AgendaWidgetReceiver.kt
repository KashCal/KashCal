package org.onekash.kashcal.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Broadcast receiver for the Agenda widget.
 *
 * Handles widget lifecycle events (add, update, delete) and
 * delegates to [AgendaWidget] for content rendering.
 */
class AgendaWidgetReceiver : GlanceAppWidgetReceiver() {

    /**
     * The GlanceAppWidget to use for this receiver.
     */
    override val glanceAppWidget: GlanceAppWidget = AgendaWidget()
}
