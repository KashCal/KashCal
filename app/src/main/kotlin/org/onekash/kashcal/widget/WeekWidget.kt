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
 * Week View widget: a scrollable list of the 7 days from today.
 *
 * - Each day shows up to the user's events-per-day setting (default 5), then an overflow row.
 * - Day headers highlight today.
 * - Tapping a day header or the overflow row opens that day in the app, an event opens its
 *   quick view, and an empty day creates an event on that day.
 *
 * Refreshes on each [WidgetUpdateManager.updateAllWidgets] call (for example an event write, a
 * sync, midnight or a settings change) and every 30 minutes.
 *
 * [WIDGET_REFRESH_STAMP] lives in the Glance preferences state. The fetch ([fetchWeekData])
 * runs inside [provideContent], keyed on the stamp, because Glance 1.1's session-scoped
 * recomposition re-runs only what is inside it ([MonthWidget] explains the session model).
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
        // Resolve the accent before provideContent so the first RemoteViews carry the picked
        // seed. Seeding produceState with null would render one frame on the platform dynamic
        // palette (null ?: GlanceTheme.colors); if the host snapshots the widget before the next
        // push, a SEED user keeps wallpaper colors. A null here still means the DYNAMIC source.
        val initialAccent = resolveWidgetAccentColors(context, dataStore).colors

        provideContent {
            val prefs = currentState<Preferences>()
            val stamp = prefs[WIDGET_REFRESH_STAMP] ?: 0L
            val isRefreshing = isRefreshCueActive(prefs[WIDGET_REFRESHING_UNTIL], System.currentTimeMillis())
            // Empty-events seed: an empty week may flash on cold start before fetchWeekData
            // resolves. Accepted; there is no dedicated loading UI.
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
     * Renders a sample week into the widget picker. Some sample days are empty so the preview
     * also shows a quiet day.
     */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { WeekPreviewContent(context) }
    }
}
