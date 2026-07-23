package org.onekash.kashcal.ui.components.attendees

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.shared.contrastForegroundOn
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.contacts.ContactEmail
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.domain.identity.matchesAttendee
import org.onekash.kashcal.domain.scheduling.AttendeeInput
import org.onekash.kashcal.domain.scheduling.AttendeeInputParser
import org.onekash.kashcal.ui.permission.ContactsPermissionState
import org.onekash.kashcal.util.AddressNormalizer

/**
 * The attendee picker: a [ModalBottomSheet] off the event form's Attendees
 * row. Search field always on top (usable without any permission); selected
 * attendees as removable chips; debounced contact suggestions; an
 * "Add 'x@y'" row for a typed email; an inline contacts-permission banner
 * (never a popup) that disappears on permanent denial.
 *
 * The selection model holds [Attendee] ENTITIES, seeded from the event's
 * existing rows, so editing preserves wire fields the UI projection drops.
 * Selections auto-commit: every add/remove fires [onSelectionChanged] with the
 * merged entities, so there's no confirm step — back or swipe just closes
 * (consistent with the settings/event search surfaces). Mirrors how Signal's
 * contact picker commits live.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, FlowPreview::class)
@Composable
fun AttendeePickerSheet(
    seed: List<Attendee>,
    account: Account?,
    permissionState: ContactsPermissionState,
    bannerDismissed: Boolean,
    onQueryContacts: suspend (String) -> List<ContactEmail>,
    onRequestPermission: () -> Unit,
    onDeclineContacts: () -> Unit,
    onDismissPermissionBanner: () -> Unit,
    onSelectionChanged: (attendees: List<Attendee>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selection by remember(seed) { mutableStateOf(AttendeeSelection.seed(seed)) }
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<ContactEmail>>(emptyList()) }

    // Auto-focus the search field on open so the keyboard is up immediately —
    // the picker is a search-first surface, so a second tap to focus is wasted.
    // The field isn't attached during the sheet's enter animation, so a
    // one-shot requestFocus() can no-op on slower devices (the keyboard never
    // appears). Retry across frames until focus takes (or we give up), so the
    // behavior is deterministic regardless of animation timing.
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            val focused = try {
                searchFocus.requestFocus()
                true
            } catch (_: IllegalStateException) {
                false // FocusRequester not attached yet — wait a frame and retry.
            }
            if (focused) return@LaunchedEffect
            withFrameNanos {}
        }
    }

    // Apply a selection change and auto-commit it to the caller. add/remove
    // return the same instance on a no-op (duplicate add / absent remove), so
    // referential inequality is the precise "something actually changed" test —
    // avoids a redundant callback when nothing did.
    fun applySelection(next: AttendeeSelection) {
        if (next !== selection) {
            selection = next
            onSelectionChanged(next.attendees)
        }
    }

    // Pin the sheet to a near-full-screen fixed height (matching EventFormSheet)
    // so it opens tall like the Signal contact picker and — crucially — does
    // NOT resize when the search keyboard opens/closes (a fillMaxHeight sheet
    // recomputes against the IME-shrunk window and visibly hops). Computed once
    // per configuration so rotation still resizes correctly.
    val configuration = LocalConfiguration.current
    val sheetHeight = remember(configuration.orientation, configuration.screenWidthDp) {
        (configuration.screenHeightDp * 0.95f).dp
    }

    // Debounced contact lookup. Re-queries only when granted; otherwise the
    // suggestion list stays empty and the user types a full email instead.
    LaunchedEffect(permissionState) {
        snapshotFlow { query }
            .debounce(300)
            .collect { q ->
                suggestions = if (permissionState is ContactsPermissionState.Granted) {
                    onQueryContacts(q)
                } else {
                    emptyList()
                }
            }
    }

    val parsed = AttendeeInputParser.parse(query)
    val selectedCanonicals = remember(selection) {
        selection.attendees.map { AddressNormalizer.canonical(it.address) }.toSet()
    }

    // Pinned like EventFormSheet: no drag handle, gestures disabled, fixed tall
    // height. It opens full like the Signal picker and stays put while the
    // search keyboard opens/closes. The back button (and scrim tap) closes it;
    // selections auto-commit, so nothing is lost on close.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {},
        sheetGesturesEnabled = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
        ) {
        // Back-button header (back closes), matching the settings/event-search
        // search surfaces — no confirm/cancel buttons since edits auto-commit.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
            Text(
                text = stringResource(R.string.attendee_picker_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            org.onekash.kashcal.ui.screens.settings.BetaBadge()
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.attendee_pick_from_contacts)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            isError = query.isNotBlank() && parsed is AttendeeInput.Invalid,
            supportingText = if (query.isNotBlank() && parsed is AttendeeInput.Invalid) {
                { Text(stringResource(R.string.attendee_invalid_email)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                (parsed as? AttendeeInput.Valid)?.let {
                    applySelection(selection.addNew(it.displayName, it.email))
                    query = ""
                }
            }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(searchFocus)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // Everything below the pinned header + search scrolls as one region —
        // selected chips, the optional permission banner, and the suggestion
        // list — so a long list of added attendees stays reachable instead of
        // overflowing off the bottom. Header + search stay on screen.
        val canOfferContacts = permissionState is ContactsPermissionState.NotRequested ||
            permissionState is ContactsPermissionState.ShouldShowRationale
        val showBanner = canOfferContacts && !bannerDismissed && query.isBlank()
        val validTyped = parsed as? AttendeeInput.Valid
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 8.dp),
        ) {
            // Selected attendees as removable chips.
            if (selection.attendees.isNotEmpty()) {
                item(key = "selected_chips") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        selection.attendees.forEach { att ->
                            val canonical = AddressNormalizer.canonical(att.address)
                            val label = att.displayName?.takeIf { it.isNotBlank() }
                                ?: AddressNormalizer.stripMailto(att.address)
                            val isYou = account?.matchesAttendee(att.address) == true
                            // Every guest is removable — uninviting a seeded
                            // guest sends them an iTIP CANCEL on save.
                            val removable = selection.isRemovable(att)
                            FilterChip(
                                selected = true,
                                onClick = {
                                    if (removable) applySelection(selection.remove(canonical))
                                },
                                label = { Text(if (isYou) stringResource(R.string.attendee_you_marker) else label) },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(avatarColorFor(att.address)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = avatarInitials(label),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = contrastForegroundOn(avatarColorFor(att.address)),
                                        )
                                    }
                                },
                                trailingIcon = if (removable) {
                                    {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }

            // Inline permission banner — shown only when contacts could help,
            // the user hasn't dismissed it this session, and there's no active
            // query. Permanent denial hides it too (no settings redirect —
            // manual entry remains).
            if (showBanner) {
                item(key = "perm_banner") {
                    ContactsPermissionBanner(
                        onAllow = onRequestPermission,
                        onDeny = onDeclineContacts,
                        onDismiss = onDismissPermissionBanner,
                    )
                }
            }

            // "Add 'x@y'" row for a valid typed email not already selected.
            if (validTyped != null &&
                AddressNormalizer.canonical(validTyped.email) !in selectedCanonicals
            ) {
                item(key = "add_typed") {
                    AddTypedRow(
                        email = validTyped.email,
                        onClick = {
                            applySelection(selection.addNew(validTyped.displayName, validTyped.email))
                            query = ""
                        },
                    )
                }
            }
            items(
                items = suggestions.filter {
                    AddressNormalizer.canonical(it.address) !in selectedCanonicals
                },
                key = { "contact_${it.address}" },
            ) { contact ->
                ContactRow(
                    contact = contact,
                    onClick = {
                        applySelection(selection.addNew(contact.displayName, contact.address))
                    },
                )
            }
        }

        }
    }
}

/**
 * Compact read-only chip used on the event form's editable Attendees row to
 * preview a selected invitee (avatar dot + label). Removal happens inside the
 * picker, so this chip has no close affordance.
 */
