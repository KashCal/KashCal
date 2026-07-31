package org.onekash.kashcal.ui.components.category

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onekash.kashcal.R

/**
 * Compact, read-only tag pills for dense surfaces (event cards, week blocks).
 * Shows the first [maxVisible] tags as tiny filled pills and collapses the rest
 * into a "+N more" badge. Renders nothing when [categories] is empty, so
 * untagged events keep their exact prior layout.
 */
@Composable
fun CategoryPillRow(
    categories: List<String>,
    modifier: Modifier = Modifier,
    maxVisible: Int = 3,
) {
    // Drop blank names — a malformed server value like "CATEGORIES:foo,,bar"
    // can carry an empty element that would otherwise render as a blank chip.
    val names = categories.filter { it.isNotBlank() }
    if (names.isEmpty()) return

    val visible = names.take(maxVisible)
    val overflow = names.size - visible.size

    // Per-tag custom colors from the screen root; unprovided (previews/tests)
    // it's empty and every pill falls back to its hash color.
    val tagColors = LocalTagColors.current

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        visible.forEach { tag ->
            val tagColor = colorFor(tagColors, tag)
            val bg = Color(tagColor)
            val fg = Color(onColorFor(tagColor))
            val cd = stringResource(R.string.cd_tag, tag)
            Text(
                text = tag,
                color = fg,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .semantics { contentDescription = cd },
            )
        }
        if (overflow > 0) {
            Text(
                text = stringResource(R.string.tags_hint_n_more, overflow),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
            )
        }
    }
}
