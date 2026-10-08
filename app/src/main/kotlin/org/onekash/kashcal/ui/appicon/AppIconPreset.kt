package org.onekash.kashcal.ui.appicon

import android.content.ComponentName
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.onekash.kashcal.R
import java.time.LocalDate

/**
 * A selectable launcher-icon variant.
 *
 * Each variant is backed by `<activity-alias>` entries in the manifest that target `MainActivity`.
 * One alias is enabled at a time; switching enables the chosen alias and disables the others
 * ([AppIconSwitchPlan], [AppIconUtility]). The component state is the source of truth; no
 * preference is persisted.
 *
 * [DEFAULT] is the only alias enabled at install. [SUPPORTER] and [SUPPORTER_CALENDAR] share one
 * gold icon and differ only in the launcher label: "KashCal" vs "Calendar". [TODAYS_DATE] has one
 * alias per day of the month, each showing that day's number ([DayIcons]); which one it enables
 * depends on the date.
 *
 * @property fixedAliasSuffix the alias class name relative to the application package, or null
 *   for [TODAYS_DATE], whose alias comes from [LauncherAliases.daySuffix].
 * @property fixedPreviewRes the adaptive icon's foreground layer, or null for [TODAYS_DATE], whose
 *   preview is today's ([previewForegroundFor]).
 * @property labelRes the picker row label; the launcher label lives in the manifest.
 */
enum class AppIconPreset(
    internal val fixedAliasSuffix: String?,
    @param:DrawableRes private val fixedPreviewRes: Int?,
    @param:StringRes val labelRes: Int,
) {
    DEFAULT(
        fixedAliasSuffix = ".MainActivityDefault",
        fixedPreviewRes = R.mipmap.ic_launcher_foreground,
        labelRes = R.string.app_icon_default,
    ),
    SUPPORTER(
        fixedAliasSuffix = ".MainActivitySupporter",
        fixedPreviewRes = R.mipmap.ic_launcher_supporter_foreground,
        labelRes = R.string.app_icon_supporter,
    ),
    SUPPORTER_CALENDAR(
        fixedAliasSuffix = ".MainActivitySupporterCalendar",
        fixedPreviewRes = R.mipmap.ic_launcher_supporter_foreground,
        labelRes = R.string.app_icon_supporter_calendar,
    ),
    TODAYS_DATE(
        fixedAliasSuffix = null,
        fixedPreviewRes = null,
        labelRes = R.string.app_icon_todays_date,
    );

    /** Returns the alias this preset enables on [today], relative to the application package. */
    fun aliasSuffixFor(today: LocalDate): String =
        fixedAliasSuffix ?: LauncherAliases.daySuffix(today.dayOfMonth)

    /**
     * Returns the foreground layer the picker shows for this preset on [today]. Compose's
     * painterResource can't load the adaptive-icon XML, so the picker draws this over
     * [R.color.ic_launcher_background], as AppLockVeil does.
     */
    @DrawableRes
    fun previewForegroundFor(today: LocalDate): Int =
        fixedPreviewRes ?: DayIcons.foregroundFor(today.dayOfMonth)

    companion object {
        /** The variant enabled on a fresh install (the only alias without `enabled="false"`). */
        val Default: AppIconPreset get() = DEFAULT

        /**
         * Scales [previewForegroundFor]'s layer inside a clipped tile so the tile shows the 72dp
         * of the 108dp layer a launcher mask shows; drawn unscaled, the mark sits in a wide
         * margin.
         */
        const val PREVIEW_FOREGROUND_SCALE = 108f / 72f
    }
}

/**
 * Every launcher alias in the manifest, relative to the application package: the static
 * presets' aliases and `.MainActivityDay1` to `.MainActivityDay31`. `LauncherAliasManifestTest`
 * checks the manifest against this list.
 */
object LauncherAliases {

    /** Returns the alias for [day] of the month; throws for a day outside 1..31. */
    fun daySuffix(day: Int): String {
        require(day in 1..31) { "day of month out of range: $day" }
        return ".MainActivityDay$day"
    }

    val DAYS: List<String> = (1..31).map(::daySuffix)

    val ALL: List<String> = AppIconPreset.entries.mapNotNull { it.fixedAliasSuffix } + DAYS

    /** Returns the component for [suffix], resolved against the application package. */
    fun componentName(context: Context, suffix: String): ComponentName {
        val appContext = context.applicationContext
        return ComponentName(appContext, appContext.packageName + suffix)
    }
}

/**
 * Lists the component-state changes that switch the launcher icon to [toEnable].
 *
 * [AppIconUtility.setAppIcon] enables the target before disabling the others (or applies both in
 * one atomic batch), so the app is never left with zero enabled launcher aliases, which would
 * remove it from the launcher. Pure, so the decision is testable without a PackageManager.
 *
 * @property toEnable the single alias suffix to enable.
 * @property toDisable every other alias suffix in [LauncherAliases.ALL].
 */
data class AppIconSwitchPlan(
    val toEnable: String,
    val toDisable: List<String>,
) {
    companion object {
        fun forTarget(target: AppIconPreset, today: LocalDate): AppIconSwitchPlan {
            val enable = target.aliasSuffixFor(today)
            return AppIconSwitchPlan(
                toEnable = enable,
                toDisable = LauncherAliases.ALL.filter { it != enable },
            )
        }
    }
}