@Composable
fun AttendeePickChip(label: String, address: String, initialsSource: String = label) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 10.dp, top = 4.dp, bottom = 4.dp, start = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(avatarColorFor(address)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // Initials come from the person's real name, not the visible
                    // label — when the label is the "You" marker the avatar must
                    // still show the user's own initials, not "Y".
                    text = avatarInitials(initialsSource),
                    style = MaterialTheme.typography.labelSmall,
                    color = contrastForegroundOn(avatarColorFor(address)),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Inline contacts-permission card, shaped like an Android-settings grouped
 * card. [onAllow] fires the system dialog; [onDeny] permanently declines (the
 * banner never returns); the top-right ✕ ([onDismiss]) hides it for this
 * session only.
 */
@Composable
private fun ContactsPermissionBanner(
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.PersonAdd,
                        contentDescription = null, // decorative; title carries the meaning
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = stringResource(R.string.attendee_contacts_permission_rationale),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        // Leave room for the ✕ in the top-right corner.
                        modifier = Modifier.padding(end = 28.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDeny) {
                        Text(stringResource(R.string.action_no_thanks))
                    }
                    TextButton(onClick = onAllow) {
                        Text(stringResource(R.string.action_allow))
                    }
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_dismiss),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddTypedRow(email: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                // The tonal fill can wash out against the surface for pale accent
                // seeds; a hairline outline keeps the avatar edge defined on any theme.
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.attendee_add_typed, email),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ContactRow(contact: ContactEmail, onClick: () -> Unit) {
    val label = contact.displayName.ifBlank { contact.address }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(avatarColorFor(contact.address)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = avatarInitials(label),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = contrastForegroundOn(avatarColorFor(contact.address)),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (contact.displayName.isNotBlank() &&
                !contact.address.equals(contact.displayName, ignoreCase = true)
            ) {
                Text(
                    text = contact.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
