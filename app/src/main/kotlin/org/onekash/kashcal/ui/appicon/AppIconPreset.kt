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
 * One alias is enabled at a time; switching enables the chosen alias and disables the others
 * ([AppIconSwitchPlan], [AppIconUtility]). The component state is the source of truth; no
 * preference is persisted.
 *
 * [DEFAULT] is the only alias enabled at install. [SUPPORTER] and [SUPPORTER_CALENDAR] share one
 * gold icon and differ only in the launcher label: "KashCal" vs "Calendar".
 *
 * @property aliasSuffix the alias class name relative to the application package.
 * @property previewForegroundRes the adaptive icon's foreground layer. Compose's painterResource
 *   can't load the adaptive-icon XML, so the picker draws this over
 *   [R.color.ic_launcher_background], as AppLockVeil does.
 * @property labelRes the picker row label; the launcher label lives in the manifest.
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
 * Lists the component-state changes that switch the launcher icon to [toEnable].
 *
 * [AppIconUtility.setAppIcon] enables the target before disabling the others, so the app is never
 * left with zero enabled launcher aliases, which would remove it from the launcher. Pure, so the
 * decision is testable without a PackageManager.
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
