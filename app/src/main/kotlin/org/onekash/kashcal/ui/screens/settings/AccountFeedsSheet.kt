package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R

/**
 * Shows Calendar Feeds' "Add from account" sheet: a title whose ⓘ opens an explanation inline,
 * then one section per feed account
 * ([org.onekash.kashcal.domain.model.AccountProvider.supportsCalendarFeeds]) with the feeds its
 * server stores.
 *
 * Every state comes from the ViewModel; the sheet only renders it and reports taps.
 *
 * @param onSwitch called with the section's account id, the feed's URL and the switch's new value.
 * @param onRetry called with the account id of a section whose listing failed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountFeedsSheet(
    state: AccountFeedsSheetState,
    onSwitch: (accountId: Long, url: String, on: Boolean) -> Unit,
    onRetry: (accountId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Full height, like the holiday sheet, so the list scrolls in a stable area.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(bottom = 24.dp),
        ) {
            SheetTitle()

            if (state.sections.isEmpty()) {
                SectionMessage(stringResource(R.string.account_feeds_none))
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(state.sections, key = { it.accountId }) { section ->
                        AccountFeedsSectionView(section, onSwitch, onRetry)
                    }
                }
            }
        }
    }
}

/** The title, with an ⓘ that opens the explanation inline below it ([LineWithInfo]). */
@Composable
private fun SheetTitle() {
    val title = stringResource(R.string.account_feeds_add)
    LineWithInfo(
        detail = stringResource(R.string.account_feeds_note),
        infoDescription = stringResource(R.string.cd_about_setting, title),
        modifier = Modifier.padding(start = 24.dp, end = 8.dp, bottom = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun AccountFeedsSectionView(
    section: AccountFeedsSection,
    onSwitch: (accountId: Long, url: String, on: Boolean) -> Unit,
    onRetry: (accountId: Long) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            section.displayName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Text(
            section.maskedEmail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        when (val content = section.content) {
            AccountFeedsContent.Checking -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.account_feeds_checking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AccountFeedsContent.NoFeeds -> SectionMessage(stringResource(R.string.account_feeds_none))
            AccountFeedsContent.Failed -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(R.string.account_feeds_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onRetry(section.accountId) }) {
                    Text(stringResource(R.string.action_retry))
                }
            }
            is AccountFeedsContent.Feeds -> content.rows.forEach { row ->
                AccountFeedRowView(row) { url, on -> onSwitch(section.accountId, url, on) }
            }
        }
    }
}

/** One toggleable element, so TalkBack reads the feed's name and host with the switch state. */
@Composable
private fun AccountFeedRowView(row: AccountFeedRow, onSwitch: (url: String, on: Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = row.isOn,
                enabled = !row.isBusy,
                role = Role.Switch,
                onValueChange = { onSwitch(row.url, it) },
            )
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(Color(row.color), CircleShape),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.host,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.isBusy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Switch(checked = row.isOn, onCheckedChange = null, enabled = !row.isBusy)
    }
}

@Composable
private fun SectionMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
    )
}
