package org.onekash.kashcal.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/** Change the host's resize floor as well as the rendered grid. No scrolling is needed. */
internal fun updateMonthWidgetSizing(context: Context, weekCount: Int) {
    val rows = weekCount.takeIf { it in 1..6 } ?: 6
    val manager = AppWidgetManager.getInstance(context)
    val provider = ComponentName(context, MonthWidgetReceiver::class.java)
    val metadataKey = "org.onekash.kashcal.MONTH_WEEKS_$rows"
    // Avoid a provider-change broadcast loop when a host starts a fresh Glance session.
    val info = manager.installedProviders.firstOrNull { it.provider == provider }
    val expectedHeight = (monthWidgetMinHeightDp(rows) * context.resources.displayMetrics.density + 0.5f).toInt()
    if (info != null && info.minResizeHeight != expectedHeight) {
        manager.updateAppWidgetProviderInfo(provider, metadataKey)
    }
}
