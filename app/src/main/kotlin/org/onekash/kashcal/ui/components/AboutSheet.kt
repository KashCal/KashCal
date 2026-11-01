package org.onekash.kashcal.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.kash.KashArt
import org.onekash.kashcal.ui.components.kash.KashPose
import org.onekash.kashcal.util.ExternalLinks

/**
 * Shows About KashCal at full height: Kash with an armful of calendars, the app name and what it
 * is, rows that open the help pages, the source code and the donate page, and a footer with the
 * installed [versionName]. Swiping it down, tapping outside it or Back calls [onDismiss].
 *
 * [onOpenLink] opens a link with [ExternalLinks.openUrl] unless a caller passes its own, and
 * returns false when no app opens it; a line under the rows then says so until a link opens. The
 * rows rise in; with "Remove animations" on, nothing moves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(
    versionName: String,
    onDismiss: () -> Unit,
    motionEnabled: Boolean = rememberHeroMotionEnabled(),
    onOpenLink: (String) -> Boolean = LocalContext.current.let { context ->
        { url -> ExternalLinks.openUrl(context, url) }
    },
) {
    var linkFailed by rememberSaveable { mutableStateOf(false) }
    fun row(icon: ImageVector, @StringRes title: Int, @StringRes detail: Int, url: String) =
        SheetRowSpec(
            icon = icon,
            title = title,
            detail = detail,
            onClick = { linkFailed = !onOpenLink(url) },
            opensBrowser = true,
        )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.fillMaxHeight(),
    ) {
        CenteredSheetLayout(
            footer = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.about_built_in_austin),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.about_version, versionName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            },
        ) {
            KashArt(KashPose.ARMFUL, Modifier.size(160.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.about_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            RisingRows(
                rows = listOf(
                    row(
                        Icons.AutoMirrored.Outlined.HelpOutline,
                        R.string.about_help_title,
                        R.string.about_help_detail,
                        ExternalLinks.HELP,
                    ),
                    row(
                        Icons.Default.Code,
                        R.string.about_source_title,
                        R.string.about_source_detail,
                        ExternalLinks.SOURCE,
                    ),
                    row(
                        Icons.Default.Favorite,
                        R.string.about_support_title,
                        R.string.about_support_detail,
                        ExternalLinks.DONATE,
                    ),
                ),
                motionEnabled = motionEnabled,
            )
            // Always in the tree, empty until a link fails: TalkBack announces a live region when
            // its text changes, not when the node first appears.
            Text(
                text = if (linkFailed) stringResource(R.string.about_link_failed) else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
