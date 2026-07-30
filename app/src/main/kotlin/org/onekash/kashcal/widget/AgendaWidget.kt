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

    override val previewSizeMode = WidgetPreviewSizes.AGENDA

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
            // Empty-events seed: "No events today" may flash briefly on cold start
            // before fetchAgendaData resolves — accepted trade-off, no dedicated loading UI.
            val data by produceState(
                initialValue = AgendaData(
                    events = emptyList(),
                    showEventEmojis = true,
                    maxEventsPerDay = 5,
                    timePattern = "h:mm a",
                    currentDate = "",
                    detailedRows = false
                ),
                key1 = stamp
            ) {
                value = fetchAgendaData(repository, dataStore, context)
            }
            val accentColors by produceState(initialValue = initialAccent, key1 = stamp) {
                value = resolveWidgetAccentColors(context, dataStore).colors
            }
            GlanceTheme(colors = accentColors ?: GlanceTheme.colors) {
                AgendaWidgetContent(
                    events = data.events,
                    currentDate = data.currentDate,
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
     * Renders sample events into the widget picker so this widget is distinguishable
     * from the other four. Deliberately reads no stored data: previews are published
     * once per app version, so anything user-specific would be frozen at publish time.
     */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { AgendaPreviewContent(context) }
    }
}
