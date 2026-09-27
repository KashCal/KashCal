package org.onekash.kashcal.ui.components.pickers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import kotlin.math.absoluteValue
import android.graphics.Color as AndroidColor

// ============================================================================
// Color Utility Functions
// ============================================================================

/** Converts a hue (0-360, clamped) to an opaque ARGB color at full saturation and brightness. */
fun hueToArgb(hue: Float): Int {
    val hsv = floatArrayOf(hue.coerceIn(0f, 360f), 1f, 1f)
    return AndroidColor.HSVToColor(hsv)
}

/** Returns the hue (0-360) of an ARGB color, dropping its saturation and brightness. */
fun colorToHue(argb: Int): Float {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(argb, hsv)
    return hsv[0]
}

/** Formats an ARGB color as six uppercase hex digits without `#`, dropping alpha. */
fun argbToHex(argb: Int): String {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return String.format(java.util.Locale.ROOT, "%02X%02X%02X", r, g, b)
}

/**
 * Parses six hex digits, with or without `#` and in any case, to an opaque ARGB color, or null
 * if invalid.
 */
fun hexToArgb(hex: String): Int? {
    val cleaned = hex.removePrefix("#").uppercase()
    if (cleaned.length != 6) return null
    return try {
        val r = cleaned.substring(0, 2).toInt(16)
        val g = cleaned.substring(2, 4).toInt(16)
        val b = cleaned.substring(4, 6).toInt(16)
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    } catch (e: NumberFormatException) {
        null
    }
}

/** Returns whether [hex] is six hex digits, with or without a `#` prefix. */
fun isValidHex(hex: String): Boolean {
    val cleaned = hex.removePrefix("#")
    if (cleaned.length != 6) return false
    return cleaned.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
}

/**
 * Returns an English color name for a hue. The slider's accessibility text uses the localized
 * [hueToColorNameI18n]; no production code calls this one.
 */
fun hueToColorName(hue: Float): String = when {
    hue < 15 -> "Red"
    hue < 45 -> "Orange"
    hue < 75 -> "Yellow"
    hue < 150 -> "Green"
    hue < 210 -> "Cyan"
    hue < 270 -> "Blue"
    hue < 330 -> "Purple"
    else -> "Red"
}

@Composable
fun hueToColorNameI18n(hue: Float): String = when {
    hue < 15 -> stringResource(R.string.color_red)
    hue < 45 -> stringResource(R.string.color_orange)
    hue < 75 -> stringResource(R.string.color_yellow)
    hue < 150 -> stringResource(R.string.color_green)
    hue < 210 -> stringResource(R.string.color_cyan)
    hue < 270 -> stringResource(R.string.color_blue)
    hue < 330 -> stringResource(R.string.color_purple)
    else -> stringResource(R.string.color_red)
}

// ============================================================================
// Composables
// ============================================================================

/**
 * Shows a bottom sheet that picks a color with a hue slider and a hex field.
 *
 * The slider covers hues at full saturation and brightness, with haptics and a localized color
 * name for accessibility. The slider and the hex field update each other; a typed hex sets only
 * the hue, and Select commits the slider's full-saturation color.
 *
 * @param currentColor the initial color; only its hue seeds the slider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerSheet(
    sheetState: SheetState,
    currentColor: Int,
    onColorSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        ColorPickerSheetContent(
            currentColor = currentColor,
            onColorSelected = onColorSelected,
            onCancel = onDismiss
        )
    }
}

/**
 * Shows the body of [ColorPickerSheet], split out so tests avoid ModalBottomSheet animation
 * timing.
 */
