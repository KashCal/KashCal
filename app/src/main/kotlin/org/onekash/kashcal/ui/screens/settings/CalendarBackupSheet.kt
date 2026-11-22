package org.onekash.kashcal.ui.screens.settings

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import org.onekash.kashcal.R
import org.onekash.kashcal.calendarbackup.BackupCalendarRow
import org.onekash.kashcal.calendarbackup.BackupNotificationState
import org.onekash.kashcal.calendarbackup.CalendarBackupNotifications
import org.onekash.kashcal.calendarbackup.CalendarBackupResult
import org.onekash.kashcal.calendarbackup.CalendarBackupSettings
import org.onekash.kashcal.calendarbackup.CalendarBackupUiModel
import org.onekash.kashcal.calendarbackup.FolderFailure
import org.onekash.kashcal.ui.components.InlineConfirm
import org.onekash.kashcal.ui.components.pickers.BackupIntervalPicker
import org.onekash.kashcal.ui.components.pickers.rememberBackupIntervalPickerState
import org.onekash.kashcal.ui.shared.formatBackupInterval
import org.onekash.kashcal.ui.viewmodels.CalendarBackupViewModel
import org.onekash.kashcal.ui.viewmodels.KeepAsk

/**
 * Opens the Automatic calendar backup sheet over [viewModel]. "Choose folder", "Change" and
 * "Choose again" open the system folder picker; [onOpenNotificationSettings] opens the system
 * notification settings, for the channel whose id it gets or for the app on null. Opening the
 * sheet reads again whether notifications are on; closing it forgets a refused pick and an
 * unanswered step-down.
 */
@Composable
fun CalendarBackupRoute(
    viewModel: CalendarBackupViewModel,
    onOpenNotificationSettings: (channelId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val model by viewModel.model.collectAsStateWithLifecycle()
    val pickError by viewModel.pickError.collectAsStateWithLifecycle()
    val keepAsk by viewModel.keepAsk.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.onFolderPicked(uri?.toString())
    }
    LaunchedEffect(Unit) { viewModel.refreshNotifications() }
    val current = model ?: return
    CalendarBackupSheet(
        model = current,
        pickError = pickError,
        keepAsk = keepAsk,
        timeText = { millis ->
            DateUtils.formatDateTime(
                context, millis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
            )
        },
        onChooseFolder = { picker.launch(null) },
        onSetEnabled = viewModel::setEnabled,
        onSetInterval = viewModel::setInterval,
        onRequestKeep = viewModel::requestKeep,
        onConfirmKeep = viewModel::confirmKeep,
        onCancelKeep = viewModel::cancelKeep,
        onBackUpNow = viewModel::backUpNow,
        onOpenNotificationSettings = onOpenNotificationSettings,
        onDismissNotificationsHint = viewModel::dismissNotificationsHint,
        onDismiss = {
            viewModel.clearTransient()
            onDismiss()
        },
    )
}

/**
 * Returns the Settings row subtitle for [model]: "Off", the folder problem, how many calendars
 * failed, or how often and where; the Boolean is true for a problem or a failure, which show in
 * the error color.
 */
@Composable
fun calendarBackupRowSubtitle(model: CalendarBackupUiModel?): Pair<String, Boolean> {
    val settings = model?.settings
    val failed = model?.let(::failedCalendars) ?: 0
    return when {
        settings == null || !settings.isActive ->
            stringResource(R.string.calendar_backup_row_off) to false
        model.status.folderProblem != null -> stringResource(R.string.calendar_backup_row_problem) to true
        failed > 0 -> pluralStringResource(R.plurals.calendar_backup_row_calendars_failed, failed, failed) to true
        else -> stringResource(
            R.string.calendar_backup_row_on,
            formatBackupInterval(settings.intervalHours, LocalResources.current),
            settings.folderName ?: stringResource(R.string.calendar_backup_folder_unnamed),
        ) to false
    }
}

// Counts the shown calendars whose last backup failed, so a deleted calendar's old result is
// left out of both the sheet and the Settings row.
private fun failedCalendars(model: CalendarBackupUiModel) =
    model.calendars.count { it.result?.kind == CalendarBackupResult.Kind.FAILED }

/** The test tags of the sheet's on/off row and of the switch in it. */
internal const val SWITCH_ROW_TAG = "calendar_backup_switch_row"
internal const val SWITCH_TAG = "calendar_backup_switch"

