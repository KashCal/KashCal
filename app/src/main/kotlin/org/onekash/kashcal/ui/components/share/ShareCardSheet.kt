package org.onekash.kashcal.ui.components.share

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
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
 * Modal bottom sheet that previews and shares an event as a card image.
 *
 * Architecture (per 2026-05-31 docs research):
 *  - The on-screen preview composable IS the source of pixels for the share.
 *    `Modifier.drawWithContent { layer.record { drawContent() }; drawLayer(layer) }`
 *    captures the draw pass into a [GraphicsLayer] managed by [rememberGraphicsLayer].
 *  - Send tap calls [ShareCardRenderer.writePng] which converts the layer to a
 *    PNG via the FileProvider, then we fire `ACTION_SEND image/png` through
 *    [ShareChooser.createKashCalChooser] (excludes KashCal from its own outbound
 *    chooser per v23.7.69 pattern).
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
     * Optional .ics provider. When non-null and successful, the share intent
     * carries BOTH the rendered PNG and the .ics file (ACTION_SEND_MULTIPLE)
     * so recipients can tap the file to add the event to their calendar.
     * On null result or thrown failure, the sheet falls back to image-only.
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

    // The card lays out at 420×525 dp at the device's native density (4:5
    // aspect, big enough that the rotated silhouettes' corners fit inside
    // the capture bounds). ShareCardRenderer.writePng scales the
    // captured bitmap to a fixed 1080×1350 PNG via
    // scaleToShareCardOutput so output is constant across DPIs.
    //
    // Modifier.scale here is purely a visual transform on the preview —
    // it does NOT change the measured size of the composable, so the
    // GraphicsLayer continues to capture the full 420×525 dp content
    // regardless of how big the on-screen preview appears. Visual preview
    // is ~240 dp wide for a comfortable in-sheet size.
    val previewScale = 240f / 420f

    // Lock fontScale = 1f for the capture — the share-card is a designed
    // artifact, not a UI surface. With the user's system font scale
    // applied, large-text users get a 28sp title that grows enough to push
    // the attribution off the bottom of the 420dp content area. We keep
    // the device's native density (which kept the v23.7.72 layout fix
    // working) and only override fontScale.
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
            // No sheet title — the live preview below makes the sheet's
            // purpose obvious; an extra "Share as card" header would be
            // redundant chrome.

            // Live preview.
            //
            // ShareCardComposable measures itself at 420×525 dp at the
            // device's native density. The GraphicsLayer captures that
            // size in the device's pixels; ShareCardRenderer.writePng
            // scales to a fixed 1080×1350 px PNG.
            //
            // The host Box is sized to fit the 240-dp scaled preview
            // (240 × 5/4 = 300) so the visible preview is comfortable
            // in-sheet. The inner capture-host Box is given an EXPLICIT
            // .requiredSize(420, 525) so its measured bounds — and
            // therefore the GraphicsLayer.record clip rect — are exactly
            // 420×525 dp regardless of the outer 288-dp-wide parent
            // constraints. Without requiredSize the inner Box would
            // collapse to ~288×300 dp on a 360-dp-wide phone (the
            // outer container's max), the silhouettes would lose their
            // peek margin, and the captured PNG would clip the rotated
            // corners. .scale(previewScale) is purely a visual transform
            // applied AFTER the layer record, so the layer holds full-
            // size 420×525 content while the on-screen preview displays
            // at 240×300 dp.
            //
            // No Crossfade between styles: switching is instantaneous so
            // the captured layer is never an interpolated frame. Style
            // change is marked by haptic feedback on chip tap.
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

            // Style chips. No "Style" label — two emoji chips and a
            // selected state communicate "pick one of these" without
            // narration.
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

            // Generous breathing room above the primary action so the
            // chips don't crowd the Send button — the spec called this
            // out as needing more space.
            Spacer(Modifier.height(32.dp))

            // Send is the whole point of the sheet; treat it like the
            // primary action with a full-width filled button + icon.
            Button(
                onClick = {
                    if (isSending) return@Button
                    isSending = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        try {
                            val pngResult = renderer.writePng(context, fileNameHint, graphicsLayer)
                            pngResult.onSuccess { pngUri ->
                                // Try to attach a single-occurrence .ics
                                // alongside the image so recipients can
                                // tap-to-add. Failure is non-fatal —
                                // image-only fallback still works.
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
