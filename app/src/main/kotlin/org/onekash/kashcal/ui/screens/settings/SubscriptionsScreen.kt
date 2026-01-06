package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Subscriptions detail screen.
 *
 * Dedicated screen for managing ICS calendar subscriptions.
 * Features:
 * - LazyColumn with swipeable items
 * - Swipe left to delete
 * - Tap to edit
 * - FAB to add new subscription
 *
 * @param subscriptions List of current subscriptions
 * @param onNavigateBack Callback to navigate back
 * @param onAddSubscription Callback when adding new subscription
 * @param onToggleSubscription Callback when subscription enabled/disabled
 * @param onDeleteSubscription Callback when subscription deleted
 * @param onRefreshSubscription Callback when subscription refreshed
 * @param onUpdateSubscription Callback when subscription updated
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
    // State for dialogs
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSubscription by remember { mutableStateOf<IcsSubscriptionUiModel?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subscriptions") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add subscription"
                )
            }
        }
    ) { paddingValues ->
        if (subscriptions.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "🔗",
                        style = MaterialTheme.typography.displayMedium
                    )
                    Text(
                        "No Subscriptions",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Subscribe to ICS calendar feeds\nlike holidays or sports schedules",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // Subscription list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding(),
                    bottom = paddingValues.calculateBottomPadding() + 80.dp // Space for FAB
                )
            ) {
                items(
                    items = subscriptions,
                    key = { it.id ?: 0L }
                ) { subscription ->
                    SwipeableSubscriptionItem(
                        subscription = subscription,
                        onToggle = onToggleSubscription,
                        onDelete = onDeleteSubscription,
                        onRefresh = onRefreshSubscription,
                        onEdit = { editingSubscription = it }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 32.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
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
            onDismiss = { editingSubscription = null },
            onSave = { name, color, syncInterval ->
                subscription.id?.let { id ->
                    onUpdateSubscription(id, name, color, syncInterval)
                }
                editingSubscription = null
            }
        )
    }
}
