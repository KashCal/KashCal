package org.onekash.kashcal.ui.components.pickers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onekash.kashcal.ui.components.VerticalWheelPicker
import org.onekash.kashcal.ui.shared.Css3ColorEntry
import org.onekash.kashcal.ui.shared.EventColorPalette
import org.onekash.kashcal.ui.shared.HueFamily

/**
 * Shows two side-by-side wheels for browsing the 92-color CSS3 palette, under a large preview
 * swatch with the CSS3 name and hex.
 *
 * The left wheel picks a [HueFamily] (10 localized entries), the right a CSS3 color in that
 * family. CSS3 names are technical identifiers and deliberately not localized; the swatch and
 * the family label carry the meaning.
 *
 * The parent owns the state: each change calls [onColorSelected] with the new entry, and the
 * parent decides when to commit it.
 */
@Composable
fun ColorWheelPicker(
    selected: Css3ColorEntry,
    onColorSelected: (Css3ColorEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val family = selected.family
    val colorsInFamily = remember(family) { EventColorPalette.colorsInFamily(family) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Color(selected.argb))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = selected.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "#${argbToHex(selected.argb)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VerticalWheelPicker(
                items = HueFamily.entries,
                selectedItem = family,
                onItemSelected = { newFamily ->
                    if (newFamily != family) {
                        onColorSelected(
                            EventColorPalette.resolveColorForFamily(newFamily, selected.argb)
                        )
                    }
                },
                modifier = Modifier.weight(0.45f),
                visibleItems = 5,
                itemHeight = 40.dp,
                isCircular = true
            ) { item, isSelected ->
                Text(
                    text = stringResource(item.labelRes),
                    fontSize = if (isSelected) 16.sp else 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
            }

            VerticalWheelPicker(
                items = colorsInFamily,
                selectedItem = selected,
                onItemSelected = { entry ->
                    if (entry.argb != selected.argb) onColorSelected(entry)
                },
                modifier = Modifier.weight(0.55f),
                visibleItems = 5,
                itemHeight = 40.dp,
                isCircular = true
            ) { item, isSelected ->
                val familyLabel = stringResource(item.family.labelRes)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .semantics(mergeDescendants = true) {
                            // "Red, crimson": a CSS3 name alone is cryptic for TalkBack.
                            contentDescription = "$familyLabel, ${item.name}"
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color(item.argb))
                    )
                    Text(
                        text = item.name,
                        fontSize = if (isSelected) 14.sp else 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
