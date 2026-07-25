package org.onekash.kashcal.widget

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.collection.intSetOf
import androidx.core.content.edit
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import kotlin.reflect.KClass

/**
 * Publishes generated previews of each widget to the system widget picker.
 *
 * Two platform constraints shape this. Generated previews exist only on Android 15 and
 * later, and `GlanceAppWidgetManager.setWidgetPreviews` does not check the running SDK
 * itself, so the caller must. Publishing is also rate limited to roughly two calls an
 * hour for the whole app, while there are five widgets to publish.
 *
 * Together those rule out a single app-wide "already published" flag: one throttled widget
 * would hold the flag back, and the next launch would spend the fresh quota re-publishing
 * widgets that had already succeeded, so the widgets at the end of the list might never
 * get a preview at all. Instead each widget records its own [publishStamp], advanced only
 * after its own call succeeds, and a throttled call ends the run so the remaining quota is
 * left for the next launch.
 */
internal object WidgetPreviewRegistrar {

    private const val TAG = "WidgetPreviewRegistrar"

    /**
     * Registration state lives in its own preferences file, deliberately excluded from
     * backup. Sharing the app's general preferences would restore "already registered"
     * onto a device that has never published a preview, leaving its picker showing
     * placeholders until the next app update.
     */
    const val PREFS_NAME = "widget_previews"

    /** Lowest SDK level where the platform accepts generated previews (Android 15). */
    const val MIN_SDK = 35

    /**
     * How many distinct months a stamp distinguishes before wrapping. Giving the month its
     * own range inside the stamp keeps a version bump from ever landing on the same value
     * as some later month of an older build, which would suppress that re-publish. 4096
     * months is 341 years, so the wrap is unreachable in practice.
     */
    private const val MONTH_SLOTS = 4096

    /** Result of one pass over the widget list. */
    data class Outcome(val registered: Int, val rateLimited: Boolean)

    /** Every widget receiver whose preview is published, in publish order. */
    private val RECEIVERS: List<KClass<out GlanceAppWidgetReceiver>> = listOf(
        AgendaWidgetReceiver::class,
        WeekWidgetReceiver::class,
        MonthWidgetReceiver::class,
        DateWidgetReceiver::class,
        UpcomingWidgetReceiver::class
    )

    /**
     * Identifies the previews a publish would produce, from the [versionCode] that built
     * them and the month [today] falls in.
     *
     * The month is part of the identity because previews are rasterized once and then kept
     * by the system: their sample content is derived from the publish date, so previews
     * published in one month would otherwise go on advertising that month's grid, that
     * week's strip and that day's number indefinitely. Including the month re-publishes at
     * each rollover — five calls a month against a budget of roughly two an hour.
     */
    fun publishStamp(versionCode: Int, today: LocalDate): Int =
        versionCode * MONTH_SLOTS + (today.year * 12 + today.monthValue).mod(MONTH_SLOTS)

    /**
     * Whether one widget needs publishing.
     *
     * Takes [sdkInt] as a parameter rather than reading it from the framework so the
     * Android 15 branch stays reachable in unit tests, which run pinned below it.
     *
     * Any stored stamp that differs from [currentStamp] triggers a re-publish, including
     * one that runs ahead of it: a stamp this build never wrote is not evidence that this
     * build's previews were published.
     */
    fun shouldRegister(sdkInt: Int, lastPublishedStamp: Int, currentStamp: Int): Boolean =
        sdkInt >= MIN_SDK && lastPublishedStamp != currentStamp

    /**
     * Walk the widget list, publishing each widget that needs it.
     *
     * Stops at the first throttled call, leaving the rest for the next launch. A widget's
     * version advances only after its own call reports success, so a throttle or a failure
     * leaves that widget queued rather than silently marked done.
     *
     * State access and the platform call are injected so the walk is testable off-device.
     */
    suspend fun registerBatch(
        sdkInt: Int,
        currentStamp: Int,
        receivers: List<String>,
        lastPublishedStamp: (String) -> Int,
        recordRegistered: (String, Int) -> Unit,
        setPreviews: suspend (String) -> Int
    ): Outcome {
        if (sdkInt < MIN_SDK) return Outcome(registered = 0, rateLimited = false)

        var registered = 0
        for (receiver in receivers) {
            if (!shouldRegister(sdkInt, lastPublishedStamp(receiver), currentStamp)) continue

            val result = try {
                setPreviews(receiver)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A missing preview is cosmetic; never let it escape app startup.
                Log.w(TAG, "Failed to publish preview for $receiver", e)
                continue
            }

            if (result == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_RATE_LIMITED) {
                Log.d(TAG, "Preview publishing throttled at $receiver; resuming next launch")
                return Outcome(registered = registered, rateLimited = true)
            }

            recordRegistered(receiver, currentStamp)
            registered++
        }
        return Outcome(registered = registered, rateLimited = false)
    }

    /**
     * Publish previews for any widget that still needs it, keeping state in this object's
     * own preferences file. Safe to call on any API level; below [MIN_SDK] it returns
     * without touching the platform.
     */
    suspend fun register(context: Context, versionCode: Int): Outcome {
        val nothingDone = Outcome(registered = 0, rateLimited = false)
        if (Build.VERSION.SDK_INT < MIN_SDK) {
            return nothingDone
        }
        return try {
            registerOnSupportedPlatform(context, publishStamp(versionCode, LocalDate.now()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // This runs on a startup coroutine scope with no exception handler, so anything
            // escaping here would take the process down over a cosmetic picker entry. The
            // batch already guards the platform call; this covers reading the preferences
            // file and constructing the manager.
            Log.w(TAG, "Widget preview registration failed", e)
            nothingDone
        }
    }

    @RequiresApi(MIN_SDK)
    private suspend fun registerOnSupportedPlatform(
        context: Context,
        currentStamp: Int
    ): Outcome {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val byName = RECEIVERS.associateBy { it.java.name }

        val outcome = registerBatch(
            sdkInt = Build.VERSION.SDK_INT,
            currentStamp = currentStamp,
            receivers = RECEIVERS.map { it.java.name },
            lastPublishedStamp = { prefs.getInt(it, 0) },
            recordRegistered = { name, stamp -> prefs.edit { putInt(name, stamp) } },
            setPreviews = { name -> setPreviews(context, byName.getValue(name)) }
        )
        if (outcome.registered > 0) {
            Log.i(TAG, "Published ${outcome.registered} widget preview(s)")
        }
        return outcome
    }

    @RequiresApi(MIN_SDK)
    private suspend fun setPreviews(
        context: Context,
        receiver: KClass<out GlanceAppWidgetReceiver>
    ): Int = GlanceAppWidgetManager(context).setWidgetPreviews(
        receiver = receiver,
        // These widgets are home-screen only; there are no lock-screen variants.
        widgetCategories = intSetOf(AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
    )
}
