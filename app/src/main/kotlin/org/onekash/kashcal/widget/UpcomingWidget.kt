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
 * Upcoming Events widget — shows a scrollable list of events across the next
 * [UPCOMING_HORIZON_DAYS] calendar days starting today. Empty days are
 * skipped; past events are hidden. In-progress events and all-day events
 * remain visible until they actually end.
 *
 * Refresh triggers (inherited from [WidgetUpdateManager]):
 * - Event CRUD
 * - Sync completion
 * - Local midnight (through Doze via AlarmManager)
 * - Every 30 minutes (WorkManager)
 *
 * State management:
 * - [WIDGET_REFRESH_STAMP] stored in Glance PreferencesGlanceStateDefinition
 * - Bumped by [WidgetUpdateManager] before each `updateAll()` to re-key [produceState]
 * - Data fetch lives inside [provideContent] via [fetchUpcomingState] so Glance 1.1's
 *   session-scoped recomposition actually re-runs the fetch (see MonthWidget KDoc)
 */
class UpcomingWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override val previewSizeMode = WidgetPreviewSizes.UPCOMING

    override val stateDefinition = PreferencesGlanceStateDefinition

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UpcomingWidgetEntryPoint {
        fun widgetDataRepository(): WidgetDataRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context, UpcomingWidgetEntryPoint::class.java
        )
        val repository = entryPoint.widgetDataRepository()
        val dataStore = KashCalDataStore(context)
        // Resolve the accent BEFORE provideContent so the very first RemoteViews already carry the
        // picked seed. Seeding produceState with null would render one frame on the platform dynamic
        // palette (null ?: GlanceTheme.colors) and only swap to the seed on a later push — which, if
        // the host snapshots the widget before that push lands, leaves a SEED user showing wallpaper
        // colors ("randomly didn't take the tint"). null here still means the genuine DYNAMIC source.
        val initialAccent = resolveWidgetAccentColors(dataStore)

        provideContent {
            val prefs = currentState<Preferences>()
            val stamp = prefs[WIDGET_REFRESH_STAMP] ?: 0L
            val isRefreshing = isRefreshCueActive(prefs[WIDGET_REFRESHING_UNTIL], System.currentTimeMillis())
            val state by produceState<UpcomingState>(
                initialValue = UpcomingState.Loading,
                key1 = stamp
            ) {
                value = fetchUpcomingState(repository, dataStore, context)
            }
            val accentColors by produceState(initialValue = initialAccent, key1 = stamp) {
                value = resolveWidgetAccentColors(dataStore)
            }
            GlanceTheme(colors = accentColors ?: GlanceTheme.colors) {
                UpcomingWidgetScaffold(state = state, isRefreshing = isRefreshing)
            }
        }
    }

    /**
     * Renders sample upcoming events into the widget picker. Goes straight to the
     * content composable rather than through the scaffold, so the picker never shows a
     * loading or error state.
     */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { UpcomingPreviewContent(context) }
    }
}
