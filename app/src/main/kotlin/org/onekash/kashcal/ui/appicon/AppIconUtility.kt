package org.onekash.kashcal.ui.appicon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.time.LocalDate

/**
 * Reads and switches the active launcher icon by toggling the manifest activity-aliases.
 *
 * There is no stored preference: the PackageManager component state is the source of truth.
 * Every write uses [PackageManager.DONT_KILL_APP], so the process isn't killed. The platform still
 * closes a task launched from an alias when that alias is disabled, so a pick in the picker can
 * restart the app; when a background date switch may run is decided by [DateIconRefresher].
 *
 * Every switch and refresh in the process runs under one lock. The picker and the date refreshes
 * can run at the same time, and on API 31 and 32, where a switch is one call per alias, two
 * interleaved switches could otherwise leave no alias enabled.
 */
class AppIconUtility internal constructor(
    context: Context,
    private val writer: ComponentStateWriter =
        PackageManagerComponentStateWriter(context.applicationContext.packageManager),
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {

    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager

    /**
     * Returns the active preset from component state. Writes nothing, so it is safe to call from
     * composition. Returns [AppIconPreset.DEFAULT] when no alias reports enabled; only
     * [refreshDateIcon] and [setAppIcon] repair that state.
     */
    fun currentPreset(): AppIconPreset {
        // Stops at the first enabled alias in [presetOf]'s order: one read for most users, since
        // this runs on the main thread from composition.
        val active = AppIconPreset.entries.firstOrNull { preset ->
            preset.fixedAliasSuffix?.let(::isEnabled) == true
        } ?: AppIconPreset.TODAYS_DATE.takeIf { LauncherAliases.DAYS.any(::isEnabled) }
        if (active == null) Log.w(TAG, "No app-icon alias enabled; reporting DEFAULT")
        return active ?: AppIconPreset.DEFAULT
    }

    /**
     * Returns the preset [enabled] belongs to, or null when it is empty. With more than one alias
     * enabled a static alias wins over a day alias, DEFAULT first. An interrupted switch on API
     * 31-32 leaves the same pair in either direction, so this can undo a pick of the date icon;
     * the date to static direction has the longer window (one write per alias turned off), so it
     * is the likelier cause.
     */
    private fun presetOf(enabled: List<String>): AppIconPreset? =
        AppIconPreset.entries.firstOrNull { it.fixedAliasSuffix in enabled }
            ?: AppIconPreset.TODAYS_DATE.takeIf { enabled.isNotEmpty() }

    /**
     * Enables [target]'s alias for [today] and disables the others. On API 33 and later this is
     * one atomic batch; earlier, the target is enabled first, so one launcher entry stays live.
     */
    fun setAppIcon(target: AppIconPreset, today: LocalDate = LocalDate.now()) {
        synchronized(LOCK) { apply(AppIconSwitchPlan.forTarget(target, today)) }
    }

    /**
     * Moves the date icon to [today]'s alias when it shows another day, enables DEFAULT when no
     * alias is enabled, and keeps only [presetOf]'s alias when several are. Writes nothing in every
     * other case.
     *
     * A switch that turns an alias off runs only when [canSwitch] allows it, checked under the lock
     * just before writing: turning off the alias a task was launched from closes that task. A
     * deferred switch is retried by the next trigger. Restoring DEFAULT turns nothing off, so it
     * doesn't wait.
     *
     * @return true when it wrote a switch, false when nothing was needed or it deferred.
     */
    fun refreshDateIcon(
        today: LocalDate,
        canSwitch: () -> Boolean = { true },
    ): Boolean = synchronized(LOCK) {
        val enabled = enabledAliases()
        val preset = presetOf(enabled)
        val plan = when {
            preset == null -> {
                Log.w(TAG, "No app-icon alias enabled; restoring the default icon")
                AppIconSwitchPlan.forTarget(AppIconPreset.DEFAULT, today)
            }
            preset != AppIconPreset.TODAYS_DATE ->
                if (enabled.size > 1) {
                    Log.w(TAG, "Several app-icon aliases enabled; keeping $preset")
                    AppIconSwitchPlan.forTarget(preset, today)
                } else {
                    return@synchronized false
                }
            enabled == listOf(LauncherAliases.daySuffix(today.dayOfMonth)) ->
                return@synchronized false
            else -> AppIconSwitchPlan.forTarget(AppIconPreset.TODAYS_DATE, today)
        }
        val turnsSomethingOff = enabled.any { it != plan.toEnable }
        if (turnsSomethingOff && !canSwitch()) {
            Log.w(TAG, "App-icon switch deferred: KashCal is in use")
            return@synchronized false
        }
        apply(plan)
        true
    }

    /** Returns the aliases that are enabled, in [LauncherAliases.ALL] order. */
    private fun enabledAliases(): List<String> = LauncherAliases.ALL.filter(::isEnabled)

    /**
     * True when [suffix]'s alias is enabled. The default alias ships without `android:enabled`, so
     * it reports [PackageManager.COMPONENT_ENABLED_STATE_DEFAULT] until first toggled; that counts
     * as enabled.
     */
    private fun isEnabled(suffix: String): Boolean {
        val state = pm.getComponentEnabledSetting(LauncherAliases.componentName(appContext, suffix))
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (suffix == AppIconPreset.DEFAULT.fixedAliasSuffix &&
                state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
    }

    private fun apply(plan: AppIconSwitchPlan) {
        val enable = component(plan.toEnable) to PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val disable = plan.toDisable.map {
            component(it) to PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            writer.setAll(listOf(enable) + disable)
        } else {
            writer.setOne(enable.first, enable.second)
            disable.forEach { (component, state) -> writer.setOne(component, state) }
        }
    }

    private fun component(suffix: String) = LauncherAliases.componentName(appContext, suffix)

    private companion object {
        const val TAG = "AppIconUtility"

        /** Shared by every instance: the picker and DateIconRefresher each build one. */
        val LOCK = Any()
    }
}

/** Writes launcher-alias enabled states; a seam so tests can record the order and batching. */
internal interface ComponentStateWriter {

    /** Applies every change in one atomic call. Only used on API 33 and later. */
    fun setAll(changes: List<Pair<ComponentName, Int>>)

    /** Applies one change. */
    fun setOne(component: ComponentName, state: Int)
}

/**
 * Writes through [PackageManager] with [PackageManager.DONT_KILL_APP] on every entry. A batch whose
 * entries mix that flag throws IllegalArgumentException, so it is never mixed.
 */
internal class PackageManagerComponentStateWriter(
    private val pm: PackageManager,
) : ComponentStateWriter {

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun setAll(changes: List<Pair<ComponentName, Int>>) {
        pm.setComponentEnabledSettings(
            changes.map { (component, state) ->
                PackageManager.ComponentEnabledSetting(
                    component,
                    state,
                    PackageManager.DONT_KILL_APP,
                )
            }
        )
    }

    override fun setOne(component: ComponentName, state: Int) {
        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }
}
