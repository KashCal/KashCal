package org.onekash.kashcal.widget

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import org.onekash.kashcal.data.preferences.KashCalDataStore

/**
 * Date widget: today's date and no event content.
 *
 * Small, a circular face with the short weekday over the day number ("SAT" over "19"), usable in
 * place of the app icon. Larger, a rounded card with the full weekday over the localized month
 * and day ("Saturday" over "September 19"). [dateWidgetLayout] picks the face from the size.
 * Tapping anywhere opens the app at today.
 *
 * Event-driven refreshes skip this widget, midnight included:
 * [WidgetUpdateManager.updateAllWidgets] leaves it out. It redraws on the system's 30-minute
 * `updatePeriodMillis` update and on [WidgetUpdateManager.updateAllWidgetsForColorChange].
 */
class DateWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override val previewSizeMode = WidgetPreviewSizes.DATE

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dataStore = KashCalDataStore(context)
        // Resolve the accent before provideContent so the first RemoteViews carry the picked seed.
        // Seeding produceState with null renders a frame on the platform dynamic palette, and a
        // host that snapshots it then leaves a seed user on wallpaper colors. Null colors mean the
        // DYNAMIC source on the system face.
        val initialAccent = resolveWidgetAccentColors(context, dataStore).colors
        provideContent {
            // Key the accent fetch on the refresh stamp: updateAll recomposes a warm session but
            // doesn't re-run a keyless producer. The stamp is bumped for this widget only on a
            // color change ([WidgetUpdateManager.updateAllWidgetsForColorChange]).
            val stamp = currentState<Preferences>()[WIDGET_REFRESH_STAMP] ?: 0L
            val accentColors by produceState(initialValue = initialAccent, key1 = stamp) {
                value = resolveWidgetAccentColors(context, dataStore).colors
            }
            GlanceTheme(colors = accentColors ?: GlanceTheme.colors) {
                DateWidgetContent()
            }
        }
    }

    /** Renders today's date into the widget picker; the widget has no sample data to supply. */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { DatePreviewContent() }
    }
}
