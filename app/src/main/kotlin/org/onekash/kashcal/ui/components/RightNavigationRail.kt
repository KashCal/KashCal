package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.viewmodels.ViewMode

/**
 * Narrow right-anchored navigation rail. Four vertical anchors:
 * Invites (with a corner Badge overlay when pending count > 0),
 * Jump to date (action — opens a date picker), Insights (selected
 * when current view is INSIGHTS), Settings. Width is 80dp per the
 * design spec — default ModalDrawerSheet is 360dp.
 *
 * The Invites count overlay shares its formatting with the AppBar
 * rail toggle via [formatBadgeCount], so the two badge sites can
 * never disagree on what counts as "show" or where the cap renders.
 */
@Composable
internal fun RightNavigationRail(
    currentViewMode: ViewMode,
    pendingInvitesCount: Int,
    onInvitesClick: () -> Unit,
    onJumpToDateClick: () -> Unit,
    onInsightsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalDrawerSheet(modifier = modifier.width(80.dp)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = 72.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            NavigationRailItem(
                selected = false,
                onClick = onInvitesClick,
                icon = {
                    val badgeText = formatBadgeCount(pendingInvitesCount)
                    BadgedBox(
                        badge = {
                            if (badgeText != null) {
                                Badge { Text(badgeText) }
                            }
                        }
                    ) {
                        Icon(Icons.Default.MailOutline, contentDescription = null)
                    }
                },
                label = { Text(stringResource(R.string.menu_invites)) }
            )
            NavigationRailItem(
                selected = false,
                onClick = onJumpToDateClick,
                icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                label = { Text(stringResource(R.string.jump_to_date)) }
            )
            NavigationRailItem(
                selected = currentViewMode == ViewMode.INSIGHTS,
                onClick = onInsightsClick,
                icon = { Icon(Icons.Default.Insights, contentDescription = null) },
                label = { Text(stringResource(R.string.view_insights)) }
            )
            NavigationRailItem(
                selected = false,
                onClick = onSettingsClick,
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                label = { Text(stringResource(R.string.settings_title)) }
            )
            NavigationRailItem(
                selected = false,
                onClick = onAboutClick,
                icon = { Icon(Icons.Default.Info, contentDescription = null) },
                label = { Text(stringResource(R.string.menu_about)) }
            )
        }
    }
}
