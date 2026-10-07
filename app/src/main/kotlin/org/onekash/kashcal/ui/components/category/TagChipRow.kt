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
import org.onekash.kashcal.ui.components.LocalTextFieldGestureMarker

/**
 * Shows the event's tags and lets the user add or remove them.
 *
 * At rest it is one line: a "+ New tag" chip when no tag is applied, else the applied tags as
 * filled chips with an "x" to remove, then a small "+" to add more.
 *
 * The add affordance opens a type-to-filter picker: a text field over [suggestions] in the order
 * given (callers rank them; nothing re-sorts here). Typing prefix-filters the list, and a
 * "Create '…'" row comes last for a non-blank name matching neither a suggestion nor an applied
 * tag. A typed name commits through [CategoryNameValidator]; an invalid one shows an inline error
 * and doesn't commit.
 *
 * In [readOnly] mode (the quick views) the chips have no "x" and there is no add affordance.
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
    // Applied tags live in the caller's state (via onAdd/onToggle); only the draft is local.
    // Moving the row while a draft is half-typed (the form lets the user place the tag row
    // above or below notes) recomposes it at the new slot and resets the draft. Accepted:
    // committed tags are never lost, only unsaved keystrokes.
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    val cdTemplate = stringResource(R.string.cd_tag)
    val addLabel = stringResource(R.string.tags_new)

    // Empty when unprovided (previews, tests), so every chip falls back to its hash color.
    val tagColors = LocalTagColors.current

    // Commits a typed name from the field's Done action or the "Create" row, collapsing to
    // the resting line on success. Validating against applied tags and suggestions reuses an
    // existing tag's casing: typing "personal" when "Personal" is known commits "Personal",
    // as a tap would.
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
        // Chips are laid out without the 48dp minimum interactive size so the row isn't padded
        // to touch-target height; Material sizes chips at ~32dp and expects groups to opt out
        // of the enforcement.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // A malformed pulled CATEGORIES value can carry an empty element that would render
            // as a blank chip.
            selected.filter { it.isNotBlank() }.forEach { tag ->
                val tagColor = colorFor(tagColors, tag)
                val bg = Color(tagColor)
                val fg = Color(onColorFor(tagColor))
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
                    // The row opts out of the 48dp minimum target, so pin the "+" at 40dp to
                    // keep a comfortable tap area.
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
                modifier = LocalTextFieldGestureMarker.current.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit(draft) }),
            )

            // Prefix-match the suggestions in the given order, leaving out applied tags.
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
                            .background(Color(colorFor(tagColors, tag))),
                    )
                    Text(tag, style = MaterialTheme.typography.bodyLarge)
                }
            }

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

/** Maps a tag-name rejection to its message, for this row and the Tags settings screen. */
internal fun CategoryNameError.toMessageRes(): Int = when (this) {
    CategoryNameError.EMPTY -> R.string.tags_empty_reject
    CategoryNameError.COMMA -> R.string.tags_comma_reject
    CategoryNameError.TOO_LONG -> R.string.tags_too_long
}
