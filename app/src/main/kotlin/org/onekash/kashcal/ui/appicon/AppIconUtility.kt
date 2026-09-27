package org.onekash.kashcal.ui.appicon

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Reads and switches the active launcher icon by toggling the manifest activity-aliases.
 *
 * There is no stored preference: the PackageManager component state is the source of truth.
 * Switching enables the target alias first, then disables the rest ([AppIconSwitchPlan]), all with
 * [PackageManager.DONT_KILL_APP] so the process isn't force-killed. The launcher may still refresh
 * or briefly restart the app when the active alias changes; the platform does that.
 */
class AppIconUtility(context: Context) {

    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager

    /**
     * Returns the active preset from component state. Writes nothing, so it is safe to call from
     * composition. Returns [AppIconPreset.DEFAULT] when no alias reports enabled, a state
     * [setAppIcon]'s enable-first order doesn't produce; nothing repairs it.
     *
     * [AppIconPreset.DEFAULT]'s alias ships without `android:enabled`, so it reports
     * [PackageManager.COMPONENT_ENABLED_STATE_DEFAULT] until it is first toggled; that counts as
     * enabled.
     */
    fun currentPreset(): AppIconPreset {
        val active = AppIconPreset.entries.firstOrNull { preset ->
            val state = pm.getComponentEnabledSetting(preset.componentName(appContext))
            when {
                preset == AppIconPreset.DEFAULT &&
                    state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> true
                else -> state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
        }
        if (active == null) Log.w(TAG, "No app-icon alias enabled; reporting DEFAULT")
        return active ?: AppIconPreset.DEFAULT
    }

    /** Enables [target]'s alias, then disables the others, so one launcher entry stays live. */
    fun setAppIcon(target: AppIconPreset) {
        val plan = AppIconSwitchPlan.forTarget(target)
        pm.setComponentEnabledSetting(
            plan.toEnable.componentName(appContext),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        plan.toDisable.forEach { preset ->
            pm.setComponentEnabledSetting(
                preset.componentName(appContext),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private companion object {
        const val TAG = "AppIconUtility"
    }
}
