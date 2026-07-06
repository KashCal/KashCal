package org.onekash.kashcal.ui.appicon

import android.content.ComponentName
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.onekash.kashcal.R

/**
 * A selectable launcher-icon variant.
 *
 * Each variant is backed by an `<activity-alias>` in the manifest that targets `MainActivity`.
 * Exactly one alias is enabled at a time; switching the icon enables the chosen alias and disables
 * the others (see [AppIconSwitchPlan] and AppIconUtility). The component state itself is the source
 * of truth — there is no separate persisted preference.
 *
 * [DEFAULT] is the only alias enabled at install. [SUPPORTER] and [SUPPORTER_CALENDAR] share one
 * gold icon and differ only in the launcher label: "KashCal" vs "Calendar".
 *
 * @property aliasSuffix the alias class name relative to the application package.
 * @property previewForegroundRes the adaptive icon's *foreground* layer (a raster the picker can
 *   render over [R.color.ic_launcher_background]). The adaptive-icon XML itself can't be loaded by
 *   Compose's painterResource, so the picker composites the foreground over the background — the
 *   same approach as AppLockVeil.
 * @property labelRes the picker row label (not the launcher label; that lives in the manifest).
 */
enum class AppIconPreset(
    val aliasSuffix: String,
    @param:DrawableRes val previewForegroundRes: Int,
    @param:StringRes val labelRes: Int,
) {
    DEFAULT(
        aliasSuffix = ".MainActivityDefault",
        previewForegroundRes = R.mipmap.ic_launcher_foreground,
        labelRes = R.string.app_icon_default,
    ),
    SUPPORTER(
        aliasSuffix = ".MainActivitySupporter",
        previewForegroundRes = R.mipmap.ic_launcher_supporter_foreground,
        labelRes = R.string.app_icon_supporter,
    ),
    SUPPORTER_CALENDAR(
        aliasSuffix = ".MainActivitySupporterCalendar",
        previewForegroundRes = R.mipmap.ic_launcher_supporter_foreground,
        labelRes = R.string.app_icon_supporter_calendar,
    );

    /** The manifest component this preset enables, resolved against the application package. */
    fun componentName(context: Context): ComponentName {
        val appContext = context.applicationContext
        return ComponentName(appContext, appContext.packageName + aliasSuffix)
    }

    companion object {
        /** The variant enabled on a fresh install (the only alias without `enabled="false"`). */
        val Default: AppIconPreset get() = DEFAULT
    }
}

/**
 * The set of component-state changes to apply when switching to [target].
 *
 * Enabling the target *before* disabling the others guarantees the app is never left with zero
 * enabled launcher aliases (which would remove it from the launcher). This is pure so the
 * enable/disable decision is unit-testable without a PackageManager.
 *
 * @property toEnable the single alias to enable.
 * @property toDisable every other alias.
 */
data class AppIconSwitchPlan(
    val toEnable: AppIconPreset,
    val toDisable: List<AppIconPreset>,
) {
    companion object {
        fun forTarget(target: AppIconPreset): AppIconSwitchPlan =
            AppIconSwitchPlan(
                toEnable = target,
                toDisable = AppIconPreset.entries.filter { it != target },
            )
    }
}
