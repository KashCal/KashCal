package org.onekash.kashcal.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.ui.shared.maskEmail
import org.onekash.kashcal.ui.theme.KashCalTheme

/**
 * Sub-sheet types for the account detail flow.
 * Sequential pattern: only one sheet visible at a time.
 */
private enum class SubSheet { NONE, RENAME, CHANGE_PASSWORD, SIGN_OUT }

/** Constants for sync warning indicator on account rows. */
internal object SyncWarningConstants {
    /** Minimum consecutive sync failures before showing warning indicator. */
    const val SYNC_FAILURE_THRESHOLD = 3
    /** Time window (24 hours in ms) — beyond this, subtitle changes to "Sync issue". */
    const val SYNC_ISSUE_SUBTITLE_THRESHOLD_MS = 24 * 60 * 60 * 1000L
}

/**
 * Accounts detail screen.
 *
 * Dedicated screen for managing connected accounts (iCloud and CalDAV).
 * Features:
 * - List of connected accounts with calendar counts
 * - Tap account to open AccountDetailSheet for management
 * - Add iCloud and Add CalDAV buttons
 * - Empty state when no accounts connected
 *
 * Follows SubscriptionsScreen navigation pattern.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    iCloudAccount: ICloudAccountUiModel?,
    showAddICloud: Boolean,
    calDavAccounts: List<CalDavAccountUiModel>,
    onNavigateBack: () -> Unit,
    onAddICloud: () -> Unit,
    onICloudSignOut: () -> Unit,
    onAddCalDav: () -> Unit,
    onCalDavSignOut: (Long) -> Unit,
    // Account detail state + callbacks
    accountDetail: AccountDetailUiModel? = null,
    accountDetailSyncStatus: AccountDetailSyncStatus = AccountDetailSyncStatus.Idle,
    accountDetailDiscoverStatus: AccountDetailDiscoverStatus = AccountDetailDiscoverStatus.Idle,
    onObserveAccountDetail: (Long) -> Unit = {},
    onClearAccountDetail: () -> Unit = {},
    onSyncAccountNow: (Long) -> Unit = {},
    onToggleAccountEnabled: (Long, Boolean) -> Unit = { _, _ -> },
    onRenameAccount: (Long, String) -> Unit = { _, _ -> },
    onChangeAccountPassword: (Long, String, (Result<Unit>) -> Unit) -> Unit = { _, _, _ -> },
    onDiscoverCalendars: (Long) -> Unit = {}
) {
    // Selected account and sub-sheet state (survives config change)
    var selectedAccountId by rememberSaveable { mutableStateOf<Long?>(null) }
    var activeSubSheet by rememberSaveable { mutableStateOf(SubSheet.NONE) }

    // Password change state
    var isPasswordValidating by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }

    // Sheet states
    val detailSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val subSheetState = rememberModalBottomSheetState()

    val hasAccounts = iCloudAccount != null || calDavAccounts.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.accounts_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.accounts_cd_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding() + 24.dp
            )
        ) {
            if (hasAccounts) {
                // Connected section header
                item {
                    SectionHeader(stringResource(R.string.accounts_section_connected))
                }

                // iCloud account (if connected)
                iCloudAccount?.let { account ->
                    item {
                        AccountRow(
                            icon = Icons.Default.Cloud,
                            iconContentDescription = stringResource(R.string.accounts_cd_icloud_icon),
                            providerName = stringResource(R.string.provider_icloud),
                            email = maskEmail(account.email),
                            calendarCount = account.calendarCount,
                            consecutiveSyncFailures = account.consecutiveSyncFailures,
                            lastSuccessfulSyncAt = account.lastSuccessfulSyncAt,
                            onClick = {
                                selectedAccountId = account.accountId
                                onObserveAccountDetail(account.accountId)
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                // CalDAV accounts
                items(
                    items = calDavAccounts,
                    key = { it.id }
                ) { account ->
                    AccountRow(
                        icon = Icons.Default.CalendarMonth,
                        iconContentDescription = stringResource(R.string.accounts_cd_caldav_icon),
                        providerName = account.displayName,
                        email = maskEmail(account.email),
                        calendarCount = account.calendarCount,
                        consecutiveSyncFailures = account.consecutiveSyncFailures,
                        lastSuccessfulSyncAt = account.lastSuccessfulSyncAt,
                        onClick = {
                            selectedAccountId = account.id
                            onObserveAccountDetail(account.id)
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }

                // Add Account section
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    SectionHeader(stringResource(R.string.accounts_section_add))
                }

                item {
                    AddAccountButtons(
                        showAddICloud = showAddICloud,
                        onAddICloud = onAddICloud,
                        onAddCalDav = onAddCalDav,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            } else {
                // Empty state
                item {
                    EmptyState(
                        onAddICloud = onAddICloud,
                        onAddCalDav = onAddCalDav,
                        modifier = Modifier.fillParentMaxSize()
                    )
                }
            }
        }
    }

    // Account detail sheet
    if (selectedAccountId != null && activeSubSheet == SubSheet.NONE && accountDetail != null) {
        AccountDetailSheet(
            sheetState = detailSheetState,
            account = accountDetail,
            syncStatus = accountDetailSyncStatus,
            discoverStatus = accountDetailDiscoverStatus,
            onRename = { activeSubSheet = SubSheet.RENAME },
            onSyncNow = { onSyncAccountNow(accountDetail.accountId) },
            onToggleEnabled = { enabled -> onToggleAccountEnabled(accountDetail.accountId, enabled) },
            onDiscoverCalendars = { onDiscoverCalendars(accountDetail.accountId) },
            onChangePassword = {
                passwordError = null
                activeSubSheet = SubSheet.CHANGE_PASSWORD
            },
            onSignOut = { activeSubSheet = SubSheet.SIGN_OUT },
            onDismiss = {
                selectedAccountId = null
                onClearAccountDetail()
            }
        )
    }

    // Rename sub-sheet
    if (selectedAccountId != null && activeSubSheet == SubSheet.RENAME && accountDetail != null) {
        RenameAccountSheet(
            sheetState = subSheetState,
            currentName = accountDetail.displayName,
            onSave = { newName ->
                onRenameAccount(accountDetail.accountId, newName)
            },
            onDismiss = {
                activeSubSheet = SubSheet.NONE
                // Reload detail with fresh data
                selectedAccountId?.let { onObserveAccountDetail(it) }
            }
        )
    }

    // Change password sub-sheet
    if (selectedAccountId != null && activeSubSheet == SubSheet.CHANGE_PASSWORD && accountDetail != null) {
        ChangePasswordSheet(
            sheetState = subSheetState,
            provider = accountDetail.provider,
            isValidating = isPasswordValidating,
            error = passwordError,
            onSave = { newPassword ->
                isPasswordValidating = true
                passwordError = null
                onChangeAccountPassword(accountDetail.accountId, newPassword) { result ->
                    isPasswordValidating = false
                    result.fold(
                        onSuccess = {
                            activeSubSheet = SubSheet.NONE
                            selectedAccountId?.let { onObserveAccountDetail(it) }
                        },
                        onFailure = { error ->
                            passwordError = error.message
                        }
                    )
                }
            },
            onDismiss = {
                if (!isPasswordValidating) {
                    activeSubSheet = SubSheet.NONE
                    passwordError = null
                    selectedAccountId?.let { onObserveAccountDetail(it) }
                }
            }
        )
    }

    // Sign out confirmation sub-sheet
    if (selectedAccountId != null && activeSubSheet == SubSheet.SIGN_OUT && accountDetail != null) {
        GenericSignOutConfirmationSheet(
            sheetState = subSheetState,
            providerName = accountDetail.displayName,
            email = accountDetail.email,
            onConfirm = {
                when (accountDetail.provider) {
                    AccountProvider.ICLOUD -> onICloudSignOut()
                    AccountProvider.CALDAV -> onCalDavSignOut(accountDetail.accountId)
                    else -> {}
                }
                selectedAccountId = null
                onClearAccountDetail()
            },
            onDismiss = {
                activeSubSheet = SubSheet.NONE
            }
        )
    }
}

/**
 * Row displaying a connected account with provider icon, name, email, and calendar count.
 */
