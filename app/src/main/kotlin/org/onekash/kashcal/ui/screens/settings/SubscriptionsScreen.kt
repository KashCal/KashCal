package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
 * Dedicated screen for managing ICS calendar subscriptions and contact birthdays.
 * Features:
 * - Contact birthdays section with enable toggle
 * - LazyColumn with swipeable ICS subscription items
 * - Swipe left to delete
 * - Tap to edit
 * - FAB to add new subscription
 *
 * @param subscriptions List of current ICS subscriptions
 * @param contactBirthdaysEnabled Whether contact birthdays is enabled
 * @param contactBirthdaysColor Calendar color for birthdays
 * @param contactBirthdaysReminder Birthday reminder minutes (-1 = no reminder)
 * @param contactBirthdaysLastSync Last sync timestamp
 * @param hasContactsPermission Whether READ_CONTACTS permission is granted
 * @param onNavigateBack Callback to navigate back
 * @param onAddSubscription Callback when adding new ICS subscription
 * @param onToggleSubscription Callback when ICS subscription enabled/disabled
 * @param onDeleteSubscription Callback when ICS subscription deleted
 * @param onRefreshSubscription Callback when ICS subscription refreshed
 * @param onUpdateSubscription Callback when ICS subscription updated
 * @param onToggleContactBirthdays Callback when contact birthdays toggled
 * @param onContactBirthdaysColorChange Callback when birthday calendar color changed
 * @param onContactBirthdaysReminderChange Callback when birthday reminder setting changed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    subscriptions: List<IcsSubscriptionUiModel>,
    contactBirthdaysEnabled: Boolean,
    contactBirthdaysColor: Int,
    contactBirthdaysReminder: Int,
    contactBirthdaysLastSync: Long,
    hasContactsPermission: Boolean,
    onNavigateBack: () -> Unit,
    onAddSubscription: (url: String, name: String, color: Int) -> Unit,
    onToggleSubscription: (Long, Boolean) -> Unit,
    onDeleteSubscription: (Long) -> Unit,
    onRefreshSubscription: (Long) -> Unit,
    onUpdateSubscription: (Long, String, Int, Int) -> Unit,
    onToggleContactBirthdays: (Boolean) -> Unit,
    onContactBirthdaysColorChange: (Int) -> Unit,
    onContactBirthdaysReminderChange: (Int) -> Unit
) {
    // State for dialogs
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSubscription by remember { mutableStateOf<IcsSubscriptionUiModel?>(null) }
    var showContactBirthdaysSheet by remember { mutableStateOf(false) }

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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding() + 80.dp // Space for FAB
            )
        ) {
            // Contact Birthdays Section
            item {
                SectionHeader("Contact Birthdays")
            }

            item {
                ContactBirthdaysRow(
                    enabled = contactBirthdaysEnabled,
                    onClick = { showContactBirthdaysSheet = true }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 32.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }

            // ICS Subscriptions Section
            item {
                SectionHeader("ICS Calendars")
            }

            if (subscriptions.isEmpty()) {
                item {
                    Text(
                        "No ICS subscriptions.\nTap + to add a calendar feed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)
                    )
                }
            } else {
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

    // Contact Birthdays sheet
    if (showContactBirthdaysSheet) {
        ContactBirthdaysSheet(
            isEnabled = contactBirthdaysEnabled,
            calendarColor = contactBirthdaysColor,
            reminderMinutes = contactBirthdaysReminder,
            lastSyncTime = contactBirthdaysLastSync,
            hasPermission = hasContactsPermission,
            onDismiss = { showContactBirthdaysSheet = false },
            onToggle = onToggleContactBirthdays,
            onColorChange = onContactBirthdaysColorChange,
            onReminderChange = onContactBirthdaysReminderChange
        )
    }
}

/**
 * Row item for Contact Birthdays in the subscriptions list.
 */
@Composable
private fun ContactBirthdaysRow(
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Contact Birthdays",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                if (enabled) "Enabled" else "Disabled",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
