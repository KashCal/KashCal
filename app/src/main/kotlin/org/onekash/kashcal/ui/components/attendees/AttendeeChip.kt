package org.onekash.kashcal.ui.components.attendees

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import org.onekash.kashcal.R

/**
 * Single attendee chip. Tap toggles inline expansion revealing email +
 * Copy/Email actions. Status indicator dot left of the name; optional
 * organizer pill ahead of name; for the current user the name is replaced
 * by the literal "You" (the user already knows their own name, and the
 * server-supplied CN can be a noisy account login like "rkash").
 *
 * Uses Material 3 theme tokens — no hardcoded hex.
 */
@Composable
fun AttendeeChip(
    model: AttendeeUiModel,
    modifier: Modifier = Modifier
) {
    // Slot identity is pinned by AttendeeChipRow's `key(bareAddress, isSynthesized)`,
    // so a plain remember is sufficient — the slot itself is invariant per chip.
    var expanded by remember { mutableStateOf(false) }
    val statusColor = model.status.color()
    val statusIcon = model.status.icon()

    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val youLabel = stringResource(R.string.attendee_you_marker)
    val visibleName = if (model.isYou) youLabel else model.displayName
    val statusLabel = stringResource(model.status.labelResId)
    // Talkback announces "<name>, <status>" (and the email when expanded).
    val a11yDescription = if (expanded) {
        "$visibleName, $statusLabel, ${model.bareAddress}"
    } else {
        "$visibleName, $statusLabel"
    }

    Surface(
        modifier = modifier
            .testTag(TEST_TAG_CHIP)
            .semantics { contentDescription = a11yDescription },
        shape = RoundedCornerShape(if (expanded) 12.dp else 16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = { expanded = !expanded }
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .animateContentSize()
                .padding(
                    horizontal = 12.dp,
                    vertical = if (expanded) 10.dp else 6.dp
                ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Status indicator — colored dot + status icon overlay
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(16.dp)
                )

                if (model.isOrganizer) {
                    OrganizerPill()
                }

                Text(
                    text = visibleName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (model.isYou) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (expanded) {
                Text(
                    text = model.bareAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val copiedMsg = stringResource(R.string.attendee_email_copied)
                    val noEmailAppMsg = stringResource(R.string.attendee_no_email_app)
                    IconButton(
                        onClick = {
                            scope.launch {
                                val entry = androidx.compose.ui.platform.ClipEntry(
                                    android.content.ClipData.newPlainText(
                                        "email",
                                        model.bareAddress
                                    )
                                )
                                clipboard.setClipEntry(entry)
                                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(R.string.attendee_action_copy),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            try {
                                val intent = Intent(
                                    Intent.ACTION_SENDTO,
                                    "mailto:${model.bareAddress}".toUri()
                                ).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, noEmailAppMsg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = stringResource(R.string.attendee_action_email),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OrganizerPill() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Text(
            text = "👑",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
internal fun AttendeeStatus.color(): Color = when (this) {
    AttendeeStatus.Accepted -> MaterialTheme.colorScheme.primary
    AttendeeStatus.Declined -> MaterialTheme.colorScheme.error
    AttendeeStatus.Tentative -> MaterialTheme.colorScheme.secondary
    AttendeeStatus.Delegated -> MaterialTheme.colorScheme.secondary
    AttendeeStatus.NeedsAction -> MaterialTheme.colorScheme.tertiary
}

internal fun AttendeeStatus.icon(): ImageVector = when (this) {
    AttendeeStatus.Accepted -> Icons.Default.CheckCircle
    AttendeeStatus.Declined -> Icons.Default.Cancel
    AttendeeStatus.Tentative -> Icons.Default.HelpOutline
    AttendeeStatus.Delegated -> Icons.Default.SwapHoriz
    AttendeeStatus.NeedsAction -> Icons.Default.HourglassEmpty
}

internal const val TEST_TAG_CHIP = "AttendeeChip"
internal const val TEST_TAG_CHIP_ROW = "AttendeeChipRow"
internal const val TEST_TAG_LAVENDER_COUNT = "AttendeeChipRow_LavenderCount"
internal const val TEST_TAG_SHOW_MORE = "AttendeeChipRow_ShowMore"
