package org.onekash.kashcal.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.category.CategoryName
import org.onekash.kashcal.domain.category.CategoryNameValidator
import org.onekash.kashcal.ui.components.SettingsTopAppBar
import org.onekash.kashcal.ui.components.category.toMessageRes
import org.onekash.kashcal.ui.components.pickers.ColorPaletteSheet
import org.onekash.kashcal.ui.viewmodels.TagUiItem

/**
 * Tag-management detail screen. Lists every tag the user has, each with a
 * colored swatch, and lets them recolor, rename, or delete it. Fully
 * stateless / param-driven — the caller owns the tag list and the three actions.
 *
 * The active per-tag action sheet is local UI state; which tag it targets is
 * held alongside it so the sheet survives a config change with the same tag.
 */
private enum class TagSheet { NONE, COLOR, RENAME }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(
    tags: List<TagUiItem>,
    onNavigateBack: () -> Unit,
    onSetColor: (name: String, color: Int) -> Unit,
    onRename: (from: String, to: String) -> Unit,
    onDelete: (name: String) -> Unit,
) {
    var activeSheet by rememberSaveable { mutableStateOf(TagSheet.NONE) }
    var targetName by rememberSaveable { mutableStateOf("") }
    var syncInfoExpanded by rememberSaveable { mutableStateOf(false) }

    // Resolve the live row for the targeted tag so a rename/color that
    // changes the list updates or dismisses the sheet rather than acting stale.
    val target = remember(targetName, tags) { tags.find { it.name == targetName } }

    Scaffold(
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.tags_screen_title),
                onNavigateBack = onNavigateBack,
                backContentDescription = stringResource(R.string.subscriptions_cd_back),
                // Reached from the account hub, not a calendar view — a today
                // shortcut would be off-context here.
                showLogo = false,
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
            item(key = "tags_manage_note", contentType = "manage_note") {
                ManageNote(
                    expanded = syncInfoExpanded,
                    onToggleInfo = { syncInfoExpanded = !syncInfoExpanded },
                )
                // Full-width rule below the guidance so the tag list visibly
                // starts here, distinct from the header note above.
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            if (tags.isEmpty()) {
                item(key = "tags_empty", contentType = "empty_state") {
                    Text(
                        text = stringResource(R.string.tags_screen_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)
                    )
                }
            } else {
                items(
                    items = tags,
                    key = { it.name },
                    contentType = { "tag_item" }
                ) { tag ->
                    Column(modifier = Modifier.animateItem()) {
                        TagRow(
                            tag = tag,
                            onColor = { targetName = tag.name; activeSheet = TagSheet.COLOR },
                            onRename = { targetName = tag.name; activeSheet = TagSheet.RENAME },
                            onDelete = { onDelete(tag.name) },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 32.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }

    when (activeSheet) {
        TagSheet.NONE -> Unit
        TagSheet.COLOR -> if (target != null) {
            ColorPaletteSheet(
                selectedArgb = target.color,
                onColorSelected = {
                    onSetColor(target.name, it)
                    activeSheet = TagSheet.NONE
                },
                onDismiss = { activeSheet = TagSheet.NONE }
            )
        } else activeSheet = TagSheet.NONE
        TagSheet.RENAME -> if (target != null) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            RenameTagSheet(
                sheetState = sheetState,
                currentName = target.name,
                onSave = { onRename(target.name, it) },
                onDismiss = { activeSheet = TagSheet.NONE }
            )
        } else activeSheet = TagSheet.NONE
    }
}

/**
 * Top-of-screen guidance. Always shows a short intro to the tag actions. A
 * trailing info button expands the detail inline (removal is local, events keep
 * their labels, how tags travel over sync) rather than in a dialog, in keeping
 * with the app's inline-over-modal UX.
 */
@Composable
private fun ManageNote(
    expanded: Boolean,
    onToggleInfo: () -> Unit,
) {
    Column(modifier = Modifier.padding(start = 32.dp, end = 8.dp, top = 16.dp, bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = stringResource(R.string.tags_manage_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleInfo) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.tags_info_content_desc),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            // Tonal callout so the sync detail reads as its own info box, set off
            // from the plain note above it.
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(top = 8.dp, end = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.tags_device_interop_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun TagRow(
    tag: TagUiItem,
    onColor: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val swatchDesc = stringResource(R.string.tags_swatch_content_desc, tag.name)
    val actionsDesc = stringResource(R.string.tags_actions_content_desc, tag.name)

    Box {
        // The whole row is the tap target — tapping anywhere opens the actions
        // menu — with the trailing glyph as its affordance. The row carries the
        // "actions for <tag>" label; the swatch keeps its own so TalkBack still
        // announces the color separately.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { menuExpanded = true }
                .semantics { contentDescription = actionsDesc }
                .padding(start = 32.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(tag.color))
                    .semantics { contentDescription = swatchDesc }
            )
            Text(
                text = tag.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.MoreVert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tags_action_color)) },
                onClick = { menuExpanded = false; onColor() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tags_action_rename)) },
                onClick = { menuExpanded = false; onRename() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tags_action_delete)) },
                onClick = { menuExpanded = false; onDelete() }
            )
        }
    }
}

/**
 * Rename sheet: pre-fills the current name, disables Save for a blank name or
 * one unchanged from the current. Mirrors the account rename sheet; no
 * imePadding — Material3 handles IME insets for the modal sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameTagSheet(
    sheetState: SheetState,
    currentName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    val focusRequester = remember { FocusRequester() }

    // Run the same name rules as every other tag entry point (comma/length/blank),
    // so a rename can't slip an invalid name past the shared validator. An invalid
    // name shows inline (no blocking dialog) and disables Save; a true no-op (the
    // exact same string) also disables it. A case-only change ("work" -> "Work")
    // is a real edit and stays enabled — the rename cascade restamps the casing.
    val outcome = CategoryNameValidator.validate(name)
    val valid = outcome as? CategoryName.Valid
    val errorRes = (outcome as? CategoryName.Invalid)?.error?.toMessageRes()
    val changed = valid != null && valid.value != currentName

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.tags_rename_title),
                style = MaterialTheme.typography.titleLarge
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                isError = errorRes != null,
                supportingText = errorRes?.let { { Text(stringResource(it)) } },
                label = { Text(stringResource(R.string.tags_rename_hint)) }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = { valid?.let { onSave(it.value) }; onDismiss() },
                    modifier = Modifier.weight(1f),
                    enabled = changed
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
