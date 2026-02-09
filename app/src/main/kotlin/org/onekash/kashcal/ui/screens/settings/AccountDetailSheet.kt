package org.onekash.kashcal.ui.screens.settings

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Account detail bottom sheet — unified layout for all providers.
 *
 * Sections:
 * 1. Header (provider icon, display name, email)
 * 2. Sync (status, enabled toggle, sync now)
 * 3. Calendars (count, discover new)
 * 4. Account (change password, sign out)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailSheet(
    sheetState: SheetState,
    account: AccountDetailUiModel,
    syncStatus: AccountDetailSyncStatus,
    discoverStatus: AccountDetailDiscoverStatus,
    onRename: () -> Unit,
    onSyncNow: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDiscoverCalendars: () -> Unit,
    onChangePassword: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // ==================== Header ====================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onRename)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Provider icon
                Icon(
                    imageVector = when (account.provider) {
                        AccountProvider.ICLOUD -> Icons.Default.Cloud
                        else -> Icons.Default.CalendarMonth
                    },
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.displayName,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = account.email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.account_detail_cd_rename),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Divider after header
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // ==================== Sync ====================
            SectionHeader(stringResource(R.string.account_detail_section_sync))
            SettingsCard {
                // Sync status row (hidden when healthy + idle)
                SyncStatusRow(
                    lastSuccessfulSyncAt = account.lastSuccessfulSyncAt,
                    consecutiveSyncFailures = account.consecutiveSyncFailures,
                    syncStatus = syncStatus
                )

                // Enabled toggle
                SettingsToggleRow(
                    label = stringResource(R.string.account_detail_enabled),
                    subtitle = stringResource(R.string.account_detail_enabled_subtitle),
                    checked = account.isEnabled,
                    onCheckedChange = { onToggleEnabled(it) }
                )

                // Sync Now — primary text + sync icon / spinner
                val isSyncing = syncStatus is AccountDetailSyncStatus.Syncing
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isSyncing) { onSyncNow() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.account_detail_sync_now),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // ==================== Calendars ====================
            SectionHeader(stringResource(R.string.account_detail_section_calendars))
            SettingsCard {
                // Calendar count + discover status
                val calendarText = when (discoverStatus) {
                    is AccountDetailDiscoverStatus.Discovering ->
                        stringResource(R.string.account_detail_discovering)
                    is AccountDetailDiscoverStatus.Done -> {
                        if (discoverStatus.newCount > 0) {
                            stringResource(R.string.account_detail_calendar_count_new, discoverStatus.totalCount, discoverStatus.newCount)
                        } else {
                            formatCalendarCount(account.calendarCount)
                        }
                    }
                    is AccountDetailDiscoverStatus.Error -> formatCalendarCount(account.calendarCount)
                    is AccountDetailDiscoverStatus.Idle -> formatCalendarCount(account.calendarCount)
                }

                SettingsRow(
                    label = calendarText,
                    onClick = {},
                    showChevron = false
                )

                if (discoverStatus is AccountDetailDiscoverStatus.Error) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = discoverStatus.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Discover — primary text + search icon / spinner
                val isDiscovering = discoverStatus is AccountDetailDiscoverStatus.Discovering
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isDiscovering) { onDiscoverCalendars() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.account_detail_discover_calendars),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (isDiscovering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // ==================== Account ====================
            SectionHeader(stringResource(R.string.account_detail_section_account))
            SettingsCard {
                SettingsRow(
                    label = stringResource(R.string.account_detail_change_password),
                    onClick = onChangePassword,
                    showDivider = true
                )

                // Sign Out — red outlined button
                OutlinedButton(
                    onClick = onSignOut,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.account_detail_sign_out))
                }
            }
        }
    }
}

/**
 * Format calendar count with correct singular/plural.
 */
@Composable
private fun formatCalendarCount(count: Int): String {
    return if (count == 1) {
        stringResource(R.string.account_detail_calendar_count_one)
    } else {
        stringResource(R.string.account_detail_calendar_count_other, count)
    }
}

/**
 * Sync status display row within the SYNC section.
 *
 * Three states:
 * - Healthy + idle (0 failures, not syncing): renders nothing
 * - Syncing: spinner + "Syncing..." text
 * - Failure (>0 failures): warning + failure count + last sync context
 */
@Composable
private fun SyncStatusRow(
    lastSuccessfulSyncAt: Long?,
    consecutiveSyncFailures: Int,
    syncStatus: AccountDetailSyncStatus
) {
    // Healthy + idle: render nothing
    if (consecutiveSyncFailures == 0 && syncStatus !is AccountDetailSyncStatus.Syncing) {
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        when {
            syncStatus is AccountDetailSyncStatus.Syncing -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = stringResource(R.string.account_detail_syncing),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            consecutiveSyncFailures > 0 -> {
                // Failure warning
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (consecutiveSyncFailures == 1) {
                            stringResource(R.string.account_detail_failure_one, consecutiveSyncFailures)
                        } else {
                            stringResource(R.string.account_detail_failure_other, consecutiveSyncFailures)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // Last sync context
                val syncTimeText = if (lastSuccessfulSyncAt != null && lastSuccessfulSyncAt > 0) {
                    val relativeTime = DateUtils.getRelativeTimeSpanString(
                        lastSuccessfulSyncAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE
                    ).toString()
                    stringResource(R.string.account_detail_last_success, relativeTime)
                } else {
                    stringResource(R.string.account_detail_never_synced)
                }

                Text(
                    text = syncTimeText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
