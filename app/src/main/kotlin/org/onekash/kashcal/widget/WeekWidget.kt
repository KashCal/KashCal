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
import androidx.glance.state.PreferencesGlanceStateDefinition
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.onekash.kashcal.data.preferences.KashCalDataStore

/**
 * Week View widget showing events for the next 7 days (today + 6 days).
 *
 * Features:
 * - Shows 7-day rolling week in a scrollable list
 * - Up to 5 events per day with overflow indicator
 * - Day headers with "Today" highlight
 * - Tap day header → navigate to that day in app
 * - Tap event → open event quick view
 * - Tap empty day → create event on that day
 *
 * Updates:
 * - On event create/update/delete
 * - On sync completion
 * - At midnight (new day)
 * - Periodically (every 30 minutes)
 *
 * State management:
 * - [WIDGET_REFRESH_STAMP] stored in Glance PreferencesGlanceStateDefinition
 * - Data fetch lives inside [provideContent] via [fetchWeekData] so Glance 1.1's
 *   session-scoped recomposition actually re-runs the fetch (see MonthWidget KDoc)
 */
class WeekWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override val previewSizeMode = WidgetPreviewSizes.WEEK

    override val stateDefinition = PreferencesGlanceStateDefinition

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WeekWidgetEntryPoint {
        fun widgetDataRepository(): WidgetDataRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context, WeekWidgetEntryPoint::class.java)
        val repository = entryPoint.widgetDataRepository()
        val dataStore = KashCalDataStore(context)
        // Resolve the accent BEFORE provideContent so the very first RemoteViews already carry the
        // picked seed. Seeding produceState with null would render one frame on the platform dynamic
        // palette (null ?: GlanceTheme.colors) and only swap to the seed on a later push — which, if
        // the host snapshots the widget before that push lands, leaves a SEED user showing wallpaper
        // colors ("randomly didn't take the tint"). null here still means the genuine DYNAMIC source.
        val initialAccent = resolveWidgetAccentColors(context, dataStore).colors

        provideContent {
            val prefs = currentState<Preferences>()
            val stamp = prefs[WIDGET_REFRESH_STAMP] ?: 0L
            val isRefreshing = isRefreshCueActive(prefs[WIDGET_REFRESHING_UNTIL], System.currentTimeMillis())
            // Empty-events seed: empty week may flash briefly on cold start before
            // fetchWeekData resolves — accepted trade-off, no dedicated loading UI.
            val data by produceState(
                initialValue = WeekData(
                    weekEvents = emptyMap(),
                    showEventEmojis = true,
                    maxEventsPerDay = 5,
                    timePattern = "h:mm a",
                    detailedRows = false
                ),
                key1 = stamp
            ) {
                value = fetchWeekData(repository, dataStore, context)
            }
            val accentColors by produceState(initialValue = initialAccent, key1 = stamp) {
                value = resolveWidgetAccentColors(context, dataStore).colors
            }
            GlanceTheme(colors = accentColors ?: GlanceTheme.colors) {
                WeekWidgetContent(
                    weekEvents = data.weekEvents,
                    showEventEmojis = data.showEventEmojis,
                    timePattern = data.timePattern,
                    maxEventsPerDay = data.maxEventsPerDay,
                    isRefreshing = isRefreshing,
                    detailedRows = data.detailedRows
                )
            }
        }
    }

    /**
     * Renders a sample week into the widget picker. Some sample days are deliberately
     * empty so the preview also shows what a quiet day looks like.
     */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { WeekPreviewContent(context) }
    }
}