/**
 * Shows automatic backup's setup at full height. Without a folder: what it does, Choose folder
 * and the folder hint. With one: the switch (subtitle from [switchSubtitle]) with Back up now
 * under it while on and the notifications-off hint, then the
 * folder with its problem and Choose again under it. Then Frequency (expanding
 * [BackupIntervalPicker] in place) and Copies to keep (a step down that deletes copies asks
 * through [InlineConfirm]), both settable before a folder is chosen, and one line per offline
 * calendar. The footer says the files are overwritten and how to restore from them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarBackupSheet(
    model: CalendarBackupUiModel,
    pickError: FolderFailure?,
    keepAsk: KeepAsk?,
    timeText: (Long) -> String,
    onChooseFolder: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetInterval: (Int) -> Unit,
    onRequestKeep: (Int) -> Unit,
    onConfirmKeep: () -> Unit,
    onCancelKeep: () -> Unit,
    onBackUpNow: () -> Unit,
    onOpenNotificationSettings: (channelId: String?) -> Unit,
    onDismissNotificationsHint: () -> Unit,
    onDismiss: () -> Unit,
) {
    val settings = model.settings
    val folderName = settings.folderName ?: stringResource(R.string.calendar_backup_folder_unnamed)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.calendar_backup_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).semantics { heading() },
            )
            if (settings.folderUri == null) {
                FirstTimeIntro(onChooseFolder)
            } else {
                SwitchRow(
                    enabled = settings.enabled,
                    subtitle = switchSubtitle(model, folderName, timeText),
                    onSetEnabled = onSetEnabled,
                )
                if (settings.enabled) {
                    FilledTonalButton(
                        onClick = onBackUpNow,
                        enabled = !model.running,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                    ) {
                        Text(stringResource(R.string.calendar_backup_now))
                    }
                }
                model.notificationsHint?.let { hint ->
                    NotificationsHint(
                        hint = hint,
                        onOpenSettings = {
                            onOpenNotificationSettings(
                                if (hint == BackupNotificationState.CHANNEL_OFF) CalendarBackupNotifications.CHANNEL_ID else null
                            )
                        },
                        onNotNow = onDismissNotificationsHint,
                    )
                }
                FolderRow(folderName = folderName, onChange = onChooseFolder)
                val problem = model.status.folderProblem
                if (problem != null && settings.enabled) {
                    FolderProblem(problem = problem, folderName = folderName, onChooseAgain = onChooseFolder)
                }
            }
            pickError?.let { PickErrorLine(it) }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            FrequencyRow(intervalHours = settings.intervalHours, onSetInterval = onSetInterval)
            CopiesRow(
                keep = settings.keepCount,
                keepAsk = keepAsk,
                onRequestKeep = onRequestKeep,
                onConfirmKeep = onConfirmKeep,
                onCancelKeep = onCancelKeep,
            )
            if (settings.folderUri != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = stringResource(R.string.calendar_backup_calendars),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp)
                        .semantics { heading() },
                )
                model.calendars.forEach { CalendarLine(it, showFailure = settings.enabled) }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            Text(
                text = stringResource(R.string.calendar_backup_footer_overwrites),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun FirstTimeIntro(onChooseFolder: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Text(
            text = stringResource(R.string.calendar_backup_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Button(onClick = onChooseFolder) { Text(stringResource(R.string.calendar_backup_choose_folder)) }
        Text(
            text = stringResource(R.string.calendar_backup_choose_folder_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
    }
}

/**
 * Returns the switch row's subtitle: where the files stay while off, "Backing up…" during a run,
 * otherwise the last backup and how many calendars failed in it. Null before the first backup and
 * while the folder has a problem: a run that couldn't use the folder saved nothing.
 */
@Composable
private fun switchSubtitle(model: CalendarBackupUiModel, folderName: String, timeText: (Long) -> String): String? {
    if (!model.settings.enabled) return stringResource(R.string.calendar_backup_off_files_stay, folderName)
    if (model.running) return stringResource(R.string.calendar_backup_running)
    if (model.status.folderProblem != null) return null
    val checked = model.status.lastCheckedAt ?: return null
    val failed = failedCalendars(model)
    return if (failed > 0) {
        pluralStringResource(R.plurals.calendar_backup_status_failed, failed, timeText(checked), failed)
    } else {
        stringResource(R.string.calendar_backup_status, timeText(checked))
    }
}

@Composable
private fun SwitchRow(enabled: Boolean, subtitle: String?, onSetEnabled: (Boolean) -> Unit) {
    // The visible label is only On or Off, so TalkBack reads what the switch turns on and the
    // subtitle instead; a content description replaces the row's text, so it carries both.
    val name = listOfNotNull(stringResource(R.string.calendar_backup_title), subtitle).joinToString(", ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag(SWITCH_ROW_TAG)
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onSetEnabled)
            .semantics { contentDescription = name }
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                stringResource(if (enabled) R.string.settings_on else R.string.settings_off),
                style = MaterialTheme.typography.bodyLarge,
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = enabled, onCheckedChange = null, modifier = Modifier.testTag(SWITCH_TAG))
    }
}

