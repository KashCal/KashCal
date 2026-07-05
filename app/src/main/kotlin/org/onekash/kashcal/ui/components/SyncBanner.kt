package org.onekash.kashcal.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R

enum class SyncBannerState {
    Syncing,
    Preparing,
    Success,
    PartialError,
    Error
}

/**
 * Inline sync progress banner with state-aware indicator.
 *
 * - Syncing: animated three-dot wave
 * - Success: check icon
 * - Error: warning icon + errorContainer background
 *
 * Design follows Material 3 guidelines:
 * - primaryContainer for progress/success
 * - errorContainer for errors
 * - Edge-to-edge layout
 */
@Composable
fun SyncBanner(
    state: SyncBannerState = SyncBannerState.Syncing,
    errorDetail: String? = null,
    modifier: Modifier = Modifier
) {
    val isError = state == SyncBannerState.Error || state == SyncBannerState.PartialError
    val containerColor by animateColorAsState(
        targetValue = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.primaryContainer,
        animationSpec = tween(300),
        label = "bannerColor"
    )
    val contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onPrimaryContainer

    val message = when (state) {
        SyncBannerState.Syncing -> stringResource(R.string.sync_banner_syncing)
        SyncBannerState.Preparing -> stringResource(R.string.sync_banner_preparing)
        SyncBannerState.Success -> stringResource(R.string.success_sync_complete)
        SyncBannerState.PartialError -> stringResource(R.string.sync_banner_complete_errors)
        SyncBannerState.Error -> stringResource(R.string.sync_banner_failed, errorDetail ?: stringResource(R.string.error_unknown_error))
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            // Announce sync status changes (Syncing / complete / failed) to
            // TalkBack without the user having to focus the banner.
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = containerColor,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (state) {
                SyncBannerState.Syncing, SyncBannerState.Preparing -> {
                    SyncDots(color = contentColor)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                SyncBannerState.Success -> {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = contentColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                SyncBannerState.PartialError, SyncBannerState.Error -> {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = contentColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
            Text(
                text = message,
                color = contentColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * Three-dot wave animation. Each dot pulses in sequence
 * with a 150ms offset, creating a traveling wave effect.
 */
@Composable
private fun SyncDots(
    color: Color,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "syncDots")

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val dotAlpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 500,
                        delayMillis = index * 150,
                        easing = LinearEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .alpha(dotAlpha)
                    .background(color, CircleShape)
            )
        }
    }
}
