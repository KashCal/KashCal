package org.onekash.kashcal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import kotlin.math.roundToInt

/** Position of a swipeable row. */
private enum class RevealValue { CLOSED, REVEALED }

/** Width of the Delete button, and the distance the row slides to show it. */
private val DELETE_WIDTH = 96.dp

/**
 * Wraps a list row so a swipe toward the start reveals a Delete button at the row's end; nothing
 * is deleted until the button is tapped. Once open, the row closes on a swipe back or when
 * [open] changes to false.
 *
 * - Both anchors use one sign: the drag gesture reverses in right-to-left layouts and
 *   [Modifier.offset] mirrors, so the button is at the end in both directions.
 * - One haptic tick plays when a drag reaches the reveal; the next plays only after the row
 *   closed.
 * - [content] gets a modifier carrying [deleteLabel] as a custom accessibility action, because
 *   TalkBack and Switch Access can't swipe; it goes on the row's own clickable node, the one
 *   TalkBack focuses. The button itself is hidden from accessibility and not clickable until the
 *   row is revealed, so a closed row has no second "Delete" stop behind it.
 * - Without [enabled] the row is plain.
 *
 * [SwipeRevealRowTest] covers it.
 */
@Composable
fun SwipeRevealRow(
    enabled: Boolean,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    deleteLabel: String,
    onDelete: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    if (!enabled) {
        content(Modifier)
        return
    }
    val width = with(LocalDensity.current) { DELETE_WIDTH.toPx() }
    val state = remember(width) {
        AnchoredDraggableState(
            initialValue = RevealValue.CLOSED,
            anchors = DraggableAnchors {
                RevealValue.CLOSED at 0f
                RevealValue.REVEALED at -width
            },
        )
    }
    val haptics = LocalHapticFeedback.current
    val openChange by rememberUpdatedState(onOpenChange)
    // A row that leaves the list or turns plain reports closed.
    DisposableEffect(state) { onDispose { openChange(false) } }
    LaunchedEffect(state) {
        var armed = true
        var wasOpen = false
        snapshotFlow { state.targetValue to state.settledValue }.collect { (target, settled) ->
            if (target == RevealValue.REVEALED && armed) {
                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                armed = false
            }
            if (settled == RevealValue.CLOSED && target == RevealValue.CLOSED) armed = true
            val isOpen = settled == RevealValue.REVEALED
            if (isOpen != wasOpen) {
                wasOpen = isOpen
                openChange(isOpen)
            }
        }
    }
    LaunchedEffect(open) {
        if (!open && state.settledValue != RevealValue.CLOSED) state.animateTo(RevealValue.CLOSED)
    }
    val delete by rememberUpdatedState(onDelete)
    val actions = Modifier.semantics {
        customActions = listOf(CustomAccessibilityAction(deleteLabel) { delete(); true })
    }
    val revealed = state.settledValue == RevealValue.REVEALED
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier
                    .width(DELETE_WIDTH)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.error)
                    .then(
                        if (revealed) {
                            Modifier.clickable(onClickLabel = deleteLabel) { delete() }
                        } else {
                            Modifier.clearAndSetSemantics {}
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onError,
                    )
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.onError,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(state.requireOffset().roundToInt(), 0) }
                .anchoredDraggable(
                    state = state,
                    orientation = Orientation.Horizontal,
                    flingBehavior = AnchoredDraggableDefaults.flingBehavior(
                        state,
                        positionalThreshold = { it * 0.5f },
                    ),
                )
                .background(MaterialTheme.colorScheme.surface),
        ) {
            content(actions)
        }
    }
}

/**
 * The one open row of a list, by id, and the row showing an inline delete confirm in place of
 * itself, if any. Opening a row closes the other and the confirm. While a row is open or a
 * confirm shows, a tap elsewhere in the list should first close it: [closeOr] runs an action
 * only when nothing was open, for taps that only close; others call [close] and then act.
 */
class RevealedRow {
    var openId: Long? by mutableStateOf(null)
        private set

    /** The row showing its inline confirm, or null. */
    var confirmingId: Long? by mutableStateOf(null)

    /**
     * Records [id] as open or closed after its row settles; a close for another row is ignored.
     * A close leaves [confirmingId] alone: a swiped row reports closed when its confirm replaces
     * it.
     */
    fun set(id: Long, open: Boolean) {
        if (open) {
            openId = id
            confirmingId = null
        } else if (openId == id) {
            openId = null
        }
    }

    /** Closes the open row and the confirm; returns true when either was showing. */
    fun close(): Boolean {
        val was = openId != null || confirmingId != null
        openId = null
        confirmingId = null
        return was
    }

    /** Closes the open row but leaves a confirm showing, for a scroll that reaches its buttons. */
    fun closeOpen() {
        openId = null
    }

    /** Closes the open row and the confirm, or runs [action] when neither was showing. */
    fun closeOr(action: () -> Unit) {
        if (!close()) action()
    }
}
