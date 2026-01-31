package org.onekash.kashcal.ui.screens.settings

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.theme.KashCalTheme

/**
 * Subscriptions detail screen.
 *
 * Dedicated screen for managing ICS calendar subscriptions and contact birthdays.
 * Features:
 * - Contact birthdays section (shown only when enabled)
 * - LazyColumn with swipeable ICS subscription items
 * - Swipe left to delete
 * - Tap to edit
 * - Add buttons at bottom for Contact Birthdays and ICS subscriptions
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
    // State for dialogs (rememberSaveable survives config changes)
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editingSubscriptionId by rememberSaveable { mutableLongStateOf(-1L) }
    var showContactBirthdaysSheet by rememberSaveable { mutableStateOf(false) }

    // Derive editingSubscription from ID (complex objects can't be saved directly)
    val editingSubscription = remember(editingSubscriptionId, subscriptions) {
        if (editingSubscriptionId > 0) subscriptions.find { it.id == editingSubscriptionId } else null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.subscriptions_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.subscriptions_cd_back)
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
                bottom = paddingValues.calculateBottomPadding() + 16.dp
            )
        ) {
            // Contact Birthdays Section (only show when enabled)
            if (contactBirthdaysEnabled) {
                item {
                    SectionHeader(stringResource(R.string.subscriptions_section_contact_birthdays))
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
            }

            // ICS Subscriptions Section
            item {
                SectionHeader(stringResource(R.string.subscriptions_section_ics_calendars))
            }

            if (subscriptions.isEmpty()) {
                item {
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
                    key = { it.id ?: 0L }
                ) { subscription ->
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

            // ADD Section with buttons
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader(stringResource(R.string.subscriptions_section_add))
            }

            item {
                AddSubscriptionButtons(
                    onAddBirthdays = { showContactBirthdaysSheet = true },
                    onAddIcs = { showAddDialog = true },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
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
                stringResource(R.string.subscriptions_add_birthdays),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                if (enabled) stringResource(R.string.subscriptions_birthdays_enabled)
                else stringResource(R.string.subscriptions_birthdays_disabled),
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

/**
 * Add subscription buttons section.
 * Always visible at bottom of screen.
 */
@Composable
private fun AddSubscriptionButtons(
    onAddBirthdays: () -> Unit,
    onAddIcs: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Contact Birthdays button (always visible per user request)
        OutlinedButton(
            onClick = onAddBirthdays,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.Cake,
                contentDescription = stringResource(R.string.subscriptions_cd_birthdays_icon),
                modifier = Modifier.size(18.dp)
            )
            Text(
                stringResource(R.string.subscriptions_add_birthdays),
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        // Add ICS button
        OutlinedButton(
            onClick = onAddIcs,
            modifier = Modifier.fillMaxWidth()
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
            contactBirthdaysEnabled = true,
            contactBirthdaysColor = 0xFF9C27B0.toInt(),
            contactBirthdaysReminder = 540,
            contactBirthdaysLastSync = System.currentTimeMillis(),
            hasContactsPermission = true,
            onNavigateBack = {},
            onAddSubscription = { _, _, _ -> },
            onToggleSubscription = { _, _ -> },
            onDeleteSubscription = {},
            onRefreshSubscription = {},
            onUpdateSubscription = { _, _, _, _ -> },
            onToggleContactBirthdays = {},
            onContactBirthdaysColorChange = {},
            onContactBirthdaysReminderChange = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SubscriptionsScreenEmptyPreview() {
    KashCalTheme {
        SubscriptionsScreen(
            subscriptions = emptyList(),
            contactBirthdaysEnabled = false,
            contactBirthdaysColor = 0xFF9C27B0.toInt(),
            contactBirthdaysReminder = 540,
            contactBirthdaysLastSync = 0L,
            hasContactsPermission = false,
            onNavigateBack = {},
            onAddSubscription = { _, _, _ -> },
            onToggleSubscription = { _, _ -> },
            onDeleteSubscription = {},
            onRefreshSubscription = {},
            onUpdateSubscription = { _, _, _, _ -> },
            onToggleContactBirthdays = {},
            onContactBirthdaysColorChange = {},
            onContactBirthdaysReminderChange = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SubscriptionsScreenBirthdaysOnlyPreview() {
    KashCalTheme {
        SubscriptionsScreen(
            subscriptions = emptyList(),
            contactBirthdaysEnabled = true,
            contactBirthdaysColor = 0xFF9C27B0.toInt(),
            contactBirthdaysReminder = 540,
            contactBirthdaysLastSync = System.currentTimeMillis(),
            hasContactsPermission = true,
            onNavigateBack = {},
            onAddSubscription = { _, _, _ -> },
            onToggleSubscription = { _, _ -> },
            onDeleteSubscription = {},
            onRefreshSubscription = {},
            onUpdateSubscription = { _, _, _, _ -> },
            onToggleContactBirthdays = {},
            onContactBirthdaysColorChange = {},
            onContactBirthdaysReminderChange = {}
        )
    }
}
