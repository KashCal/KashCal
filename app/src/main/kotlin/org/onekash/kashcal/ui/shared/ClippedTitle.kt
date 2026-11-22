package org.onekash.kashcal.ui.shared

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import java.text.Bidi

/**
 * Draws [text] on one line cut at the edge, with no ellipsis, so every letter that fits shows.
 * A title that fits sits at the layout's start edge. One that doesn't is set at its full width
 * and cut at the end of its own script's direction, so a right-to-left title in a left-to-right
 * layout, or the reverse, still shows its first letters.
 *
 * With [hideFromAccessibility] the line stays in the semantics tree but screen readers skip it,
 * for a caller that announces the text itself.
 */
@Composable
internal fun ClippedTitle(
    text: String,
    style: TextStyle,
    color: Color,
    textDecoration: TextDecoration?,
    modifier: Modifier = Modifier,
    hideFromAccessibility: Boolean = false,
) {
    val rightToLeftText = remember(text) { !Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight() }
    Layout(
        modifier = modifier.clipToBounds(),
        content = {
            Text(
                text = text,
                style = style,
                color = color,
                textDecoration = textDecoration,
                maxLines = 1,
                softWrap = false,
                modifier = if (hideFromAccessibility) Modifier.semantics { hideFromAccessibility() } else Modifier,
            )
        },
    ) { measurables, constraints ->
        val line = measurables.single()
        // At exactly its own width: unbounded, a right-to-left line is set at the far end of an
        // endless line and draws nowhere. Capped, since Constraints can't hold the width of a
        // pasted title tens of thousands of letters long, and only the start can show anyway.
        val natural = line.maxIntrinsicWidth(constraints.maxHeight).coerceAtMost(MAX_LINE_WIDTH_PX)
        val placeable = line.measure(Constraints(maxWidth = natural))
        val width = placeable.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, placeable.height) {
            when {
                placeable.width <= width -> placeable.placeRelative(0, 0)
                rightToLeftText -> placeable.place(width - placeable.width, 0)
                else -> placeable.place(0, 0)
            }
        }
    }
}

/** The widest a clipped line is laid out, well past any screen and inside what Constraints hold. */
private const val MAX_LINE_WIDTH_PX = 100_000
