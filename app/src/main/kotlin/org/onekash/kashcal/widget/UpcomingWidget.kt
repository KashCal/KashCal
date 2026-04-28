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

        provideContent {
            val stamp = currentState<Preferences>()[WIDGET_REFRESH_STAMP] ?: 0L
            val state by produceState<UpcomingState>(
                initialValue = UpcomingState.Loading,
                key1 = stamp
            ) {
                value = fetchUpcomingState(repository, dataStore, context)
            }
            GlanceTheme {
                UpcomingWidgetScaffold(state = state)
            }
        }
    }
}
