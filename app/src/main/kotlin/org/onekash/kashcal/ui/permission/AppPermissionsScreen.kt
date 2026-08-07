package org.onekash.kashcal.ui.permission

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.SettingsTopAppBar
import org.onekash.kashcal.ui.permission.LocalNetworkPermissionManager.Companion.LOCAL_NETWORK_PERMISSION_MIN_SDK
import org.onekash.kashcal.ui.screens.settings.SettingsInfoButton
import org.onekash.kashcal.ui.screens.settings.SettingsRowInfo

/**
 * Full-screen destination that lists every runtime permission the app uses,
 * with a one-tap grant. Opened from the account hub's Privacy & security
 * section and rendered as an opaque overlay above the hub, mirroring the way
 * Manage tags opens over the calendar. Its own top bar with a back arrow
 * dismisses it through the same [onBack] path as the system back gesture.
 *
 * Self-contained like the hub's personalization section: it owns the permission
 * launchers and the live grant reads (which need an Activity), and re-resolves
 * every row on resume so a grant or revoke performed in system settings during a
 * deep-link round trip is reflected when the user returns. The renderable body
 * is hoisted into [AppPermissionsScreenContent] so it can be unit-tested with a
 * fixed row list and no Activity or Hilt graph.
 *
 * @param onOpenPermissionSettings deep-link to the system settings page for a
 *   given permission kind. Used both when tapping an already-granted row and as
 *   the escape hatch when a fired request turns out to be permanently denied (so
 *   Allow is never a dead end). Notifications routes to its own notification
 *   settings; the rest fall back to the app info page. The host routes this
 *   through its internal-activity launch so app lock does not re-lock on return.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPermissionsScreen(
    onBack: () -> Unit,
    onOpenPermissionSettings: (AppPermissionKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val activity = LocalActivity.current
    val sdkInt = Build.VERSION.SDK_INT

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun notificationsGranted(): Boolean =
        sdkInt < Build.VERSION_CODES.TIRAMISU || isGranted(Manifest.permission.POST_NOTIFICATIONS)

    fun localNetworkGranted(): Boolean =
        sdkInt < LOCAL_NETWORK_PERMISSION_MIN_SDK || isGranted(Manifest.permission.ACCESS_LOCAL_NETWORK)

    // Live grant readings, recomputed on resume and after every request result so
    // the rows reflect the current system state rather than a stale open-time read.
    var rows by remember {
        mutableStateOf(
            buildAppPermissionRows(
                sdkInt = sdkInt,
                notificationsGranted = notificationsGranted(),
                contactsGranted = isGranted(Manifest.permission.READ_CONTACTS),
                calendarsGranted = isGranted(Manifest.permission.READ_CALENDAR),
                localNetworkGranted = localNetworkGranted(),
            ),
        )
    }
    fun refresh() {
        rows = buildAppPermissionRows(
            sdkInt = sdkInt,
            notificationsGranted = notificationsGranted(),
            contactsGranted = isGranted(Manifest.permission.READ_CONTACTS),
            calendarsGranted = isGranted(Manifest.permission.READ_CALENDAR),
            localNetworkGranted = localNetworkGranted(),
        )
    }

    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }

    // A request that comes back permanently denied ("don't ask again") can't
    // surface a dialog on a further tap, so route to that permission's system
    // settings page instead of leaving a dead Allow button. Keyed on the
    // post-request rationale signal.
    fun onResult(kind: AppPermissionKind, permission: String, granted: Boolean) {
        val rationaleAfter = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, permission)
        } ?: false
        if (allowRequestNeedsSettingsFallback(granted = granted, rationaleAfter = rationaleAfter)) {
            onOpenPermissionSettings(kind)
        }
        refresh()
    }

    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onResult(AppPermissionKind.NOTIFICATIONS, Manifest.permission.POST_NOTIFICATIONS, granted) }

    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onResult(AppPermissionKind.CONTACTS, Manifest.permission.READ_CONTACTS, granted) }

    val localNetworkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onResult(AppPermissionKind.LOCAL_NETWORK, Manifest.permission.ACCESS_LOCAL_NETWORK, granted) }

    // Calendars is multi-permission (READ + WRITE); the row's granted signal keys
    // on READ, matching the calendars-permission classifier.
    val calendarsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants -> onResult(AppPermissionKind.CALENDARS, Manifest.permission.READ_CALENDAR, grants[Manifest.permission.READ_CALENDAR] == true) }

    val onAllow: (AppPermissionKind) -> Unit = { kind ->
        when (kind) {
            AppPermissionKind.NOTIFICATIONS -> notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            AppPermissionKind.CONTACTS -> contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
            AppPermissionKind.CALENDARS -> calendarsLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            )
            AppPermissionKind.LOCAL_NETWORK -> localNetworkLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.hub_app_permissions),
                onNavigateBack = onBack,
                // Reached from the account hub, where a "jump home to today" logo
                // shortcut is off-context.
                showLogo = false,
            )
        },
    ) { padding ->
        AppPermissionsScreenContent(
            rows = rows,
            onAllow = onAllow,
            onOpenPermissionSettings = onOpenPermissionSettings,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        )
    }
}

/**
 * The renderable body of the app-permissions screen: one row per permission. A
 * granted row reads as the quiet "Allowed" state and routes taps to that
 * permission's system settings; a not-granted row offers the accent "Allow"
 * action that fires [onAllow]. Hoisted out of [AppPermissionsScreen] so it
 * renders from a fixed row list with no Activity or Hilt graph.
 */
@Composable
internal fun AppPermissionsScreenContent(
    rows: List<AppPermissionRow>,
    onAllow: (AppPermissionKind) -> Unit,
    onOpenPermissionSettings: (AppPermissionKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        rows.forEach { row ->
            AppPermissionRowItem(
                row = row,
                onAllow = { onAllow(row.kind) },
                onOpenSettings = { onOpenPermissionSettings(row.kind) },
            )
        }
    }
}

@Composable
private fun AppPermissionRowItem(
    row: AppPermissionRow,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val name = stringResource(row.nameRes)
    val why = stringResource(row.whyRes)
    // A granted row is itself the affordance to review the grant in system
    // settings; a not-granted row's Allow button owns the tap, so the row body
    // is inert there.
    val rowModifier = if (row.trailing == PermissionTrailing.ALLOWED) {
        Modifier.clickable(role = Role.Button, onClick = onOpenSettings)
    } else {
        Modifier
    }
    Row(
        modifier = rowModifier
            .fillMaxWidth()
            // A roomy 64dp minimum height with generous vertical padding so each
            // permission sits clearly apart from its neighbours.
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = iconFor(row.kind),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        // The info button keeps its full 48dp accessible touch target on this
        // taller row (not the compact settings-row variant).
        SettingsInfoButton(SettingsRowInfo(title = name, text = why), compact = false)
        Spacer(Modifier.width(8.dp))
        when (row.trailing) {
            PermissionTrailing.ALLOWED -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                // Match the Allow button's 48dp target so granted and not-granted
                // rows sit at the same height.
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.status_allowed),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PermissionTrailing.ALLOW -> Button(onClick = onAllow) {
                Text(stringResource(R.string.action_allow))
            }
        }
    }
}

private fun iconFor(kind: AppPermissionKind): ImageVector = when (kind) {
    AppPermissionKind.NOTIFICATIONS -> Icons.Default.Notifications
    AppPermissionKind.CONTACTS -> Icons.Default.People
    AppPermissionKind.CALENDARS -> Icons.Default.CalendarMonth
    AppPermissionKind.LOCAL_NETWORK -> Icons.Default.Wifi
}
