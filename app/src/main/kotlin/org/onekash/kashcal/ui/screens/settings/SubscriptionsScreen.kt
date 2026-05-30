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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.SettingsTopAppBar
import org.onekash.kashcal.ui.theme.KashCalTheme

/**
 * Subscriptions detail screen.
 *
 * Dedicated screen for managing ICS calendar subscriptions.
 * Features:
 * - LazyColumn with swipeable ICS subscription items
 * - Swipe left to delete
 * - Tap to edit
 * - Add button at bottom
 *
 * @param subscriptions List of current ICS subscriptions
 * @param onNavigateBack Callback to navigate back
 * @param onAddSubscription Callback when adding new ICS subscription
 * @param onToggleSubscription Callback when ICS subscription enabled/disabled
 * @param onDeleteSubscription Callback when ICS subscription deleted
 * @param onRefreshSubscription Callback when ICS subscription refreshed
 * @param onUpdateSubscription Callback when ICS subscription updated
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
    onUpdateSubscription: (Long, String, Int, Int) -> Unit
) {
    // State for dialogs (rememberSaveable survives config changes)
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editingSubscriptionId by rememberSaveable { mutableLongStateOf(-1L) }

    // Derive editingSubscription from ID (complex objects can't be saved directly)
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
                items(
                    items = subscriptions,
                    key = { it.id ?: 0L },
                    contentType = { "subscription_item" }
                ) { subscription ->
                    Column(modifier = Modifier.animateItem()) {
                        SwipeableSubscriptionItem(
                            subscription = subscription,
                            onToggle = onToggleSubscription,
                            onDelete = onDeleteSubscription,
                            onRefresh = onRefreshSubscription,
                            onEdit = { editingSubscriptionId = it.id ?: -1L }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 32.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // ADD Section
            item(key = "add_header", contentType = "section_header") {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader(stringResource(R.string.subscriptions_section_add))
            }

            item(key = "add_button", contentType = "add_button") {
                OutlinedButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.subscriptions_cd_add),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        stringResource(R.string.subscriptions_add_ics),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }

    // Add subscription dialog
    if (showAddDialog) {
        AddSubscriptionDialog(
            initialUrl = null,
            onDismiss = { showAddDialog = false },
            onAdd = { url, name, color ->
                onAddSubscription(url, name, color)
                showAddDialog = false
            }
        )
    }

    // Edit subscription dialog
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
