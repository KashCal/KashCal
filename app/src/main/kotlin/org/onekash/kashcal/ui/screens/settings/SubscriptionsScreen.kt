package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.RevealedRow
import org.onekash.kashcal.ui.components.SettingsTopAppBar
import org.onekash.kashcal.ui.permission.LocalNetworkPermissionState
import org.onekash.kashcal.ui.shared.EventColorPalette
import org.onekash.kashcal.ui.theme.KashCalTheme

/**
 * Shows the ICS subscriptions detail screen.
 *
 * Each subscription is a [SwipeableSubscriptionItem]: a swipe reveals Delete, a tap edits in
 * [EditSubscriptionDialog]. One row is open at a time ([RevealedRow]). Scrolling closes it. While
 * one is open, a tap on a row, its switch or its refresh button only closes it; the Delete button
 * and the add buttons close it and then act. While there are feeds, a line above the list names the
 * swipe. Below the list, buttons open [HolidayCatalogPicker], the "Add from account" sheet and
 * [AddSubscriptionDialog].
 *
 * @param onAddSubscription called with (url, name, color) from either add path; a holiday feed
 *   gets a random palette color.
 * @param onUpdateSubscription called with (id, name, color, syncIntervalHours).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    subscriptions: List<IcsSubscriptionUiModel>,
    onNavigateBack: () -> Unit,
    onAddSubscription: (url: String, name: String, color: Int) -> Unit,
    onToggleSubscription: (Long, Boolean) -> Unit,
    onDeleteSubscription: (Long) -> Unit,
    onRefreshSubscription: (Long) -> Unit,
    onUpdateSubscription: (Long, String, Int, Int) -> Unit,
    // Android 17+ local-network permission wiring for the add-subscription dialog. Defaulted
    // so previews and pre-37 hosts render nothing.
    localNetworkPermissionState: LocalNetworkPermissionState =
        LocalNetworkPermissionState.NotRequired,
    onRequestLocalNetwork: () -> Unit = {},
    onSubscriptionDialogOpened: () -> Unit = {},
    // "Add from account": shown while a feed account exists; the sheet's state is the
    // ViewModel's, null while closed. Defaulted so previews and other hosts render nothing.
    showAddFromAccount: Boolean = false,
    onOpenAccountFeeds: () -> Unit = {},
    accountFeedsSheet: AccountFeedsSheetState? = null,
    onAccountFeedSwitch: (accountId: Long, url: String, on: Boolean) -> Unit = { _, _, _ -> },
    onRetryAccountFeeds: (accountId: Long) -> Unit = {},
    onCloseAccountFeeds: () -> Unit = {},
) {
    // rememberSaveable keeps the open dialog across configuration changes.
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showHolidayCatalog by rememberSaveable { mutableStateOf(false) }
    var editingSubscriptionId by rememberSaveable { mutableLongStateOf(-1L) }

    val revealed = remember { RevealedRow() }
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { if (it) revealed.close() }
    }

    // Only the id is saveable; the subscription is looked up from it.
    val editingSubscription = remember(editingSubscriptionId, subscriptions) {
        if (editingSubscriptionId > 0) subscriptions.find { it.id == editingSubscriptionId } else null
    }

    Scaffold(
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.settings_title),
                onNavigateBack = onNavigateBack,
                backContentDescription = stringResource(R.string.subscriptions_cd_back),
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding() + 16.dp
            )
        ) {
            item(key = "page_heading", contentType = "page_heading") {
                NestedSettingsHeading(text = stringResource(R.string.subscriptions_title))
            }

            if (subscriptions.isEmpty()) {
                item(key = "ics_empty", contentType = "empty_state") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 16.dp)
                    ) {
                        Text(
                            stringResource(R.string.subscriptions_empty_ics),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.subscriptions_empty_ics_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                item(key = "swipe_hint", contentType = "hint") {
                    Text(
                        stringResource(R.string.subscriptions_swipe_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                    )
                }
                items(
                    items = subscriptions,
                    key = { it.id ?: 0L },
                    contentType = { "subscription_item" }
                ) { subscription ->
                    Column(modifier = Modifier.animateItem()) {
                        SwipeableSubscriptionItem(
                            subscription = subscription,
                            onToggle = { id, on -> revealed.closeOr { onToggleSubscription(id, on) } },
                            onDelete = { id ->
                                revealed.close()
                                onDeleteSubscription(id)
                            },
                            onRefresh = { id -> revealed.closeOr { onRefreshSubscription(id) } },
                            onEdit = { revealed.closeOr { editingSubscriptionId = it.id ?: -1L } },
                            open = revealed.openId == subscription.id,
                            onOpenChange = { isOpen -> subscription.id?.let { revealed.set(it, isOpen) } },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 32.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            item(key = "add_header", contentType = "section_header") {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader(stringResource(R.string.subscriptions_section_add))
            }

            item(key = "holiday_catalog_button", contentType = "add_button") {
                AddSourceButton(
                    stringResource(R.string.holiday_catalog_add),
                    onClick = {
                        revealed.close()
                        showHolidayCatalog = true
                    },
                )
            }

            if (showAddFromAccount) {
                item(key = "add_from_account_button", contentType = "add_button") {
                    AddSourceButton(
                        stringResource(R.string.account_feeds_add),
                        onClick = {
                            revealed.close()
                            onOpenAccountFeeds()
                        },
                    )
                }
            }

            item(key = "add_button", contentType = "add_button") {
                AddSourceButton(
                    stringResource(R.string.subscriptions_add_ics),
                    onClick = {
                        revealed.close()
                        showAddDialog = true
                    },
                    iconDescription = stringResource(R.string.subscriptions_cd_add),
                )
            }
        }
    }

    if (showHolidayCatalog) {
        val subscribedUrls = remember(subscriptions) {
            subscriptions.mapTo(HashSet()) { it.url }
        }
        HolidayCatalogPicker(
            subscribedUrls = subscribedUrls,
            onPick = { url, name ->
                onAddSubscription(url, name, EventColorPalette.randomArgb())
                showHolidayCatalog = false
            },
            onDismiss = { showHolidayCatalog = false }
        )
    }

    if (accountFeedsSheet != null) {
        AccountFeedsSheet(
            state = accountFeedsSheet,
            onSwitch = onAccountFeedSwitch,
            onRetry = onRetryAccountFeeds,
            onDismiss = onCloseAccountFeeds,
        )
    }

    if (showAddDialog) {
        AddSubscriptionDialog(
            initialUrl = null,
            onDismiss = { showAddDialog = false },
            onAdd = { url, name, color ->
                onAddSubscription(url, name, color)
                showAddDialog = false
            },
            localNetworkPermissionState = localNetworkPermissionState,
            onRequestLocalNetwork = onRequestLocalNetwork,
            onDialogOpened = onSubscriptionDialogOpened,
        )
    }

    editingSubscription?.let { subscription ->
        EditSubscriptionDialog(
            subscription = subscription,
            onDismiss = { editingSubscriptionId = -1L },
            onSave = { name, color, syncInterval ->
                subscription.id?.let { id ->
                    onUpdateSubscription(id, name, color, syncInterval)
                }
                editingSubscriptionId = -1L
            }
        )
    }
}

// ==================== Previews ====================

/** One of Calendar Feeds' add buttons: an outlined, full-width button with a leading plus. */
@Composable
internal fun AddSourceButton(label: String, onClick: () -> Unit, iconDescription: String? = null) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = iconDescription,
            modifier = Modifier.size(18.dp)
        )
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun SubscriptionsScreenPreview() {
    val sampleSubscriptions = listOf(
        IcsSubscriptionUiModel(
            id = 1,
            url = "https://example.com/holidays.ics",
            name = "US Holidays",
            color = 0xFF2196F3.toInt()
        ),
        IcsSubscriptionUiModel(
            id = 2,
            url = "https://example.com/sports.ics",
            name = "Sports Calendar",
            color = 0xFF4CAF50.toInt()
        )
    )
    KashCalTheme {
        SubscriptionsScreen(
            subscriptions = sampleSubscriptions,
            onNavigateBack = {},
            onAddSubscription = { _, _, _ -> },
            onToggleSubscription = { _, _ -> },
            onDeleteSubscription = {},
            onRefreshSubscription = {},
            onUpdateSubscription = { _, _, _, _ -> }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SubscriptionsScreenEmptyPreview() {
    KashCalTheme {
        SubscriptionsScreen(
            subscriptions = emptyList(),
            onNavigateBack = {},
            onAddSubscription = { _, _, _ -> },
            onToggleSubscription = { _, _ -> },
            onDeleteSubscription = {},
            onRefreshSubscription = {},
            onUpdateSubscription = { _, _, _, _ -> }
        )
    }
}
