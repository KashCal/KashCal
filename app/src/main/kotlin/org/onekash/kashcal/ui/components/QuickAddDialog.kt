package org.onekash.kashcal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.onekash.kashcal.R
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.quickadd.QuickAddResult
import org.onekash.kashcal.ui.theme.WarningAmberDark
import org.onekash.kashcal.ui.theme.WarningAmberLight

/**
 * Enforces the field's two input invariants on every edit (typing and paste):
 * strip newlines so Enter can't insert a line break (it triggers Save instead,
 * and the input stays one logical line), then hard-cap the buffer at
 * [QuickAddInputLimits.MAX_LENGTH] graphemes so a large paste fills to the limit
 * and drops the remainder. Only rewrites the buffer when it actually changed.
 */
private val quickAddInputTransformation = InputTransformation {
    val current = asCharSequence().toString()
    val stripped = current.replace("\n", "")
    val capped = QuickAddInputLimits.takeGraphemes(stripped, QuickAddInputLimits.MAX_LENGTH)
    if (capped != current) {
        replace(0, length, capped)
    }
}

private val placeholderExamples = listOf(
    // Practical / realistic
    "Coffee tomorrow at 3pm",
    "Dentist next Tuesday at 2pm",
    "Pick up groceries this evening",
    "Call mom Sunday afternoon",
    "Buy flowers for wife tomorrow",
    "Take kids to park Saturday 10am",
    "Date night Friday at 7pm",
    "Lunch with Sarah Thursday noon",
    "Oil change Saturday morning",
    "Haircut next Friday at 11am",
    // Feature showcase
    "Standup every weekday at 9am",
    "Conference Friday to Sunday",
    "Team retro Friday at noon",
    "Book club every Tuesday until December",
    "Yoga every Saturday morning",
    "Flight to Spain Monday 14:00",
    "Meeting at quarter past 10",
    "Walk the dog daily",
    "Lunch with Sam tomorrow // bring the contract",
    // Witty
    "Touch grass tomorrow afternoon",
    "Panic about deadline Friday night",
    "Become a morning person Monday 5am",
    "Nap aggressively Sunday afternoon",
    "Pretend to be productive Monday morning",
    "Regret skipping gym tonight",
    // Easter egg
    "Buy Kash a coffee tomorrow",
)

/**
 * Stateless host for Quick Add input. All state and behavior are hoisted to the caller;
 * the caller owns the input state, parse preview, and save/expand/dismiss handlers.
 * See `QuickAddDialogContent` for the pure content composable used in previews/tests.
 */
@Composable
fun QuickAddDialog(
    textFieldState: TextFieldState,
    parseResult: QuickAddResult,
    isSaveEnabled: Boolean,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onExpand: () -> Unit,
    timeFormat: String = KashCalDataStore.TIME_FORMAT_SYSTEM
) {
    val focusRequester = remember { FocusRequester() }
    val placeholder = remember { placeholderExamples.random() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // Scrim: covers the whole window (behind the bars) and owns tap-to-dismiss.
        // Kept scroll-free so a tap that drifts a few pixels still dismisses.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
        ) {
            // Card region, top-anchored. safeDrawingPadding() keeps it clear of the
            // status bar and the keyboard / nav bar in one shot (decorFitsSystemWindows
            // = false, so the window won't resize for the IME on its own; safeDrawing
            // is the union of those insets, so stacking imePadding + navigationBarsPadding
            // would double-count the nav bar). The scroll is scoped here, not on the
            // scrim, so if the card is taller than the safe region (landscape, large
            // font, a tall parse preview) Save scrolls into reach instead of clipping.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Top
            ) {
                QuickAddDialogContent(
                    textFieldState = textFieldState,
                    focusRequester = focusRequester,
                    parseResult = parseResult,
                    isSaveEnabled = isSaveEnabled,
                    isSaving = isSaving,
                    placeholder = placeholder,
                    timeFormat = timeFormat,
                    onSave = onSave,
                    onExpand = onExpand
                )
            }
        }
    }
}

@Composable
internal fun QuickAddDialogContent(
    textFieldState: TextFieldState,
    focusRequester: FocusRequester,
    parseResult: QuickAddResult,
    isSaveEnabled: Boolean,
    isSaving: Boolean,
    placeholder: String,
    onSave: () -> Unit,
    onExpand: () -> Unit,
    timeFormat: String = KashCalDataStore.TIME_FORMAT_SYSTEM
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* consume clicks so they don't dismiss */ },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
                keyboardController?.show()
            }

            OutlinedTextField(
                state = textFieldState,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text(placeholder) },
                // Grows from one line as text wraps, up to three, then scrolls
                // internally so nothing is ever lost on this fast-capture surface.
                lineLimits = TextFieldLineLimits.MultiLine(
                    minHeightInLines = 1,
                    maxHeightInLines = 3
                ),
                inputTransformation = quickAddInputTransformation,
                keyboardOptions = remember {
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done
                    )
                },
                onKeyboardAction = { if (isSaveEnabled && !isSaving) onSave() }
            )

            QuickAddCharCounter(
                count = QuickAddInputLimits.graphemeCount(textFieldState.text.toString())
            )

            QuickAddPreview(result = parseResult, timeFormat = timeFormat)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onExpand) {
                    Text(
                        text = stringResource(R.string.action_more_options),
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Button(
                    onClick = onSave,
                    enabled = isSaveEnabled && !isSaving
                ) {
                    Text(
                        text = stringResource(R.string.action_save),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * Left-aligned "N/500" caption under the Quick Add field. Occupies a fixed
 * min-height row at all times so revealing it never shifts the layout: hidden
 * below the reveal threshold, amber as the cap approaches, and a muted, bold
 * "at limit" treatment (never red — input is hard-capped, so over-limit can't
 * occur) once the cap is reached.
 */
@Composable
private fun QuickAddCharCounter(count: Int) {
    val state = QuickAddInputLimits.counterState(count)
    val onLightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val amber = if (onLightSurface) WarningAmberLight else WarningAmberDark

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 18.dp)
            .padding(top = 2.dp, start = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state != QuickAddCounterState.HIDDEN) {
            Text(
                text = stringResource(
                    R.string.quick_add_char_counter,
                    count,
                    QuickAddInputLimits.MAX_LENGTH
                ),
                style = MaterialTheme.typography.bodySmall,
                color = when (state) {
                    QuickAddCounterState.WARN -> amber
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (state == QuickAddCounterState.AT_LIMIT) FontWeight.Bold else null
            )
        }
    }
}
