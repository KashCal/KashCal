package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.error.ErrorActionCallback
import org.onekash.kashcal.error.ErrorPresentation

/**
 * Persistent error banner shown at top of screen.
 *
 * Usage:
 * ```
 * if (uiState.currentError is ErrorPresentation.Banner) {
 *     ErrorBanner(
 *         presentation = uiState.currentError as ErrorPresentation.Banner,
 *         onAction = { callback -> viewModel.handleErrorAction(callback) }
 *     )
 * }
 * ```
 *
 * Design:
 * - Color-coded by severity (Info, Warning, Error)
 * - Optional action button
 * - Edge-to-edge layout
 * - Icon indicates banner type
 *
 * Use cases:
 * - No accounts configured (Info with "Set Up" action)
 * - Offline mode (Warning)
 * - Sync blocked (Error with "Fix" action)
 */
@Composable
fun ErrorBanner(
    presentation: ErrorPresentation.Banner,
    onAction: (ErrorActionCallback) -> Unit,
    modifier: Modifier = Modifier
) {
    val message = if (presentation.messageArgs.isNotEmpty()) {
        stringResource(presentation.messageResId, *presentation.messageArgs)
    } else {
        stringResource(presentation.messageResId)
    }

    val (backgroundColor, contentColor, icon) = when (presentation.type) {
        ErrorPresentation.Banner.BannerType.Info -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Default.Info
        )
        ErrorPresentation.Banner.BannerType.Warning -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Default.Warning
        )
        ErrorPresentation.Banner.BannerType.Error -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Default.Error
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = backgroundColor,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = message,
                    color = contentColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (presentation.action != null) {
                TextButton(
                    onClick = { onAction(presentation.action.callback) }
                ) {
                    Text(
                        text = stringResource(presentation.action.labelResId),
                        color = contentColor,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/**
 * Simplified offline banner for common use case.
 *
 * Shows cloud-off icon with offline message.
 */
@Composable
fun OfflineBanner(
    message: String = "You're offline",
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text(
                        text = "Retry",
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/**
 * Generic status banner with customizable colors.
 *
 * Use ErrorBanner for error-specific banners with ErrorPresentation.
 * Use this for custom status messages.
 */
@Composable
fun StatusBanner(
    message: String,
    icon: ImageVector,
    backgroundColor: Color,
    contentColor: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = backgroundColor,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = message,
                    color = contentColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(
                        text = actionLabel,
                        color = contentColor,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