@Composable
private fun NotificationsHint(hint: BackupNotificationState, onOpenSettings: () -> Unit, onNotNow: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                text = stringResource(
                    if (hint == BackupNotificationState.CHANNEL_OFF) {
                        R.string.calendar_backup_notifications_channel_off
                    } else {
                        R.string.calendar_backup_notifications_app_off
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(end = 8.dp),
            )
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onNotNow) { Text(stringResource(R.string.calendar_backup_not_now)) }
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.calendar_backup_open_settings)) }
            }
        }
    }
}

@Composable
private fun FolderRow(folderName: String, onChange: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.calendar_backup_folder), style = MaterialTheme.typography.bodyLarge)
            Text(
                folderName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onChange) { Text(stringResource(R.string.calendar_backup_change)) }
    }
}

@Composable
private fun FolderProblem(problem: FolderFailure, folderName: String, onChooseAgain: () -> Unit) {
    val text = if (problem == FolderFailure.UNSUPPORTED) {
        stringResource(R.string.calendar_backup_error_unsupported, folderName)
    } else {
        stringResource(R.string.calendar_backup_folder_unreachable, folderName)
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onChooseAgain) { Text(stringResource(R.string.calendar_backup_choose_again)) }
        }
    }
}

@Composable
private fun PickErrorLine(error: FolderFailure) {
    Text(
        text = stringResource(
            if (error == FolderFailure.UNSUPPORTED) {
                R.string.calendar_backup_pick_cannot_create
            } else {
                R.string.calendar_backup_pick_refused
            }
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun FrequencyRow(intervalHours: Int, onSetInterval: (Int) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.calendar_backup_frequency)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = { expanded = !expanded })
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(4.dp))
        SettingsInfoButton(SettingsRowInfo(title, stringResource(R.string.calendar_backup_frequency_info)))
        Spacer(Modifier.weight(1f))
        Text(
            formatBackupInterval(intervalHours, LocalResources.current),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (expanded) {
        val state = rememberBackupIntervalPickerState(intervalHours)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            BackupIntervalPicker(
                state = state,
                onDone = { hours ->
                    onSetInterval(hours)
                    expanded = false
                },
            )
        }
    }
}

/**
 * The Copies to keep stepper. Plus applies at once; minus goes through [onRequestKeep], which
 * asks first when existing copies would be deleted. TalkBack's adjust action takes the same path.
 */
@Composable
private fun CopiesRow(
    keep: Int,
    keepAsk: KeepAsk?,
    onRequestKeep: (Int) -> Unit,
    onConfirmKeep: () -> Unit,
    onCancelKeep: () -> Unit,
) {
    val title = stringResource(R.string.calendar_backup_copies)
    val stateText = pluralStringResource(R.plurals.calendar_backup_copies_state, keep, keep)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 24.dp, end = 12.dp)
            .semantics(mergeDescendants = true) {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    keep.toFloat(),
                    CalendarBackupSettings.MIN_KEEP.toFloat()..CalendarBackupSettings.MAX_KEEP.toFloat(),
                    CalendarBackupSettings.MAX_KEEP - CalendarBackupSettings.MIN_KEEP - 1,
                )
                stateDescription = stateText
                setProgress { target ->
                    onRequestKeep(target.roundToInt())
                    true
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(4.dp))
        SettingsInfoButton(SettingsRowInfo(title, stringResource(R.string.calendar_backup_copies_explained)))
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { onRequestKeep(keep - 1) }, enabled = keep > CalendarBackupSettings.MIN_KEEP) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.calendar_backup_copies_fewer))
        }
        Text(
            "$keep",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(28.dp),
        )
        IconButton(onClick = { onRequestKeep(keep + 1) }, enabled = keep < CalendarBackupSettings.MAX_KEEP) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.calendar_backup_copies_more))
        }
    }
    if (keepAsk != null) {
        InlineConfirm(
            text = keepAsk.removed?.let { pluralStringResource(R.plurals.calendar_backup_deletes, it, it) }
                ?: stringResource(R.string.calendar_backup_deletes_unknown),
            confirmLabel = stringResource(R.string.action_delete),
            onCancel = onCancelKeep,
            onConfirm = onConfirmKeep,
            modifier = Modifier.padding(start = 24.dp, end = 16.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun CalendarLine(row: BackupCalendarRow, showFailure: Boolean) {
    // While off there is no next backup to try again at, so a failure isn't shown.
    val result = row.result?.takeUnless { !showFailure && it.kind == CalendarBackupResult.Kind.FAILED }
    val failed = result?.kind == CalendarBackupResult.Kind.FAILED
    val line = when (result?.kind) {
        null, CalendarBackupResult.Kind.UPDATED -> row.fileName
        CalendarBackupResult.Kind.EMPTY -> stringResource(R.string.calendar_backup_calendar_nothing_saved, row.fileName)
        CalendarBackupResult.Kind.FAILED -> stringResource(R.string.calendar_backup_calendar_save_failed, row.fileName)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(14.dp)
                .clip(CircleShape)
                .background(Color(row.color))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
