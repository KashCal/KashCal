package org.onekash.kashcal.ui.permission

import android.os.Build
import androidx.annotation.StringRes
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.permission.LocalNetworkPermissionManager.Companion.LOCAL_NETWORK_PERMISSION_MIN_SDK

/**
 * The runtime permissions the app-permissions sheet can surface, in the order
 * they appear in the sheet. Contacts and Calendars have been runtime
 * permissions on every supported OS level; Notifications and Local network are
 * only enforced from later levels (see [buildAppPermissionRows]).
 */
enum class AppPermissionKind {
    NOTIFICATIONS,
    CONTACTS,
    CALENDARS,
    LOCAL_NETWORK,
}

/**
 * The trailing affordance for a row. A granted permission reads as [ALLOWED]
 * (quiet, tapping the row deep-links to system settings); anything not granted
 * reads as [ALLOW] (an accent action that fires the runtime request). There is
 * deliberately no permanently-denied trailing: the steady-state read can't tell
 * never-asked from permanently-denied, so the sheet always offers Allow and the
 * composable falls back to the settings deep-link only if the fired request
 * turns out to be a no-op.
 */
enum class PermissionTrailing {
    ALLOWED,
    ALLOW,
}

/**
 * A single row in the app-permissions sheet: which permission, the label and
 * tooltip text to show, and the resolved trailing affordance.
 */
data class AppPermissionRow(
    val kind: AppPermissionKind,
    @StringRes val nameRes: Int,
    @StringRes val whyRes: Int,
    val trailing: PermissionTrailing,
)

/** API level at which POST_NOTIFICATIONS became a runtime permission (Android 13). */
private const val NOTIFICATIONS_PERMISSION_MIN_SDK = Build.VERSION_CODES.TIRAMISU

/**
 * Build the ordered list of permission rows for the current OS level.
 *
 * Contacts and Calendars are always listed. Notifications is added from API 33
 * and Local network from API 37 — below those levels the OS grants the
 * capability implicitly, so a row would be a no-op. Order matches the sheet
 * design: Notifications, Contacts, Calendars, Local network.
 *
 * Each `*Granted` flag is a fresh `checkSelfPermission`-style reading taken when
 * the sheet opens (and re-read on resume); a granted reading yields the quiet
 * [PermissionTrailing.ALLOWED], anything else yields [PermissionTrailing.ALLOW]
 * so the sheet never rests on a dead end.
 */
fun buildAppPermissionRows(
    sdkInt: Int,
    notificationsGranted: Boolean,
    contactsGranted: Boolean,
    calendarsGranted: Boolean,
    localNetworkGranted: Boolean,
): List<AppPermissionRow> = buildList {
    if (sdkInt >= NOTIFICATIONS_PERMISSION_MIN_SDK) {
        add(
            AppPermissionRow(
                kind = AppPermissionKind.NOTIFICATIONS,
                nameRes = R.string.permission_name_notifications,
                whyRes = R.string.permission_why_notifications,
                trailing = trailingFor(notificationsGranted),
            ),
        )
    }
    add(
        AppPermissionRow(
            kind = AppPermissionKind.CONTACTS,
            nameRes = R.string.permission_name_contacts,
            whyRes = R.string.permission_why_contacts,
            trailing = trailingFor(contactsGranted),
        ),
    )
    add(
        AppPermissionRow(
            kind = AppPermissionKind.CALENDARS,
            nameRes = R.string.permission_name_calendars,
            whyRes = R.string.permission_why_calendars,
            trailing = trailingFor(calendarsGranted),
        ),
    )
    if (sdkInt >= LOCAL_NETWORK_PERMISSION_MIN_SDK) {
        add(
            AppPermissionRow(
                kind = AppPermissionKind.LOCAL_NETWORK,
                nameRes = R.string.permission_name_local_network,
                whyRes = R.string.permission_why_local_network,
                trailing = trailingFor(localNetworkGranted),
            ),
        )
    }
}

private fun trailingFor(granted: Boolean): PermissionTrailing =
    if (granted) PermissionTrailing.ALLOWED else PermissionTrailing.ALLOW

/**
 * Whether, after firing the runtime request for a not-granted row, the sheet
 * should fall back to opening system settings instead of leaving a dead Allow
 * button.
 *
 * A request that was denied with no rationale afterwards is "don't ask again":
 * a further in-app request can't surface a dialog, so the only way to grant is
 * via system settings. A grant (or a denial that can still be re-asked) needs
 * no fallback. Keyed on the post-request rationale signal
 * ([androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale]
 * read after the result).
 */
fun allowRequestNeedsSettingsFallback(granted: Boolean, rationaleAfter: Boolean): Boolean =
    !granted && !rationaleAfter
