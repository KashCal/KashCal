package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R

/**
 * Shows the inline confirm in place of the Force full sync row: one checkbox line naming what
 * [scope] re-downloads, Cancel and Start. Start is off until the box is ticked; the box starts
 * unticked each time the confirm appears. [onStart] runs the sync and [onCancel] closes it.
 *
 * A forced sync also runs [org.onekash.kashcal.data.db.dao.PendingOperationsDao.resetAllFailed],
 * so failed and abandoned changes get another try; pull keeps rows with pending local changes.
 */
@Composable
fun ForceFullSyncConfirm(
    scope: SyncScope,
    onCancel: () -> Unit,
    onStart: () -> Unit,
) {
    var ticked by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .toggleable(value = ticked, role = Role.Checkbox, onValueChange = { ticked = it })
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = ticked, onCheckedChange = null)
            Text(
                stringResource(
                    R.string.force_sync_confirm,
                    pluralStringResource(R.plurals.force_sync_confirm_calendars, scope.calendars, scope.calendars),
                    pluralStringResource(R.plurals.force_sync_confirm_accounts, scope.accounts, scope.accounts),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(onClick = onStart, enabled = ticked) {
                Text(stringResource(R.string.action_start))
            }
        }
    }
}
