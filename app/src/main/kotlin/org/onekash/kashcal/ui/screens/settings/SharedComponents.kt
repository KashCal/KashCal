package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Section header for settings screens.
 * Displays a title with consistent styling matching Android 15/16 Settings.
 *
 * @param title The section title text
 */
@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 32.dp, top = 24.dp, bottom = 8.dp)
    )
}

/**
 * Standard card container for settings items.
 * Provides consistent styling matching Android 15/16 Settings.
 * Uses 16dp rounded corners and horizontal margin.
 *
 * @param modifier Optional modifier
 * @param content The card content
 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(content = content)
    }
}

/**
 * Color picker dot for subscription color selection.
 * Shows a circular color swatch with selection indicator.
 *
 * @param color The color value (as Int ARGB)
 * @param isSelected Whether this color is currently selected
 * @param onClick Callback when this color is clicked
 */
@Composable
fun ColorDot(
    color: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}

/**
 * Color picker grid for subscription calendars.
 * Displays all available subscription colors in a flow layout.
 *
 * @param selectedColor Currently selected color
 * @param onColorSelected Callback when a color is selected
 * @param colors List of available colors (defaults to SubscriptionColors.all)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPicker(
    selectedColor: Int,
    onColorSelected: (Int) -> Unit,
    colors: List<Int> = SubscriptionColors.all
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        colors.forEach { color ->
            ColorDot(
                color = color,
                isSelected = selectedColor == color,
                onClick = { onColorSelected(color) }
            )
        }
    }
}