@Composable
private fun AccountRow(
    icon: ImageVector,
    iconContentDescription: String,
    providerName: String,
    email: String,
    calendarCount: Int,
    consecutiveSyncFailures: Int = 0,
    lastSuccessfulSyncAt: Long? = null,
    onClick: () -> Unit
) {
    val hasSyncWarning = consecutiveSyncFailures >= SyncWarningConstants.SYNC_FAILURE_THRESHOLD

    val showSyncIssueSubtitle = hasSyncWarning && (
        lastSuccessfulSyncAt == null ||
        lastSuccessfulSyncAt <= 0 ||
        System.currentTimeMillis() - lastSuccessfulSyncAt > SyncWarningConstants.SYNC_ISSUE_SUBTITLE_THRESHOLD_MS
    )

    val subtitle = if (showSyncIssueSubtitle) {
        stringResource(R.string.accounts_sync_issue)
    } else if (calendarCount == 0) {
        stringResource(R.string.accounts_calendars_syncing)
    } else if (calendarCount == 1) {
        stringResource(R.string.accounts_calendar_count_one, email)
    } else {
        stringResource(R.string.accounts_calendar_count_other, email, calendarCount)
    }

    val syncWarningDesc = if (hasSyncWarning) {
        ", " + stringResource(R.string.accounts_cd_sync_warning)
    } else ""
    val semanticsDescription = "$providerName account, $email, $calendarCount calendars$syncWarningDesc"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .semantics { contentDescription = semanticsDescription },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = iconContentDescription,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Column {
                Text(
                    providerName,
                    style = MaterialTheme.typography.bodyLarge
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasSyncWarning) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = stringResource(R.string.accounts_cd_sync_warning),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (showSyncIssueSubtitle) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Add account buttons - iCloud (filled) and CalDAV (outlined).
 *
 * @param showAddICloud Whether to show the Add iCloud button (false when already connected)
 */
@Composable
private fun AddAccountButtons(
    showAddICloud: Boolean = true,
    onAddICloud: () -> Unit,
    onAddCalDav: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showCalDavHelp by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Add iCloud button (filled/primary) - only show if not connected
        if (showAddICloud) {
            Button(
                onClick = onAddICloud,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    stringResource(R.string.accounts_add_icloud),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        // Add CalDAV button (outlined) with subtitle
        OutlinedButton(
            onClick = onAddCalDav,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        stringResource(R.string.accounts_add_caldav),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Text(
                    stringResource(R.string.accounts_add_caldav_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // What is CalDAV? help toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showCalDavHelp = !showCalDavHelp }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                stringResource(R.string.accounts_caldav_help_title),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Expandable CalDAV help
        AnimatedVisibility(
            visible = showCalDavHelp,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        stringResource(R.string.accounts_caldav_help_description),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("• Nextcloud", style = MaterialTheme.typography.bodySmall)
                    Text("• FastMail", style = MaterialTheme.typography.bodySmall)
                    Text("• Baïkal", style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(R.string.accounts_caldav_help_more),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Empty state shown when no accounts are connected.
 */
@Composable
private fun EmptyState(
    onAddICloud: () -> Unit,
    onAddCalDav: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            stringResource(R.string.accounts_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            stringResource(R.string.accounts_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        AddAccountButtons(
            onAddICloud = onAddICloud,
            onAddCalDav = onAddCalDav,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

// ==================== Previews ====================

@Preview(showBackground = true)
@Composable
private fun AccountsScreenPreview() {
    KashCalTheme {
        AccountsScreen(
            iCloudAccount = ICloudAccountUiModel(
                accountId = 1L,
                email = "john@icloud.com",
                calendarCount = 5
            ),
            showAddICloud = false,
            calDavAccounts = listOf(
                CalDavAccountUiModel(
                    id = 2,
                    email = "user@nextcloud.example.com",
                    displayName = "Nextcloud",
                    calendarCount = 3
                ),
                CalDavAccountUiModel(
                    id = 3,
                    email = "me@fastmail.com",
                    displayName = "FastMail",
                    calendarCount = 2
                )
            ),
            onNavigateBack = {},
            onAddICloud = {},
            onICloudSignOut = {},
            onAddCalDav = {},
            onCalDavSignOut = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AccountsScreenEmptyPreview() {
    KashCalTheme {
        AccountsScreen(
            iCloudAccount = null,
            showAddICloud = true,
            calDavAccounts = emptyList(),
            onNavigateBack = {},
            onAddICloud = {},
            onICloudSignOut = {},
            onAddCalDav = {},
            onCalDavSignOut = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AccountsScreenICloudOnlyPreview() {
    KashCalTheme {
        AccountsScreen(
            iCloudAccount = ICloudAccountUiModel(
                accountId = 1L,
                email = "jane@icloud.com",
                calendarCount = 0  // Syncing state
            ),
            showAddICloud = false,
            calDavAccounts = emptyList(),
            onNavigateBack = {},
            onAddICloud = {},
            onICloudSignOut = {},
            onAddCalDav = {},
            onCalDavSignOut = {}
        )
    }
}

@Preview(showBackground = true, name = "Same Display Name - Two Accounts")
@Composable
private fun AccountsScreenSameDisplayNamePreview() {
    KashCalTheme {
        AccountsScreen(
            iCloudAccount = null,
            showAddICloud = true,
            calDavAccounts = listOf(
                CalDavAccountUiModel(
                    id = 1,
                    email = "testuser",
                    displayName = "My CalDAV Server",
                    calendarCount = 1
                ),
                CalDavAccountUiModel(
                    id = 2,
                    email = "testuser2",
                    displayName = "Work CalDAV",
                    calendarCount = 2
                )
            ),
            onNavigateBack = {},
            onAddICloud = {},
            onICloudSignOut = {},
            onAddCalDav = {},
            onCalDavSignOut = {}
        )
    }
}

@Preview(showBackground = true, name = "Sync Warning State")
@Composable
private fun AccountsScreenSyncWarningPreview() {
    KashCalTheme {
        AccountsScreen(
            iCloudAccount = ICloudAccountUiModel(
                accountId = 1L,
                email = "john@icloud.com",
                calendarCount = 5,
                consecutiveSyncFailures = 5,
                lastSuccessfulSyncAt = System.currentTimeMillis() - 48 * 60 * 60 * 1000L
            ),
            showAddICloud = false,
            calDavAccounts = listOf(
                CalDavAccountUiModel(
                    id = 2,
                    email = "user@nextcloud.example.com",
                    displayName = "Nextcloud",
                    calendarCount = 3,
                    consecutiveSyncFailures = 3,
                    lastSuccessfulSyncAt = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
                ),
                CalDavAccountUiModel(
                    id = 3,
                    email = "me@fastmail.com",
                    displayName = "FastMail",
                    calendarCount = 2,
                    consecutiveSyncFailures = 4,
                    lastSuccessfulSyncAt = null
                )
            ),
            onNavigateBack = {},
            onAddICloud = {},
            onICloudSignOut = {},
            onAddCalDav = {},
            onCalDavSignOut = {}
        )
    }
}
