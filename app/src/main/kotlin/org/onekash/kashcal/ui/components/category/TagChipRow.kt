package org.onekash.kashcal.ui.components.category

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.category.CategoryName
import org.onekash.kashcal.domain.category.CategoryNameError
import org.onekash.kashcal.domain.category.CategoryNameValidator

/**
 * Reusable tag row. At rest it is a single quiet line: either a "+ New tag"
 * affordance (no tags yet) or the applied tags as filled chips (with an "x" to
 * remove) followed by a small "+" to add more.
 *
 * Tapping the add affordance engages a type-to-filter picker: a text field over
 * a list of [suggestions] (rendered in the order given — already usage-ranked,
 * not re-sorted here). Typing prefix-filters the list; a "Create '…'" row is
 * always offered last for a name that isn't an existing suggestion. Committing
 * a typed name runs it through [CategoryNameValidator]; an invalid name shows an
 * inline error and does not commit.
 *
 * In [readOnly] mode (quick-view) the chips render without the "x", and no add
 * affordance or field is shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChipRow(
    selected: Set<String>,
    suggestions: List<String>,
    onToggle: (String) -> Unit,
    onAdd: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
) {
    // Local edit state. Applied tags live in the caller's hoisted state (via
    // onAdd/onToggle), so they always persist; only this in-progress draft is
    // local. If the host moves this row to a different layout position while a
    // draft is half-typed (the form lets the user relocate the tag row), the
    // row is recomposed at the new slot and the uncommitted draft resets. That
    // edge is accepted — committed tags are never lost, only unsaved keystrokes.
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    val cdTemplate = stringResource(R.string.cd_tag)
    val addLabel = stringResource(R.string.tags_new)

    // Commit a typed name (from the field's Done action or the "Create" row):
    // validate, and on success add it and collapse back to the resting line.
    // Validate against both applied tags and suggestions so a typed name that
    // matches an existing tag reuses its first-seen casing (typing "personal"
    // when "Personal" is a known tag commits "Personal", matching the tap path).
    val commit: (String) -> Unit = { raw ->
        when (val outcome = CategoryNameValidator.validate(raw, selected + suggestions)) {
            is CategoryName.Valid -> {
                onAdd(outcome.value)
                draft = ""
                adding = false
                errorRes = null
            }
            is CategoryName.Invalid -> errorRes = outcome.error.toMessageRes()
        }
    }

    Column(modifier = modifier) {
        // Resting/applied chips. The "x" and add affordances are hidden in
        // read-only mode. Chips are laid out without the 48dp minimum
        // interactive size so a row of them isn't padded to touch-target
        // height — Material sizes chips at ~32dp and expects groups to opt out
        // of the enforcement, keeping the row compact.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Skip blank names — a malformed pulled CATEGORIES value can carry
            // an empty element that would otherwise render as a blank chip.
            selected.filter { it.isNotBlank() }.forEach { tag ->
                val bg = Color(colorForTag(tag))
                val fg = Color(onColorFor(colorForTag(tag)))
                FilterChip(
                    selected = true,
                    onClick = { if (!readOnly) onToggle(tag) },
                    label = { Text(tag) },
                    leadingIcon = null,
                    trailingIcon = if (!readOnly) {
                        { Icon(Icons.Default.Close, contentDescription = null) }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = bg,
                        selectedLabelColor = fg,
                        selectedTrailingIconColor = fg,
                    ),
                    modifier = Modifier.semantics { contentDescription = cdTemplate.format(tag) },
                )
            }

            if (!readOnly && !adding) {
                if (selected.isEmpty()) {
                    // No tags yet: a single "+ New tag" affordance.
                    AssistChip(
                        onClick = { adding = true },
                        label = { Text(addLabel) },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                    )
                } else {
                    // Has tags: a compact "+" to add more. Sized to match the
                    // chip height (the row opts out of the 48dp min target, so
                    // pin the button so its tap area stays a comfortable 40dp).
                    FilledTonalIconButton(
                        onClick = { adding = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = addLabel,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        }

        if (!readOnly && adding) {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    errorRes = null
                },
                singleLine = true,
                isError = errorRes != null,
                placeholder = { Text(addLabel) },
                supportingText = errorRes?.let { res -> { Text(stringResource(res)) } },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit(draft) }),
            )

            // Type-to-filter picker. Prefix-match against the (pre-ranked)
            // suggestions, excluding tags already applied; keep the given order.
            val prefix = draft.trim().removePrefix("#").trim()
            val matches = suggestions.filter { s ->
                s.startsWith(prefix, ignoreCase = true) &&
                    selected.none { it.equals(s, ignoreCase = true) }
            }
            val exactExists = matches.any { it.equals(prefix, ignoreCase = true) } ||
                selected.any { it.equals(prefix, ignoreCase = true) }

            matches.forEach { tag ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable {
                            onToggle(tag)
                            draft = ""
                            adding = false
                            errorRes = null
                        }
                        .padding(horizontal = 8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(colorForTag(tag))),
                    )
                    Text(tag, style = MaterialTheme.typography.bodyLarge)
                }
            }

            // "Create '…'" is always last, offered for any non-blank name that
            // isn't already an existing suggestion. Committing validates it.
            if (prefix.isNotEmpty() && !exactExists) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable { commit(draft) }
                        .padding(horizontal = 8.dp),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.tags_autocomplete_create_new, prefix),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private fun CategoryNameError.toMessageRes(): Int = when (this) {
    CategoryNameError.EMPTY -> R.string.tags_empty_reject
    CategoryNameError.COMMA -> R.string.tags_comma_reject
    CategoryNameError.TOO_LONG -> R.string.tags_too_long
}
