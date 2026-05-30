package org.onekash.kashcal.ui.components.attendees

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R

/**
 * Unified attendees + RSVP component used by EventQuickViewSheet and
 * EventFormSheet's read-only banner. Combines the older separate
 * attendee-chip-row and RSVP-pill-row into one block:
 *
 * 1. **Summary line** — organizer name (with "Host" tag) + "You + N"
 *    or "N invited" off-list, plus a caret affordance to drill into
 *    the full attendee list.
 * 2. **RSVP cards** — Yes / Maybe / No, weighted primary on Yes
 *    pre-response.
 * 3. **Post-response** (QuickView only) — block collapses to a
 *    colored state band ("You're going · Change") with the cards
 *    re-expandable inline. The collapsed band stays clickable to drill
 *    into the attendee list and renders any series disclosure passed
 *    through.
 *
 * The form passes [alwaysExpanded] = true so the cards never
 * collapse — the form is the dedicated review surface where the
 * user might be re-reading their answer.
 *
 * @param attendees Full attendee list (with [AttendeeUiModel.isYou]
 *   and [AttendeeUiModel.isOrganizer] populated by the caller).
 * @param isCurrentUserOnList True when the active account matches
 *   any attendee. False = off-list / guest viewer.
 * @param isCurrentUserOrganizer True when the user is the event's
 *   ORGANIZER (RFC 5545 sense). Drives the summary phrasing — kept
 *   distinct from [suppressRsvp] so an editing-non-organizer doesn't
 *   get mislabeled.
 * @param suppressRsvp When true, the RSVP cards never render even if
 *   the user is on the list. Used by editable forms (where editing
 *   the event itself is the way to change attendance) without
 *   misrepresenting the user as the organizer.
 * @param onRsvp Fired when the user taps a card.
 * @param onDrillIntoAttendees Fired when the user taps the caret /
 *   summary / collapsed band. Caller decides what opens (today: nested
 *   attendee sheet; future: any other attendee surface).
 * @param seriesDisclosure Optional small line below the cards/band
 *   for recurring events ("This RSVP applies to the whole series").
 *   Rendered in both Expanded and Collapsed modes so the warning
 *   persists post-response.
 * @param alwaysExpanded When true, the cards stay always rendered
 *   even after the user has responded. Form uses this; QuickView
 *   doesn't.
 */
@Composable
fun InviteesBlock(
    attendees: List<AttendeeUiModel>,
    isCurrentUserOnList: Boolean,
    isCurrentUserOrganizer: Boolean,
    onRsvp: (AttendeeStatus) -> Unit,
    onDrillIntoAttendees: () -> Unit,
    modifier: Modifier = Modifier,
    suppressRsvp: Boolean = false,
    seriesDisclosure: String? = null,
    alwaysExpanded: Boolean = false,
) {
    if (attendees.isEmpty()) return

    val you = attendees.firstOrNull { it.isYou }
    val currentUserPartstat = you?.status
    val rsvpVisible = !suppressRsvp && shouldShowRespondSection(
        currentUserPartstat = currentUserPartstat,
        isOrganizer = isCurrentUserOrganizer,
    )
    // Delegated is RFC-valid but not a status we offer the user as a
    // pick — treat it like "no response yet" so the cards stay
    // interactive instead of showing a colored band whose label
    // doesn't match.
    val isInteractiveResponse = currentUserPartstat != null &&
        currentUserPartstat != AttendeeStatus.NeedsAction &&
        currentUserPartstat != AttendeeStatus.Delegated

    // Local re-expand state for QuickView's "Change" affordance.
    // Form's alwaysExpanded = true bypasses this entirely.
    var changing by remember(currentUserPartstat) { mutableStateOf(false) }

    val mode = when {
        !rsvpVisible -> InviteesBlockMode.SummaryOnly
        alwaysExpanded -> InviteesBlockMode.Expanded
        isInteractiveResponse && !changing -> InviteesBlockMode.Collapsed(currentUserPartstat!!)
        else -> InviteesBlockMode.Expanded
    }

    when (mode) {
        InviteesBlockMode.SummaryOnly -> SummaryOnlyBlock(
            attendees = attendees,
            isCurrentUserOnList = isCurrentUserOnList,
            isCurrentUserOrganizer = isCurrentUserOrganizer,
            onClick = onDrillIntoAttendees,
            modifier = modifier,
        )
        is InviteesBlockMode.Collapsed -> CollapsedStateBand(
            partstat = mode.partstat,
            attendees = attendees,
            onChange = { changing = true },
            onDrillIntoAttendees = onDrillIntoAttendees,
            seriesDisclosure = seriesDisclosure,
            modifier = modifier,
        )
        InviteesBlockMode.Expanded -> ExpandedBlock(
            attendees = attendees,
            isCurrentUserOnList = isCurrentUserOnList,
            isCurrentUserOrganizer = isCurrentUserOrganizer,
            currentUserPartstat = currentUserPartstat,
            onRsvp = { status ->
                // Invoke onRsvp first so the upstream partstat update
                // arrives before we collapse — the remember keyed on
                // currentUserPartstat then resets `changing` for free.
                // Setting `changing = false` first would briefly render
                // Collapsed with the OLD partstat for one frame.
                onRsvp(status)
                changing = false
            },
            onDrillIntoAttendees = onDrillIntoAttendees,
            seriesDisclosure = seriesDisclosure,
            modifier = modifier,
        )
    }
}

