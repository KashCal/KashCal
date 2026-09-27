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
 * Shows a scrollable list of events across the [UPCOMING_HORIZON_DAYS] calendar days starting
 * today. Empty days are skipped and past events hidden; in-progress and all-day events stay
 * until they end.
 *
 * Refreshes come through [WidgetUpdateManager], for example after event CRUD, sync completion,
 * local midnight (AlarmManager, through Doze) and every 30 minutes (WorkManager), and from the
 * header refresh button ([WidgetRefreshAction]).
 *
 * [WIDGET_REFRESH_STAMP] lives in the Glance preferences state. [bumpRefreshStamp] writes it
 * before each refresh's update to re-key [produceState]. The fetch ([fetchUpcomingState]) runs
 * inside [provideContent] because Glance 1.1 and later recompose the session without calling
 * provideGlance again (see [MonthWidget]).
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
        // Resolve the accent before provideContent so the first RemoteViews carry the picked seed.
        // Seeding produceState with null renders one frame on the platform dynamic palette
        // (null ?: GlanceTheme.colors); if the host snapshots the widget then, a SEED user sees
        // wallpaper colors. null here means the DYNAMIC source on the system face.
        val initialAccent = resolveWidgetAccentColors(context, dataStore).colors

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
                value = resolveWidgetAccentColors(context, dataStore).colors
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
