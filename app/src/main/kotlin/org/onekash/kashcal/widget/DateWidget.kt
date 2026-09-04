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
 * Date widget showing today's date in an icon-like format.
 *
 * Size-responsive: at a small size it looks like an adaptive app icon showing the
 * current date; grown larger it becomes a rounded date-card with the full date.
 * Users can use the small form instead of the app icon for quick access.
 *
 * Features:
 * - Small: circular face with short weekday (e.g., "SAT") over the date number (e.g., "19")
 * - Larger: rounded card with the full weekday (e.g., "Saturday") over the full
 *   localized month + day (e.g., "September 19"); the face is chosen from the widget size
 * - Date only — no event content at any size
 * - Background matching system theme; tap anywhere opens app at today's view
 *
 * Updates:
 * - At midnight (new day)
 * - On event changes (via WidgetUpdateManager)
 * - Periodically (every 30 minutes)
 */
class DateWidget : GlanceAppWidget() {

    /**
     * Use exact size mode for consistent rendering.
     */
    override val sizeMode = SizeMode.Exact

    override val previewSizeMode = WidgetPreviewSizes.DATE

    /**
     * Provide widget content.
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dataStore = KashCalDataStore(context)
        // Resolve the accent BEFORE provideContent so the very first RemoteViews already carry the
        // picked seed. Seeding produceState with null would render one frame on the platform dynamic
        // palette (null ?: GlanceTheme.colors) and only swap to the seed on a later push — which, if
        // the host snapshots the widget before that push lands, leaves a SEED user showing wallpaper
        // colors ("randomly didn't take the tint"). null here still means the genuine DYNAMIC source.
        val initialAccent = resolveWidgetAccentColors(context, dataStore).colors
        provideContent {
            // Key the accent fetch on the refresh stamp so a color change forces produceState to
            // re-run on a warm session (updateAll alone recomposes but does not re-run a keyless
            // producer). WidgetUpdateManager bumps this stamp for DateWidget on color changes.
            val stamp = currentState<Preferences>()[WIDGET_REFRESH_STAMP] ?: 0L
            val accentColors by produceState(initialValue = initialAccent, key1 = stamp) {
                value = resolveWidgetAccentColors(context, dataStore).colors
            }
            GlanceTheme(colors = accentColors ?: GlanceTheme.colors) {
                DateWidgetContent()
            }
        }
    }

    /**
     * Renders today's date into the widget picker. This widget's content is already
     * derived from the current date, so there is no sample data to supply.
     */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { DatePreviewContent() }
    }
}
