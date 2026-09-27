package org.onekash.kashcal.ui.components.share

import android.content.ActivityNotFoundException
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.share.DateChipText
import org.onekash.kashcal.domain.share.ShareCardRenderer
import org.onekash.kashcal.domain.share.ShareCardStyle
import org.onekash.kashcal.domain.share.StripePosition
import org.onekash.kashcal.util.ShareCardIntentBuilder
import org.onekash.kashcal.util.ShareChooser

/**
 * Shows a bottom sheet that previews an event as a card image and shares it.
 *
 *  - The on-screen preview is the source of the shared pixels:
 *    `Modifier.drawWithContent { layer.record { drawContent() }; drawLayer(layer) }` records
 *    the draw pass into a [GraphicsLayer] from [rememberGraphicsLayer].
 *  - Send calls [ShareCardRenderer.writePng], which writes the layer as a PNG and returns its
 *    FileProvider URI, then starts the share (`ACTION_SEND` `image/png`, or
 *    `ACTION_SEND_MULTIPLE` with an .ics, see [icsUriProvider]) through
 *    [ShareChooser.createKashCalChooser], which keeps KashCal out of its own chooser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareCardSheet(
    title: String?,
    location: String?,
    timeRangeText: String?,
    dateChip: DateChipText,
    stripe: StripePosition,
    stripeLabels: List<String>,
    isAllDay: Boolean,
    isMultiDay: Boolean,
    multiDayRangeText: String?,
    selectedStyle: ShareCardStyle,
    onStyleChange: (ShareCardStyle) -> Unit,
    onDismiss: () -> Unit,
    renderer: ShareCardRenderer,
    fileNameHint: String,
    /**
     * Provides an .ics to attach. When it returns a URI, the share carries both the PNG and the
     * .ics (`ACTION_SEND_MULTIPLE`) so recipients can tap the file to add the event. A null
     * provider, a null result or a thrown failure shares the image only.
     */
    icsUriProvider: (suspend () -> Uri?)? = null,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val attribution = stringResource(R.string.share_as_card_attribution)
    val chooserTitle = stringResource(R.string.share_as_card_chooser_title)

    val graphicsLayer = rememberGraphicsLayer()
    val allDayLabel = stringResource(R.string.share_as_card_all_day)
    val sendFailedLabel = stringResource(R.string.share_as_card_share_failed)

    // The card lays out at 420 × 525 dp at the device's density; ShareCardRenderer.writePng
    // scales the capture to a fixed 1080 × 1350 PNG, so output is the same on every DPI.
    //
    // Modifier.scale only transforms the preview visually; the measured size is unchanged, so
    // the GraphicsLayer still captures the full 420 × 525 dp. The preview shows about 240 dp
    // wide.
    val previewScale = 240f / 420f

    // The card is a designed image, not UI, so fontScale is locked to 1 for the capture. With
    // a large system font scale the title grows enough to push the attribution off the card.
    // Only fontScale is overridden; the device's density stays.
    val deviceDensity = LocalDensity.current
    val captureDensity = remember(deviceDensity) {
        Density(density = deviceDensity.density, fontScale = 1f)
    }

    var isSending by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            // No sheet title: the live preview shows what the sheet is for.

            // Live preview. The host Box fits the scaled preview (240 × 5/4 = 300 dp tall). The
            // capture host has .requiredSize(420, 525) so its bounds, and so the recorded
            // layer's clip, are 420 × 525 dp whatever the parent allows; with plain sizing it
            // would shrink to about 288 × 300 dp on a 360 dp wide phone and the capture would
            // clip the silhouettes' corners. .scale(previewScale) applies after the record,
            // so the layer keeps full-size content while the preview shows at 240 × 300 dp.
            //
            // No Crossfade between styles: switching is instant so the captured layer is never
            // an interpolated frame. A chip tap gives haptic feedback instead.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp, vertical = 16.dp)
                    .height(300.dp),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalDensity provides captureDensity) {
                    Box(
                        modifier = Modifier
                            .requiredSize(width = 420.dp, height = 525.dp)
                            .scale(previewScale)
                            .drawWithContent {
                                graphicsLayer.record {
                                    this@drawWithContent.drawContent()
                                }
                                this@drawWithContent.drawLayer(graphicsLayer)
                            },
                    ) {
                        ShareCardComposable(
                            title = title,
                            location = location,
                            timeRangeText = timeRangeText,
                            style = selectedStyle,
                            dateChip = dateChip,
                            stripe = stripe,
                            stripeLabels = stripeLabels,
                            isAllDay = isAllDay,
                            isMultiDay = isMultiDay,
                            multiDayRangeText = multiDayRangeText,
                            attribution = attribution,
                            allDayLabel = allDayLabel,
                        )
                    }
                }
            }

            // Two emoji chips with a selected state need no "Style" label.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StyleChip(
                    label = stringResource(R.string.share_as_card_chip_standard),
                    emoji = "✨",
                    selected = selectedStyle == ShareCardStyle.Standard,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onStyleChange(ShareCardStyle.Standard)
                    },
                    modifier = Modifier.weight(1f),
                )
                StyleChip(
                    label = stringResource(R.string.share_as_card_chip_celebration),
                    emoji = "🎉",
                    selected = selectedStyle == ShareCardStyle.Celebration,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onStyleChange(ShareCardStyle.Celebration)
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            // Room above the primary action so the chips don't crowd Send.
            Spacer(Modifier.height(32.dp))

            // Send is the sheet's primary action: a full-width filled button with an icon.
            Button(
                onClick = {
                    if (isSending) return@Button
                    isSending = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        try {
                            val pngResult = renderer.writePng(context, fileNameHint, graphicsLayer)
                            pngResult.onSuccess { pngUri ->
                                // Attach the .ics when the provider gives one; a failure
                                // is logged and the image is shared alone.
                                val icsUri: Uri? = icsUriProvider?.let { provider ->
                                    runCatching { provider.invoke() }
                                        .onFailure { Log.w("ShareCardSheet", "ICS export failed", it) }
                                        .getOrNull()
                                }
                                val payload = ShareCardIntentBuilder.buildPayload(
                                    pngUri = pngUri,
                                    icsUri = icsUri,
                                )
                                val chooser = ShareChooser.createKashCalChooser(
                                    context, payload, chooserTitle,
                                )
                                try {
                                    context.startActivity(chooser)
                                    onDismiss()
                                } catch (e: ActivityNotFoundException) {
                                    Log.w("ShareCardSheet", "No activity to handle share", e)
                                    Toast.makeText(context, sendFailedLabel, Toast.LENGTH_SHORT).show()
                                }
                            }
                            pngResult.onFailure { e ->
                                Log.e("ShareCardSheet", "writePng failed", e)
                                Toast.makeText(context, sendFailedLabel, Toast.LENGTH_SHORT).show()
                                // Sheet stays open so the user can retry.
                            }
                        } finally {
                            isSending = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(52.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.share_as_card_send))
            }
        }
    }
}

@Composable
private fun StyleChip(
    label: String,
    emoji: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    } else {
        Color.Transparent
    }
    val labelColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }

    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.5.dp, borderColor),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = bg,
            contentColor = labelColor,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text(emoji, modifier = Modifier.padding(end = 6.dp))
        Text(
            text = label,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
