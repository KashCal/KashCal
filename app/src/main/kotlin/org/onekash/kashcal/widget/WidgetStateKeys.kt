package org.onekash.kashcal.widget

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Keys the `produceState` fetches of [UpcomingWidget], [AgendaWidget], [WeekWidget], [MonthWidget]
 * and [DateWidget], so a new value re-runs them.
 *
 * Each placed widget instance has its own Preferences state (PreferencesGlanceStateDefinition,
 * keyed by glanceId). [bumpRefreshStamp] writes a new stamp before the widget's `update` or
 * `updateAll`, which recomposes `provideContent` with the new key. [MonthWidget]'s class doc
 * explains why state is read inside `provideContent`.
 */
internal val WIDGET_REFRESH_STAMP = longPreferencesKey("widget_refresh_stamp")

/**
 * Holds the epoch-millis deadline of the header refresh cue. [WidgetRefreshAction] writes it on a
 * tap, and the header dims its refresh glyph while `now < deadline`. A deadline, unlike a boolean,
 * can't get stuck: if the action's coroutine dies before clearing it, the next recomposition past
 * the deadline reads idle ([isRefreshCueActive]).
 */
internal val WIDGET_REFRESHING_UNTIL = longPreferencesKey("widget_refreshing_until")

/**
 * Returns whether the refresh cue shows at [nowMs] for the stored deadline. Pure, so the expiry is
 * unit-testable without a render harness.
 */
internal fun isRefreshCueActive(refreshingUntil: Long?, nowMs: Long): Boolean =
    (refreshingUntil ?: 0L) > nowMs

/** How long the tap-refresh cue stays visible before the glyph settles back to idle. */
internal const val WIDGET_REFRESH_CUE_DURATION_MS = 800L

/**
 * Counts up the [WIDGET_REFRESH_STAMP] values. Seeded from `System.currentTimeMillis()` at class
 * load, so stamps stay roughly clock-aligned for debugging yet differ across bumps in the same
 * millisecond, which can occur during CalDAV batched sync completion.
 */
private val stampCounter = AtomicLong(System.currentTimeMillis())

/** Returns the next [WIDGET_REFRESH_STAMP], larger than every value returned in this process. */
internal fun nextRefreshStamp(): Long = stampCounter.incrementAndGet()

private const val TAG_BUMP = "WidgetStateKeys"

/**
 * Writes a new [WIDGET_REFRESH_STAMP] to every placed instance of [widgetClass].
 *
 * Must complete before the widget's `update` or `updateAll`: the stamp write alone doesn't
 * recompose a running session, and the update reloads state, so an update that runs first
 * recomposes on the old key and re-fetches nothing.
 *
 * Logs and swallows any failure other than cancellation; the caller's update runs regardless.
 */
internal suspend fun <T : GlanceAppWidget> bumpRefreshStamp(
    context: Context,
    widgetClass: Class<T>
) {
    val stamp = nextRefreshStamp()
    try {
        val manager = GlanceAppWidgetManager(context)
        manager.getGlanceIds(widgetClass).forEach { glanceId ->
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                prefs.toMutablePreferences().apply { this[WIDGET_REFRESH_STAMP] = stamp }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG_BUMP, "Failed to bump refresh stamp for ${widgetClass.simpleName}", e)
    }
}

/**
 * Bumps the refresh stamp of every event widget and then updates it, all widgets in parallel.
 * [WidgetUpdateManager], [WidgetUpdateWorker] and [WidgetRetryWorker] all call this, so a new
 * widget is added here once.
 *
 * DateWidget is left out by default: it shows only today's date and refreshes on the platform's
 * 30-minute `updatePeriodMillis` update. Set [includeDateWidget] for changes to its appearance,
 * such as the accent color, so it recolors at once.
 */
internal suspend fun refreshAllWidgets(
    context: Context,
    includeDateWidget: Boolean = false,
): Unit = coroutineScope {
    val widgets = buildList {
        add(AgendaWidget::class.java to AgendaWidget())
        add(WeekWidget::class.java to WeekWidget())
        add(MonthWidget::class.java to MonthWidget())
        add(UpcomingWidget::class.java to UpcomingWidget())
        // DateWidget keys its accent producer on the same stamp, so the bump recolors it.
        if (includeDateWidget) add(DateWidget::class.java to DateWidget())
    }
    widgets.forEach { (cls, instance) ->
        // Sequential within one widget ([bumpRefreshStamp]): run as two sibling launches,
        // updateAll can win and recompose on the old stamp, leaving stale data or colors.
        launch {
            bumpRefreshStamp(context, cls)
            instance.updateAll(context)
        }
    }
}
