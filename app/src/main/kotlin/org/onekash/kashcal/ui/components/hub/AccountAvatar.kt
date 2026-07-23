package org.onekash.kashcal.ui.components.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Circular avatar rendered in the top bar and the account hub's hero.
 *
 * Shows the user's up-to-2-letter monogram in the accent container colors. When
 * [initials] normalize to empty (never set, or cleared), it falls back to a
 * person glyph on the same tonal circle, so setting initials is a seamless swap
 * on an unchanged background.
 */
@Composable
fun AccountAvatar(
    initials: String,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    fontSize: TextUnit = 14.sp,
) {
    val monogram = normalizeInitials(initials)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            // A hairline outline gives the circle a defined edge against the page.
            // The tonal container fill barely separates from the surface for some
            // accent seeds (and collapses entirely for the white/black extremes),
            // so the border, not the fill, carries the shape.
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (monogram.isEmpty()) {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(size * 0.6f),
            )
        } else {
            Text(
                text = monogram,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                // The monogram is decorative; each caller (top-bar trigger, hub
                // hero) puts the real label on its own clickable, so keep the
                // letters out of the a11y tree rather than announcing "KC" too.
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}
