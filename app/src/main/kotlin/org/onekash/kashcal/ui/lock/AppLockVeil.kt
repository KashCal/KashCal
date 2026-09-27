package org.onekash.kashcal.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onekash.kashcal.R

/**
 * Draws the full-screen branded veil shown while the app is locked.
 *
 * The host draws it above the calendar content; its opaque background hides everything
 * underneath and it swallows pointer input so the hidden UI can't be touched. The [onUnlock]
 * button is always present, even while the system prompt shows, so dismissing the prompt never
 * shifts the layout: the user sees the veil with a retry button.
 */
@Composable
fun AppLockVeil(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconBackground = colorResource(id = R.color.ic_launcher_background)
    Box(
        modifier = modifier
            .fillMaxSize()
            // Swallows taps and gestures so the veiled calendar can't be touched.
            .pointerInput(Unit) {}
            .background(
                // Plum shades darker than [iconBackground], so the icon tile stands out.
                Brush.radialGradient(
                    colors = listOf(Color(0xFF2C1932), Color(0xFF201225), Color(0xFF170C1A)),
                )
            )
            .systemBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(120.dp))

            // The app icon: launcher foreground on the launcher background, as a rounded square.
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(iconBackground),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(Modifier.height(22.dp))
            Text(
                text = stringResource(R.string.app_name),
                color = Color.White,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.62f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.app_lock_veil_locked),
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 13.sp,
                )
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onUnlock,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                shape = RoundedCornerShape(25.dp),
                modifier = Modifier
                    .width(230.dp)
                    .height(50.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = stringResource(R.string.app_lock_veil_unlock),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.app_lock_veil_footnote),
                color = Color.White.copy(alpha = 0.42f),
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}
