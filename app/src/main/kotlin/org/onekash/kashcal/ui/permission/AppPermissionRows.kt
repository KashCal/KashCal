package org.onekash.kashcal.ui.permission

import android.os.Build
import androidx.annotation.StringRes
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.permission.LocalNetworkPermissionManager.Companion.LOCAL_NETWORK_PERMISSION_MIN_SDK

/**
 * Lists the runtime permissions the app-permissions screen can show, in screen order.
 *
 * Contacts and Calendars are runtime permissions on every supported OS level; Notifications
 * and Local network only from later levels ([buildAppPermissionRows]).
 */
enum class AppPermissionKind {
    NOTIFICATIONS,
    CONTACTS,
    CALENDARS,
    LOCAL_NETWORK,
}

/**
 * Names a row's trailing affordance.
 *
 * A granted permission shows [ALLOWED] (quiet; tapping the row opens system settings); anything
 * else shows [ALLOW], an accent button that fires the runtime request. There is deliberately no
 * permanently-denied value: a steady-state read can't tell never-asked from permanently denied,
 * so the screen always offers Allow and opens settings only when the fired request comes back
 * as [allowRequestNeedsSettingsFallback] describes.
 */
enum class PermissionTrailing {
    ALLOWED,
    ALLOW,
}

/** Holds one row of the app-permissions screen: permission, label, tooltip and trailing. */
data class AppPermissionRow(
    val kind: AppPermissionKind,
    @StringRes val nameRes: Int,
    @StringRes val whyRes: Int,
    val trailing: PermissionTrailing,
)

/** API level at which POST_NOTIFICATIONS became a runtime permission (Android 13). */
private const val NOTIFICATIONS_PERMISSION_MIN_SDK = Build.VERSION_CODES.TIRAMISU

/**
 * Builds the permission rows for [sdkInt], in the order Notifications, Contacts, Calendars,
 * Local network.
 *
 * Contacts and Calendars are always listed. Notifications is added from API 33 and Local network
 * from API 37; below those levels the OS grants the capability implicitly, so a row would do
 * nothing.
 *
 * Each `*Granted` flag is a fresh `checkSelfPermission` reading, taken when the screen opens, on
 * resume and after each request. Granted gives [PermissionTrailing.ALLOWED], anything else
 * [PermissionTrailing.ALLOW], so no row is a dead end.
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
 * Returns whether, after a not-granted row's request returns, the screen opens system settings
 * instead of leaving a dead Allow button.
 *
 * A denial with no rationale afterwards
 * ([androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale] read after the result)
 * is "don't ask again": a further request can't show a dialog, so only system settings can
 * grant. A grant, or a denial that can still be re-asked, needs no fallback.
 */
fun allowRequestNeedsSettingsFallback(granted: Boolean, rationaleAfter: Boolean): Boolean =
    !granted && !rationaleAfter
