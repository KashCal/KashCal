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
 * Today's Agenda widget: the date header over today's events, with time and calendar color.
 *
 * Past events render dimmed with a strikethrough. Tapping an event opens its quick view; tapping
 * the empty state creates an event.
 *
 * Refreshes on each [WidgetUpdateManager.updateAllWidgets] call (for example an event write, a
 * sync, midnight or a settings change) and every 30 minutes. [WIDGET_REFRESH_STAMP] lives in
 * [PreferencesGlanceStateDefinition]; the fetch runs inside [provideContent] via
 * [fetchAgendaData] so a Glance 1.1 session re-runs it on update (see [MonthWidget]).
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
        // Resolve the accent before provideContent so the first RemoteViews carry the picked seed.
        // Seeding produceState with null renders a frame on the platform dynamic palette, and a
        // host that snapshots it then leaves a seed user on wallpaper colors. Null colors mean the
        // DYNAMIC source on the system face.
        val initialAccent = resolveWidgetAccentColors(context, dataStore).colors

        provideContent {
            val prefs = currentState<Preferences>()
            val stamp = prefs[WIDGET_REFRESH_STAMP] ?: 0L
            val isRefreshing = isRefreshCueActive(prefs[WIDGET_REFRESHING_UNTIL], System.currentTimeMillis())
            // "No events today" may flash on cold start until fetchAgendaData resolves; there is
            // no loading UI.
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
     * Renders sample events into the widget picker so this widget stands apart from the other
     * four. Reads no stored data: previews are published once per app version, so anything
     * user-specific would be frozen at publish time.
     */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { AgendaPreviewContent(context) }
    }
}