private sealed interface InviteesBlockMode {
    data object SummaryOnly : InviteesBlockMode
    data class Collapsed(val partstat: AttendeeStatus) : InviteesBlockMode
    data object Expanded : InviteesBlockMode
}

@Composable
private fun ExpandedBlock(
    attendees: List<AttendeeUiModel>,
    isCurrentUserOnList: Boolean,
    isCurrentUserOrganizer: Boolean,
    currentUserPartstat: AttendeeStatus?,
    onRsvp: (AttendeeStatus) -> Unit,
    onDrillIntoAttendees: () -> Unit,
    seriesDisclosure: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(2.dp),
    ) {
        SummaryRow(
            attendees = attendees,
            isCurrentUserOnList = isCurrentUserOnList,
            isCurrentUserOrganizer = isCurrentUserOrganizer,
            onClick = onDrillIntoAttendees,
        )
        if (currentUserPartstat != null) {
            Spacer(Modifier.height(8.dp))
            // The chosen card is filled-blue, so the user can see their
            // current state at a glance — no separate "Currently going"
            // hint needed when re-expanding via Change.
            Text(
                text = stringResource(R.string.rsvp_question_going),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
            RsvpCards(
                currentUserPartstat = currentUserPartstat,
                onRsvp = onRsvp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
            if (!seriesDisclosure.isNullOrBlank()) {
                Text(
                    text = seriesDisclosure,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SummaryOnlyBlock(
    attendees: List<AttendeeUiModel>,
    isCurrentUserOnList: Boolean,
    isCurrentUserOrganizer: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (background, foreground, leadingIcon) = if (!isCurrentUserOnList) {
        // Off-list: lavender tertiaryContainer + Group icon, mirroring
        // the older lavender-count chip so users still get the at-a-
        // glance "you aren't invited" cue.
        Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            true,
        )
    } else {
        Triple(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.onSurface,
            false,
        )
    }
    val showAllAction = stringResource(R.string.attendee_show_all)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                onClick(label = showAllAction, action = null)
            },
    ) {
        SummaryRow(
            attendees = attendees,
            isCurrentUserOnList = isCurrentUserOnList,
            isCurrentUserOrganizer = isCurrentUserOrganizer,
            onClick = null, // outer Box owns the click; suppress inner ripple
            leadingGroupIcon = leadingIcon,
            textColor = foreground,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun CollapsedStateBand(
    partstat: AttendeeStatus,
    attendees: List<AttendeeUiModel>,
    onChange: () -> Unit,
    onDrillIntoAttendees: () -> Unit,
    seriesDisclosure: String?,
    modifier: Modifier = Modifier,
) {
    val colors = when (partstat) {
        AttendeeStatus.Accepted -> RsvpStateColors(
            background = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            foreground = MaterialTheme.colorScheme.primary,
            border = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
            stateRes = R.string.rsvp_state_going,
        )
        AttendeeStatus.Tentative -> RsvpStateColors(
            background = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
            foreground = MaterialTheme.colorScheme.tertiary,
            border = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f),
            stateRes = R.string.rsvp_state_maybe,
        )
        AttendeeStatus.Declined -> RsvpStateColors(
            background = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
            foreground = MaterialTheme.colorScheme.error,
            border = MaterialTheme.colorScheme.error.copy(alpha = 0.25f),
            stateRes = R.string.rsvp_state_not_going,
        )
        // Delegated is filtered before we get here (mode is Expanded for
        // Delegated). NeedsAction never reaches this branch either.
        // Fall back to a neutral scheme so a future PARTSTAT addition
        // doesn't silently render the wrong "going" copy.
        else -> RsvpStateColors(
            background = MaterialTheme.colorScheme.surfaceContainerHighest,
            foreground = MaterialTheme.colorScheme.onSurface,
            border = MaterialTheme.colorScheme.outlineVariant,
            stateRes = R.string.rsvp_state_going,
        )
    }
    val drillLabel = stringResource(R.string.attendee_show_all)
    val changeLabel = stringResource(R.string.rsvp_action_change_a11y)
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.background)
            .border(BorderStroke(1.dp, colors.border), shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onDrillIntoAttendees)
                    .semantics {
                        role = Role.Button
                        onClick(label = drillLabel, action = null)
                    },
            ) {
                Text(
                    text = stringResource(colors.stateRes),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SummaryText(
                    attendees = attendees,
                    // Collapsed is reachable only when showCards=true,
                    // which requires you != null. So treat as on-list
                    // and not-self-organizer for the meta line.
                    isCurrentUserOnList = true,
                    isCurrentUserOrganizer = false,
                    style = MaterialTheme.typography.bodySmall,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = stringResource(R.string.rsvp_action_change),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onChange)
                    .semantics {
                        role = Role.Button
                        onClick(label = changeLabel, action = null)
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (!seriesDisclosure.isNullOrBlank()) {
            Text(
                text = seriesDisclosure,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            )
        }
    }
}

private data class RsvpStateColors(
    val background: Color,
    val foreground: Color,
    val border: Color,
    val stateRes: Int,
)

@Composable
private fun SummaryRow(
    attendees: List<AttendeeUiModel>,
    isCurrentUserOnList: Boolean,
    isCurrentUserOrganizer: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    leadingGroupIcon: Boolean = false,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val showAllAction = stringResource(R.string.attendee_show_all)
    val rowModifier = if (onClick != null) {
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                onClick(label = showAllAction, action = null)
            }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    } else {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingGroupIcon) {
            Icon(
                imageVector = Icons.Default.Group,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier
                    .size(18.dp)
                    .padding(end = 8.dp),
            )
        }
        SummaryText(
            attendees = attendees,
            isCurrentUserOnList = isCurrentUserOnList,
            isCurrentUserOrganizer = isCurrentUserOrganizer,
            style = MaterialTheme.typography.bodyMedium,
            textColor = textColor,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = textColor.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun SummaryText(
    attendees: List<AttendeeUiModel>,
    isCurrentUserOnList: Boolean,
    isCurrentUserOrganizer: Boolean,
    style: androidx.compose.ui.text.TextStyle,
    textColor: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    val text = composeSummaryLine(
        attendees = attendees,
        isCurrentUserOnList = isCurrentUserOnList,
        isCurrentUserOrganizer = isCurrentUserOrganizer,
    )
    Text(
        text = text,
        style = style,
        color = textColor,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * Composable wrapper that resolves a [SummaryLine] (pure data) into a
 * localized [String] using string resources. Lives inside the Compose
 * world so [pluralStringResource] / [stringResource] can be called.
 */
@Composable
internal fun composeSummaryLine(
    attendees: List<AttendeeUiModel>,
    isCurrentUserOnList: Boolean,
    isCurrentUserOrganizer: Boolean,
): String {
    val line = formatSummaryLine(
        attendees = attendees,
        isCurrentUserOnList = isCurrentUserOnList,
        isCurrentUserOrganizer = isCurrentUserOrganizer,
    )
    val hostTag = stringResource(R.string.invitees_host_tag)
    return when (line) {
        SummaryLine.Empty -> ""
        SummaryLine.YouAlone -> stringResource(R.string.invitees_summary_you_alone)
        is SummaryLine.YouOrganizing ->
            pluralStringResource(R.plurals.invitees_summary_you_organizing, line.others, line.others)
        is SummaryLine.OffListTotal ->
            pluralStringResource(R.plurals.invitees_summary_off_list_total, line.total, line.total)
        is SummaryLine.OffListWithHost -> {
            val countPhrase = pluralStringResource(
                R.plurals.invitees_summary_off_list_total,
                line.total,
                line.total,
            )
            stringResource(R.string.invitees_summary_off_list_with_host, countPhrase, line.organizerName)
        }
        is SummaryLine.OrganizerPlusYou ->
            stringResource(R.string.invitees_summary_organizer_plus_you, line.organizerName, hostTag)
        is SummaryLine.OrganizerPlusYouPlusN ->
            pluralStringResource(
                R.plurals.invitees_summary_organizer_plus_you_plus_n,
                line.others,
                line.organizerName,
                hostTag,
                line.others,
            )
        is SummaryLine.YouPlusN ->
            pluralStringResource(R.plurals.invitees_summary_you_plus_n, line.others, line.others)
        is SummaryLine.OrganizerOtherMore ->
            pluralStringResource(
                R.plurals.invitees_summary_organizer_other_more,
                line.others,
                line.organizerName,
                line.others,
            )
    }
}

/**
 * Pure summary-line variant. Mirrors the priority list documented on
 * [formatSummaryLine]. The Compose layer resolves each variant into a
 * localized string via [composeSummaryLine].
 */
internal sealed interface SummaryLine {
    data object Empty : SummaryLine
    data object YouAlone : SummaryLine
    data class YouOrganizing(val others: Int) : SummaryLine
    data class OffListTotal(val total: Int) : SummaryLine
    data class OffListWithHost(val total: Int, val organizerName: String) : SummaryLine
    data class OrganizerPlusYou(val organizerName: String) : SummaryLine
    data class OrganizerPlusYouPlusN(val organizerName: String, val others: Int) : SummaryLine
    data class YouPlusN(val others: Int) : SummaryLine
    data class OrganizerOtherMore(val organizerName: String, val others: Int) : SummaryLine
}

/**
 * Build the [SummaryLine] variant for the given inputs. Pure — no
 * Compose, no string resources — so the priority logic is unit-
 * testable directly.
 *
 * Priority:
 * - Empty list → [SummaryLine.Empty]
 * - You're the organizer:
 *     - 1 attendee (just you) → [SummaryLine.YouAlone]
 *     - more → [SummaryLine.YouOrganizing] (others = total - 1)
 * - Off-list:
 *     - no organizer surfaced → [SummaryLine.OffListTotal]
 *     - organizer surfaced → [SummaryLine.OffListWithHost]
 * - On-list with organizer who isn't you:
 *     - just you + organizer → [SummaryLine.OrganizerPlusYou]
 *     - + others → [SummaryLine.OrganizerPlusYouPlusN]
 * - On-list with no organizer in list:
 *     - just you → [SummaryLine.YouAlone]
 *     - + others → [SummaryLine.YouPlusN]
 * - On-list-but-no-you (rare, projection edge case):
 *     - organizer surfaced → [SummaryLine.OrganizerOtherMore]
 *     - else → [SummaryLine.OffListTotal]
 */
internal fun formatSummaryLine(
    attendees: List<AttendeeUiModel>,
    isCurrentUserOnList: Boolean,
    isCurrentUserOrganizer: Boolean,
): SummaryLine {
    if (attendees.isEmpty()) return SummaryLine.Empty
    val total = attendees.size
    val you = attendees.firstOrNull { it.isYou }
    val organizer = attendees.firstOrNull { it.isOrganizer && !it.isYou }
    val othersCount = (total - (if (you != null) 1 else 0) - (if (organizer != null) 1 else 0))
        .coerceAtLeast(0)

    return when {
        isCurrentUserOrganizer && you != null -> {
            val others = total - 1
            if (others <= 0) SummaryLine.YouAlone else SummaryLine.YouOrganizing(others)
        }
        !isCurrentUserOnList -> {
            if (organizer != null) SummaryLine.OffListWithHost(total, organizer.displayName)
            else SummaryLine.OffListTotal(total)
        }
        organizer != null && you != null -> {
            if (othersCount <= 0) SummaryLine.OrganizerPlusYou(organizer.displayName)
            else SummaryLine.OrganizerPlusYouPlusN(organizer.displayName, othersCount)
        }
        you != null -> {
            val others = total - 1
            if (others <= 0) SummaryLine.YouAlone else SummaryLine.YouPlusN(others)
        }
        organizer != null -> SummaryLine.OrganizerOtherMore(organizer.displayName, total - 1)
        else -> SummaryLine.OffListTotal(total)
    }
}
