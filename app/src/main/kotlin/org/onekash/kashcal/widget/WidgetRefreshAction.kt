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
 * The event-driven widgets that carry a header refresh button. Used to route [WidgetRefreshAction]
 * back to the correct widget class so only the tapped widget's session repaints.
 * (Month has its own nav arrows; Date is date-only — neither gets a refresh button.)
 */
enum class WidgetKind {
    AGENDA,
    WEEK,
    UPCOMING;

    /** The [GlanceAppWidget] instance to repaint for this kind. */
    fun widget(): GlanceAppWidget = when (this) {
        AGENDA -> AgendaWidget()
        WEEK -> WeekWidget()
        UPCOMING -> UpcomingWidget()
    }
}

/**
 * Hilt access to the injected [SyncScheduler] singleton from a widget action, which has no
 * constructor injection. Mirrors the per-widget `*EntryPoint` interfaces used for
 * [WidgetDataRepository]; reaching the scheduler this way (rather than building an explicit
 * PendingIntent) keeps the sync trigger inside the app process and avoids CWE-927.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetSyncEntryPoint {
    fun syncScheduler(): SyncScheduler
}

/**
 * Header "refresh" tap handler for the agenda, week, and upcoming widgets.
 *
 * Refresh = sync + redraw with a brief syncing cue:
 * 1. If this widget instance's cue is already active, the tap is ignored — the stored deadline
 *    doubles as a cheap per-instance debounce against mashing one widget.
 *    [SyncScheduler.requestImmediateSync] uses `REPLACE`, so two syncs kicked off close together
 *    (e.g. tapping two different placed widgets) still cancel-and-restart; that is the scheduler's
 *    existing behavior, not something this debounce prevents. It is bounded — a restarted sync
 *    loses no data — and the common single-widget mash case is fully covered.
 * 2. Otherwise write [WIDGET_REFRESHING_UNTIL] and repaint immediately so the glyph dims (the cue).
 * 3. Kick off an immediate CalDAV sync.
 * 4. After the cue window, re-fetch local data ([bumpRefreshStamp]) and repaint so the glyph
 *    settles back to idle. Fresh *server* data lands later via the existing sync-completion path
 *    ([WidgetUpdateManager.updateAllWidgets], gated on changes).
 *
 * The cue is stored as a self-expiring deadline: a failed/throttled sync or a killed coroutine
 * cannot leave the glyph *logically* stuck dim, because any recomposition past the deadline reads
 * it as idle. If the coroutine is killed before the settle repaint runs, the glyph may stay
 * visually dim until the next recomposition (sync-completion, data change, midnight, or the 30-min
 * periodic update) — bounded, and always resolves to idle without further taps.
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
        // Debounce: a tap while the cue is still showing means a sync is already in flight.
        // Checked BEFORE the try so a debounced tap returns without running the settle `finally` —
        // otherwise it would prematurely clear the first tap's in-flight cue and force a redundant
        // re-fetch. REPLACE-based requestImmediateSync makes mashing cancel-and-restart the sync.
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
            // Settle the glyph back to idle and re-fetch local data, regardless of how the sync went.
            // Clearing the deadline here is best-effort; the render-time self-expiry is the guarantee.
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
        /** ActionParameter carrying the [WidgetKind] name so the handler knows which widget to repaint. */
        val KIND = ActionParameters.Key<String>("widget_refresh_kind")
    }
}
