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
 * Shared Glance state keys used by [UpcomingWidget], [AgendaWidget], [WeekWidget],
 * and [MonthWidget].
 *
 * These widgets each declare `override val stateDefinition = PreferencesGlanceStateDefinition`,
 * which gives every placed widget instance its own Preferences bag (keyed by glanceId).
 * [WIDGET_REFRESH_STAMP] is written by [WidgetUpdateManager] before calling `updateAll()` so
 * that the session-scoped `provideContent` recomposes with a new key — re-running the
 * `produceState` block that drives data fetches. See the MonthWidget file-level KDoc for the
 * underlying Glance 1.1 session-management rationale.
 */
internal val WIDGET_REFRESH_STAMP = longPreferencesKey("widget_refresh_stamp")

/**
 * Epoch-millis deadline for the header refresh "syncing" cue. Written by [WidgetRefreshAction]
 * when the user taps refresh; the header dims its refresh glyph while `now < deadline`. Stored as
 * a self-expiring deadline (rather than a plain boolean) so the cue can never get stuck: even if
 * the action's coroutine is killed before it can clear the flag, the next recomposition past the
 * deadline reads the glyph as idle. See [isRefreshCueActive].
 */
internal val WIDGET_REFRESHING_UNTIL = longPreferencesKey("widget_refreshing_until")

/**
 * Whether the refresh "syncing" cue should currently render, given the stored deadline and the
 * current time. Pure so the self-expiry contract can be unit-tested without a render harness.
 */
internal fun isRefreshCueActive(refreshingUntil: Long?, nowMs: Long): Boolean =
    (refreshingUntil ?: 0L) > nowMs

/** How long the tap-refresh cue stays visible before the glyph settles back to idle. */
internal const val WIDGET_REFRESH_CUE_DURATION_MS = 800L

/**
 * Monotonically-increasing counter used by [WidgetUpdateManager] when writing
 * [WIDGET_REFRESH_STAMP]. Seeded from `System.currentTimeMillis()` at class load so stamps
 * remain roughly clock-aligned (useful for debugging) but distinct across same-millisecond
 * bumps that can occur during CalDAV batched sync completion.
 */
private val stampCounter = AtomicLong(System.currentTimeMillis())

/**
 * Returns a strictly monotonic [Long] for use as the next value of [WIDGET_REFRESH_STAMP].
 * Guaranteed distinct from every prior value returned in this process.
 */
internal fun nextRefreshStamp(): Long = stampCounter.incrementAndGet()

private const val TAG_BUMP = "WidgetStateKeys"

/**
 * Write a new value of [WIDGET_REFRESH_STAMP] to every placed instance of [widgetClass].
 * Must be called BEFORE `SomeWidget().updateAll(context)` — the stamp write is what triggers
 * Glance's active `provideContent` session to recompose with a new `produceState` key.
 *
 * Errors from [GlanceAppWidgetManager] (e.g. no widgets placed) are swallowed; the caller
 * already handles downstream update errors.
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
 * Bump refresh stamps and call `updateAll()` on every event-driven widget in parallel.
 * Used by WidgetUpdateManager, WidgetUpdateWorker, and WidgetRetryWorker — extracted here
 * so adding a new widget requires one edit, not three.
 *
 * Stamp write triggers recomposition of active provideContent sessions; the subsequent
 * updateAll is belt-and-braces for freshly-cold sessions. DateWidget is normally omitted: its
 * content depends only on today's date, refreshed by midnight alarm + periodic worker — so
 * event-driven refreshes skip it. Set [includeDateWidget] for changes that DO affect its
 * appearance (e.g. accent color), so it recolors immediately rather than waiting for midnight.
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
        // DateWidget is normally omitted (its content is date-only, refreshed by midnight alarm +
        // periodic worker), but color changes DO affect it, so include it then. It reads the same
        // refresh stamp and keys its accent producer on it, so it recolors immediately.
        if (includeDateWidget) add(DateWidget::class.java to DateWidget())
    }
    widgets.forEach { (cls, instance) ->
        // Widgets refresh in parallel, but WITHIN each widget the stamp write must complete
        // before updateAll (see bumpRefreshStamp docs): updateAll recomposes provideContent,
        // and it must see the NEW stamp so produceState re-runs its data + accent-color fetch.
        // Racing them (two sibling launches) let updateAll win and recompose on the old stamp,
        // leaving stale data/colors on some widgets nondeterministically.
        launch {
            bumpRefreshStamp(context, cls)
            instance.updateAll(context)
        }
    }
}
