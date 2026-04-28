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
 * Today's Agenda widget showing events for the current day.
 *
 * Features:
 * - Shows today's date in header
 * - Lists upcoming events with time and calendar color
 * - Past events shown grayed out with strikethrough
 * - Tap event to open quick view
 * - Tap empty state to create new event
 *
 * Updates:
 * - On event create/update/delete
 * - On sync completion
 * - At midnight (new day)
 * - Periodically (every 30 minutes)
 *
 * State management:
 * - [WIDGET_REFRESH_STAMP] stored in Glance PreferencesGlanceStateDefinition
 * - Data fetch lives inside [provideContent] via [fetchAgendaData] so Glance 1.1's
 *   session-scoped recomposition actually re-runs the fetch (see MonthWidget KDoc)
 */
class AgendaWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override val stateDefinition = PreferencesGlanceStateDefinition

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AgendaWidgetEntryPoint {
        fun widgetDataRepository(): WidgetDataRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context, AgendaWidgetEntryPoint::class.java)
        val repository = entryPoint.widgetDataRepository()
        val dataStore = KashCalDataStore(context)

        provideContent {
            val stamp = currentState<Preferences>()[WIDGET_REFRESH_STAMP] ?: 0L
            // Empty-events seed: "No events today" may flash briefly on cold start
            // before fetchAgendaData resolves — accepted trade-off, no dedicated loading UI.
            val data by produceState(
                initialValue = AgendaData(
                    events = emptyList(),
                    showEventEmojis = true,
                    maxEventsPerDay = 5,
                    timePattern = "h:mm a",
                    currentDate = ""
                ),
                key1 = stamp
            ) {
                value = fetchAgendaData(repository, dataStore, context)
            }
            GlanceTheme {
                AgendaWidgetContent(
                    events = data.events,
                    currentDate = data.currentDate,
                    showEventEmojis = data.showEventEmojis,
                    timePattern = data.timePattern,
                    maxEventsPerDay = data.maxEventsPerDay
                )
            }
        }
    }
}
