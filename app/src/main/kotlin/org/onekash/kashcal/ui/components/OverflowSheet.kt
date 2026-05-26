package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.viewmodels.ViewMode

/**
 * Material 3 [ModalBottomSheet] hosting six destinations: Invites, Date,
 * Share availability, Insights, Settings, About. Sheet handles scrim
 * tap, drag-to-dismiss, and back press natively.
 *
 * Row taps fire the destination callback BEFORE invoking onDismiss so
 * the destination's window opens on top of the overflow's exit animation.
 * The user sees only the destination's slide-up; the overflow's slide-down
 * happens behind the new sheet's dialog window and is no longer visible.
 *
 * The Invites row's inline badge text is driven by [formatBadgeCount],
 * the same helper that drives the overflow IconButton's badge in the
 * top bar so the two badge sites can never disagree on what counts as
 * "show" or where the "99+" cap renders.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OverflowSheet(
    currentViewMode: ViewMode,
    pendingInvitesCount: Int,
    onInvitesClick: () -> Unit,
    onJumpToDateClick: () -> Unit,
    onShareAvailabilityClick: () -> Unit,
    onInsightsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier
    ) {
        OverflowSheetContent(
            currentViewMode = currentViewMode,
            pendingInvitesCount = pendingInvitesCount,
            onInvitesClick = { onInvitesClick(); onDismiss() },
            onJumpToDateClick = { onJumpToDateClick(); onDismiss() },
            onShareAvailabilityClick = { onShareAvailabilityClick(); onDismiss() },
            onInsightsClick = { onInsightsClick(); onDismiss() },
            onSettingsClick = { onSettingsClick(); onDismiss() },
            onAboutClick = { onAboutClick(); onDismiss() }
        )
    }
}

/**
 * Pure content composable for the overflow sheet — extracted so it can
 * be rendered directly in tests without the [ModalBottomSheet] wrapper
 * (which doesn't expose its internal scaffolding to instrumented tests).
 * Callbacks here invoke the passed-in lambdas verbatim — dismissal is
 * handled by [OverflowSheet].
 */
@Composable
internal fun OverflowSheetContent(
    currentViewMode: ViewMode,
    pendingInvitesCount: Int,
    onInvitesClick: () -> Unit,
    onJumpToDateClick: () -> Unit,
    onShareAvailabilityClick: () -> Unit,
    onInsightsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.menu_invites)) },
            icon = { Icon(Icons.Default.MailOutline, contentDescription = null) },
            badge = {
                val badgeText = formatBadgeCount(pendingInvitesCount)
                if (badgeText != null) {
                    Badge { Text(badgeText) }
                }
            },
            selected = false,
            onClick = onInvitesClick
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.jump_to_date)) },
            icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
            selected = false,
            onClick = onJumpToDateClick
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.share_availability_rail_label)) },
            icon = { Icon(Icons.Default.Share, contentDescription = null) },
            selected = false,
            onClick = onShareAvailabilityClick
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.view_insights)) },
            icon = { Icon(Icons.Default.Insights, contentDescription = null) },
            selected = currentViewMode == ViewMode.INSIGHTS,
            onClick = onInsightsClick
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.settings_title)) },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            selected = false,
            onClick = onSettingsClick
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.menu_about)) },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            selected = false,
            onClick = onAboutClick
        )
    }
}
