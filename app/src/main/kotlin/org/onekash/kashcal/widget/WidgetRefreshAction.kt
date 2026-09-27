package org.onekash.kashcal.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.action.ActionParameters
import androidx.glance.state.PreferencesGlanceStateDefinition
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.session.SyncTrigger

private const val TAG = "WidgetRefreshAction"

/**
 * Names the widgets with a header refresh button, so [WidgetRefreshAction] repaints only the tapped
 * widget's session. Month and Date have no refresh button.
 */
enum class WidgetKind {
    AGENDA,
    WEEK,
    UPCOMING;

    /** Returns the [GlanceAppWidget] to repaint for this kind. */
    fun widget(): GlanceAppWidget = when (this) {
        AGENDA -> AgendaWidget()
        WEEK -> WeekWidget()
        UPCOMING -> UpcomingWidget()
    }
}

/**
 * Gives a widget action, which has no constructor injection, the [SyncScheduler] singleton, the
 * way each widget's entry point gives it [WidgetDataRepository]. Calling the scheduler keeps the
 * sync trigger inside the app process with no PendingIntent to misroute (CWE-927).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetSyncEntryPoint {
    fun syncScheduler(): SyncScheduler
}

/**
 * Handles a header refresh tap on the agenda, week and upcoming widgets: sync, then redraw, with a
 * brief syncing cue.
 *
 * 1. If this widget instance's cue is still active, ignore the tap; the stored deadline is a
 *    per-instance debounce. Taps on two different widgets still both sync, and since
 *    [SyncScheduler.requestImmediateSync] uses `REPLACE` the second cancels and restarts the
 *    first, which loses no data.
 * 2. Write [WIDGET_REFRESHING_UNTIL] and repaint, so the glyph dims.
 * 3. Request an immediate sync.
 * 4. After the cue window, bump the stamp ([bumpRefreshStamp]) and repaint, so the widget
 *    re-reads local data and the glyph settles to idle. Server data arrives later through the
 *    sync worker's [WidgetUpdateManager.updateAllWidgets], called only when the sync changed
 *    something.
 *
 * The cue is a self-expiring deadline, so a failed sync or a killed coroutine can't leave it
 * logically on: any recomposition past the deadline reads it as idle. If the coroutine dies
 * before the settle repaint, the glyph can stay visibly dim until the next recomposition, for
 * example after a sync with changes, a data or settings change, midnight, or the 30-minute
 * update; it settles to idle then without another tap.
 */
class WidgetRefreshAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val kind = parameters[KIND]?.let { runCatching { WidgetKind.valueOf(it) }.getOrNull() }
            ?: run {
                Log.w(TAG, "Refresh action with missing/unknown widget kind; ignoring")
                return
            }
        val widget = kind.widget()

        val now = System.currentTimeMillis()
        // A tap while the cue shows means a sync is already in flight. Checked before the try so
        // a debounced tap skips the settle `finally`, which would clear the first tap's cue early
        // and re-fetch for nothing.
        val currentUntil = getRefreshingUntil(context, glanceId)
        if (isRefreshCueActive(currentUntil, now)) {
            Log.d(TAG, "Refresh already in flight for $kind; ignoring tap")
            return
        }

        try {
            // Show the cue, then trigger the sync.
            setRefreshingUntil(context, glanceId, now + WIDGET_REFRESH_CUE_DURATION_MS)
            widget.update(context, glanceId)

            val scheduler = EntryPointAccessors
                .fromApplication(context, WidgetSyncEntryPoint::class.java)
                .syncScheduler()
            scheduler.requestImmediateSync(trigger = SyncTrigger.BACKGROUND_WIDGET)

            delay(WIDGET_REFRESH_CUE_DURATION_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Refresh action failed for $kind", e)
        } finally {
            // Settle to idle and re-fetch local data however the sync went. Clearing the deadline
            // is best-effort; the render-time expiry is the guarantee.
            try {
                clearRefreshing(context, glanceId)
                bumpRefreshStamp(context, widget.javaClass)
                widget.update(context, glanceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to settle refresh cue for $kind", e)
            }
        }
    }

    private suspend fun getRefreshingUntil(context: Context, glanceId: GlanceId): Long? =
        getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
            .let { prefs: Preferences -> prefs[WIDGET_REFRESHING_UNTIL] }

    private suspend fun setRefreshingUntil(context: Context, glanceId: GlanceId, until: Long) {
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            prefs.toMutablePreferences().apply { this[WIDGET_REFRESHING_UNTIL] = until }
        }
    }

    private suspend fun clearRefreshing(context: Context, glanceId: GlanceId) {
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            prefs.toMutablePreferences().apply { this[WIDGET_REFRESHING_UNTIL] = 0L }
        }
    }

    companion object {
        /** Carries the [WidgetKind] name, which picks the widget to repaint. */
        val KIND = ActionParameters.Key<String>("widget_refresh_kind")
    }
}