@Composable
fun ColorPickerSheetContent(
    currentColor: Int,
    onColorSelected: (Int) -> Unit,
    onCancel: () -> Unit
) {
    // The hue drives the slider and the preview; the hex holds what the user types.
    var localHue by remember { mutableFloatStateOf(colorToHue(currentColor)) }
    var hexInput by remember { mutableStateOf(argbToHex(currentColor)) }
    var isHexValid by remember { mutableStateOf(true) }

    val previewColor = hueToArgb(localHue)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.dialog_choose_color),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(bottom = 24.dp)
        )

        ColorPreview(
            color = previewColor,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        HueSlider(
            hue = localHue,
            onHueChange = { newHue ->
                localHue = newHue
                hexInput = argbToHex(hueToArgb(newHue))
                isHexValid = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        )

        HexInputField(
            value = hexInput,
            isError = !isHexValid,
            onValueChange = { newHex ->
                hexInput = newHex.uppercase().filter { it.isLetterOrDigit() }.take(6)
                val parsed = hexToArgb(hexInput)
                if (parsed != null) {
                    localHue = colorToHue(parsed)
                    isHexValid = true
                } else {
                    isHexValid = hexInput.length < 6 // Not an error until 6 chars
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onColorSelected(previewColor) },
                enabled = isHexValid && hexInput.length == 6
            ) {
                Text(stringResource(R.string.action_select))
            }
        }
    }
}

/** Shows a swatch of the color Select would commit. */
@Composable
private fun ColorPreview(
    color: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(color))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp)
            )
    )
}

/**
 * Shows the rainbow hue slider (0-360), with a haptic on touch and every 30 degrees of drag.
 */
@Composable
private fun HueSlider(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var lastHapticHue by remember { mutableFloatStateOf(hue) }
    var sliderWidth by remember { mutableFloatStateOf(0f) }

    val colorName = hueToColorNameI18n(hue)

    // Resolved here because the semantics block isn't @Composable.
    val sliderDescription = stringResource(R.string.cd_color_slider, colorName)
    val sliderState = stringResource(R.string.cd_color_slider_state, colorName, hue.toInt())

    val rainbowColors = remember {
        listOf(
            Color.hsl(0f, 1f, 0.5f),     // Red
            Color.hsl(60f, 1f, 0.5f),    // Yellow
            Color.hsl(120f, 1f, 0.5f),   // Green
            Color.hsl(180f, 1f, 0.5f),   // Cyan
            Color.hsl(240f, 1f, 0.5f),   // Blue
            Color.hsl(300f, 1f, 0.5f),   // Magenta
            Color.hsl(360f, 1f, 0.5f)    // Red (wrap)
        )
    }

    fun updateHueFromPosition(x: Float) {
        if (sliderWidth <= 0) return
        val newHue = (x / sliderWidth * 360f).coerceIn(0f, 360f)
        onHueChange(newHue)

        if ((newHue - lastHapticHue).absoluteValue >= 30f) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            lastHapticHue = newHue
        }
    }

    Box(
        modifier = modifier
            .height(48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = sliderDescription
                stateDescription = sliderState
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    lastHapticHue = hue
                    updateHueFromPosition(offset.x)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        lastHapticHue = hue
                    },
                    onDragEnd = {},
                    onDragCancel = {},
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        updateHueFromPosition(change.position.x)
                    }
                )
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(16.dp))
        ) {
            sliderWidth = size.width

            drawRect(
                brush = Brush.horizontalGradient(rainbowColors),
                size = size
            )

            val thumbX = (hue / 360f) * size.width
            val thumbY = size.height / 2

            // Thumb outline
            drawCircle(
                color = Color.White,
                radius = 14.dp.toPx(),
                center = Offset(thumbX, thumbY)
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.2f),
                radius = 14.dp.toPx(),
                center = Offset(thumbX, thumbY),
                style = Stroke(width = 1.dp.toPx())
            )
            drawCircle(
                color = Color(hueToArgb(hue)),
                radius = 10.dp.toPx(),
                center = Offset(thumbX, thumbY)
            )
        }
    }
}

/** Shows the hex field, with an error hint when [isError]. */
@Composable
private fun HexInputField(
    value: String,
    isError: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(stringResource(R.string.label_hex_color)) },
        prefix = { Text("#") },
        isError = isError,
        supportingText = if (isError) {
            { Text(stringResource(R.string.label_enter_hex)) }
        } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        )
    )
}
