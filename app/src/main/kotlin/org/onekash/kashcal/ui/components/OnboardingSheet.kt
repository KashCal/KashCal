package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.kash.KashWaveEntrance

/**
 * Shows the first-launch "Where are your calendars?" sheet: Kash settling in and waving, three
 * rows that each open an existing Settings screen, and "Use offline calendar".
 *
 * Full height and opened expanded. Every way out is an answer: each row, the button, and swiping
 * the sheet down, tapping outside it or Back ([onDismiss]); the host records the sheet as
 * completed for all of them. With "Remove animations" on, nothing moves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingSheet(
    onICloud: () -> Unit,
    onCalDav: () -> Unit,
    onDeviceCalendars: () -> Unit,
    onUseOffline: () -> Unit,
    onDismiss: () -> Unit,
    motionEnabled: Boolean = rememberHeroMotionEnabled(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(),
    ) {
        CenteredSheetLayout(
            footer = {
                // Pinned like the event form's Save Event, tonal so the rows stay the main path.
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FilledTonalButton(
                        onClick = onUseOffline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_use_offline),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.onboarding_trust),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            },
        ) {
            KashWaveEntrance(motionEnabled = motionEnabled, modifier = Modifier.size(160.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp))
            RisingRows(
                rows = listOf(
                    SheetRowSpec(
                        Icons.Default.Cloud,
                        R.string.provider_icloud,
                        R.string.onboarding_icloud_detail,
                        onICloud,
                    ),
                    SheetRowSpec(
                        Icons.Default.Dns,
                        R.string.onboarding_caldav_title,
                        R.string.onboarding_caldav_detail,
                        onCalDav,
                    ),
                    SheetRowSpec(
                        Icons.Default.PhoneAndroid,
                        R.string.onboarding_device_title,
                        R.string.onboarding_phone_detail,
                        onDeviceCalendars,
                    ),
                ),
                motionEnabled = motionEnabled,
            )
        }
    }
}