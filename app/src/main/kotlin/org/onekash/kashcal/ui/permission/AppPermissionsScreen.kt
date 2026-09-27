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
 * Shows every runtime permission the app uses, each with a one-tap grant, as a full-screen
 * destination.
 *
 * Opened from the account hub's Privacy & security section and drawn as an opaque overlay above
 * the hub. Its top bar's back arrow and the system back gesture both call [onBack].
 *
 * It owns the permission launchers and the live grant reads, which need an Activity, and
 * re-reads every row on resume and after each request, so a grant or revoke made in system
 * settings shows when the user returns. The body is [AppPermissionsScreenContent].
 *
 * @param onOpenPermissionSettings opens the system settings page for a permission kind: on a
 *   tap on a granted row, and when a fired request comes back permanently denied, so Allow is
 *   never a dead end. The host opens notification settings for Notifications and the app info
 *   page for the rest, through its internal-activity launch so app lock doesn't re-lock on
 *   return.
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

    // Live grant readings, recomputed on resume and after every request result.
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

    // A request that comes back permanently denied opens that permission's system settings
    // ([allowRequestNeedsSettingsFallback]).
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

    // Calendars requests READ and WRITE; the row's granted signal keys on READ only.
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
                // Reached from the account hub, where a "jump home to today" logo is out of
                // place.
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
 * Draws the app-permissions screen body, one row per permission.
 *
 * A granted row shows the quiet "Allowed" state and a tap calls [onOpenPermissionSettings]; a
 * not-granted row offers the accent "Allow" button, which calls [onAllow]. Split out of
 * [AppPermissionsScreen] so `AppPermissionsScreenTest` renders it from a fixed row list with no
 * Activity or Hilt graph.
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
    // A granted row opens system settings to review the grant; on a not-granted row the Allow
    // button owns the tap, so the row body is inert.
    val rowModifier = if (row.trailing == PermissionTrailing.ALLOWED) {
        Modifier.clickable(role = Role.Button, onClick = onOpenSettings)
    } else {
        Modifier
    }
    Row(
        modifier = rowModifier
            .fillMaxWidth()
            // A 64dp minimum height and generous vertical padding keep the rows apart.
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
        // The info button keeps its full 48dp touch target on this taller row, not the compact
        // settings-row variant.
        SettingsInfoButton(SettingsRowInfo(title = name, text = why), compact = false)
        Spacer(Modifier.width(8.dp))
        when (row.trailing) {
            PermissionTrailing.ALLOWED -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                // Matches the Allow button's 48dp target so granted and not-granted rows sit at
                // the same height.
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
