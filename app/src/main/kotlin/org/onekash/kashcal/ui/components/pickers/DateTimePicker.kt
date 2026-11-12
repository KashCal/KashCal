package org.onekash.kashcal.ui.components.pickers

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.EventFormState
import org.onekash.kashcal.ui.components.FormDateFields
import org.onekash.kashcal.ui.components.WheelFade
import org.onekash.kashcal.ui.components.WheelTimePicker
import org.onekash.kashcal.ui.shared.ALL_DAY_LENGTH_CHIPS_DAYS
import org.onekash.kashcal.ui.shared.TIMED_LENGTH_CHIPS_MINUTES
import org.onekash.kashcal.ui.shared.formatDuration
import org.onekash.kashcal.ui.shared.formatDurationShort
import org.onekash.kashcal.ui.shared.stepSwipe
import org.onekash.kashcal.ui.util.CalendarSpan
import org.onekash.kashcal.ui.util.SelectedDay
import org.onekash.kashcal.util.DateTimeUtils
import org.onekash.kashcal.util.TimezoneUtils
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Calendar as JavaCalendar
import kotlin.math.floor

/** Names which date of a range, start or end, the user is editing. */
enum class DateSelectionMode {
    START,
    END
}

/** Names the form row, Starts or Ends, that opened [DateTimeSheet]; [NONE] while it is closed. */
enum class ActiveDateTimeSheet {
    NONE,
    START,
    END
}

/**
 * Shows the selected date and time as a read-only outlined field; a tap calls [onClick].
 *
 * @param label "Starts" or "Ends", floating on the border like an OutlinedTextField label.
 * @param hour 0-23.
 * @param isAllDay hides the time.
 * @param isError shows the error border and strikes through the text.
 * @param errorMessage shown below the field.
 * @param timezone shown as an abbreviation badge only when timed and not the device zone.
 */
@Deprecated("Use DateTimeDisplayRow instead", level = DeprecationLevel.WARNING)
@Composable
fun DateTimePickerCard(
    label: String,
    dateMillis: Long,
    hour: Int,
    minute: Int,
    isAllDay: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    errorMessage: String? = null,
    timezone: String? = null,
    timePattern: String = "h:mm a"
) {
    val focusManager = LocalFocusManager.current

    val deviceTimezone = TimezoneUtils.getDeviceTimezone()
    val timezoneAbbrev = if (!isAllDay && timezone != null && timezone != deviceTimezone) {
        TimezoneUtils.getAbbreviation(timezone)
    } else null

    val displayText = buildString {
        append(formatDateForPicker(dateMillis, isAllDay))
        if (!isAllDay) {
            append("   ")
            append(DateTimeUtils.formatTime(hour, minute, timePattern))
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                Icon(Icons.Default.ExpandMore, contentDescription = stringResource(R.string.cd_select_label, label))
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = isError,
            supportingText = if (errorMessage != null) {
                { Text(errorMessage, color = MaterialTheme.colorScheme.error) }
            } else null,
            textStyle = androidx.compose.ui.text.TextStyle(
                textDecoration = if (isError) TextDecoration.LineThrough else TextDecoration.None
            )
        )

        // Floats on the top-right border, mirroring the label on the left.
        if (timezoneAbbrev != null) {
            Text(
                text = timezoneAbbrev,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-12).dp, y = 0.dp) // Match M3 floating label (centered on border)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 4.dp)
            )
        }

        // A readOnly OutlinedTextField still shows a cursor on tap, so an overlay takes the tap.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    focusManager.clearFocus()
                    onClick()
                }
        )
    }
}

/**
 * Formats a form date in the local zone, since form state is in local time.
 *
 * For all-day events the form holds local midnight (converted from UTC by
 * `DateTimeUtils.utcMidnightToLocalDate`), so the formatter must not get isAllDay = true: it
 * would read that local timestamp as UTC and show the previous day east of UTC (at UTC+11,
 * Jan 5 for Jan 6).
 */
@Suppress("UNUSED_PARAMETER") // isAllDay kept for API compatibility
private fun formatDateForPicker(dateMillis: Long, isAllDay: Boolean): String {
    return DateTimeUtils.formatEventDate(dateMillis, isAllDay = false)
}

@Composable
fun DateTimeDisplayRow(
    startDateMillis: Long,
    startHour: Int,
    startMinute: Int,
    endDateMillis: Long,
    endHour: Int,
    endMinute: Int,
    isAllDay: Boolean,
    onAllDayToggle: (Boolean) -> Unit,
    onStartClick: () -> Unit,
    onEndClick: () -> Unit,
    modifier: Modifier = Modifier,
    isStartError: Boolean = false,
    isEndError: Boolean = false,
    startErrorMessage: String? = null,
    endErrorMessage: String? = null,
    timezone: String? = null,
    timePattern: String = "h:mm a"
) {
    val focusManager = LocalFocusManager.current
    val deviceTimezone = TimezoneUtils.getDeviceTimezone()
    val timezoneAbbrev = if (!isAllDay && timezone != null && timezone != deviceTimezone) {
        TimezoneUtils.getAbbreviation(timezone)
    } else null

    Column(modifier = modifier) {
        // A Switch reserves a 48dp interactive box, which makes this row taller than the
        // single-line rows and widens the gap after the divider. The switch opts out of that
        // minimum so the row sits at the text height.
        EventFormRow(
            icon = Icons.Default.Schedule,
            iconContentDescription = stringResource(R.string.label_all_day),
            onToggle = { onAllDayToggle(!isAllDay) }
        ) {
            Text(
                stringResource(R.string.label_all_day),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Switch(
                    checked = isAllDay,
                    onCheckedChange = onAllDayToggle
                )
            }
        }

        // Start date and time, indented past the icon column to align with the row text.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    focusManager.clearFocus()
                    onStartClick()
                }
                .padding(start = 52.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val startDisplay = buildString {
                append(formatDateForPicker(startDateMillis, isAllDay))
                if (!isAllDay) {
                    append("  ")
                    append(DateTimeUtils.formatTime(startHour, startMinute, timePattern))
                }
                if (timezoneAbbrev != null) append("  $timezoneAbbrev")
            }
            Text(
                startDisplay,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isStartError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (isStartError) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 1,
                overflow = TextOverflow.Visible
            )
        }

        if (startErrorMessage != null) {
            Text(
                startErrorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 52.dp, top = 2.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    focusManager.clearFocus()
                    onEndClick()
                }
                .padding(start = 52.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val endDisplay = buildString {
                append(formatDateForPicker(endDateMillis, isAllDay))
                if (!isAllDay) {
                    append("  ")
                    append(DateTimeUtils.formatTime(endHour, endMinute, timePattern))
                }
                if (timezoneAbbrev != null) append("  $timezoneAbbrev")
            }
            Text(
                endDisplay,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isEndError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (isEndError) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 1,
                overflow = TextOverflow.Visible
            )
        }

        if (endErrorMessage != null) {
            Text(
                endErrorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 52.dp, top = 2.dp)
            )
        }
    }
}

/**
 * Shows the event's start and end in one full-height sheet: a Starts | Ends switch at the top,
 * then the month grid and, for a timed event, the time wheel, with length chips when there is
 * room. The edits apply the form's date rules as they happen ([DateTimeSheetState]).
 *
 * Only the strip with the handle and the switch drags the sheet; the grid, wheel and chips keep
 * their vertical drags, so a slipped month swipe or wheel flick can't close it. Swiping it
 * closed, Back and Done all hand the edited start and end to [onCommit] once; the form's own
 * Cancel is what discards them. Done first closes an open month/year wheel.
 *
 * @param initial the form whose start and end are edited; only its date fields are read.
 * @param openingSide the end the sheet opens on, from the row the user tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateTimeSheet(
    initial: EventFormState,
    openingSide: DateSelectionMode,
    defaultDurationMinutes: Int,
    onCommit: (FormDateFields) -> Unit,
    use24Hour: Boolean = false,
    firstDayOfWeek: Int = JavaCalendar.SUNDAY,
) {
    var sheet by remember { mutableStateOf(DateTimeSheetState.open(initial, openingSide, defaultDurationMinutes)) }
    var showMonthYearPicker by remember { mutableStateOf(false) }
    var showTimeDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val timePattern = if (use24Hour) "HH:mm" else "h:mm a"

    // Every change is applied to the current state, never to one a callback captured, so a
    // handler that outlives a recomposition (a fling's last report) can't bring an older state
    // back.
    val update: ((DateTimeSheetState) -> DateTimeSheetState) -> Unit = { change -> sheet = change(sheet) }
    val commit: () -> Unit = {
        val (committed, fields) = sheet.commit()
        sheet = committed
        if (fields != null) onCommit(fields)
    }
    val onDoneClick: () -> Unit = {
        if (showMonthYearPicker) showMonthYearPicker = false else commit()
    }

    ModalBottomSheet(
        onDismissRequest = commit,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(),
        dragHandle = null,
        // The top inset keeps the full-height sheet below the status bar; the Done bar pads
        // itself for the navigation bar so that band also takes its drags.
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Top) },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SheetTopStrip(
                sheet = sheet,
                timePattern = timePattern,
                onFlip = { side -> update { it.flip(side) } },
                onClose = commit,
            )

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val middleHeight = maxHeight
                val density = LocalDensity.current
                val layout = dateTimeSheetLayout(
                    middleHeight = middleHeight.value,
                    minRowHeight = density.sheetWheelRowMinHeight().value,
                    hintLineHeight = density.sheetHintLineHeight().value,
                    isAllDay = sheet.isAllDay,
                )
                // The scroll container spans the full width so every drag in the middle, gaps and
                // edges included, starts here; [KeepDragsFromSheet] takes what it leaves, so the
                // sheet doesn't move.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(KeepDragsFromSheet)
                        .verticalScroll(rememberScrollState())
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = middleHeight)
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        DateTimeSheetGrid(
                            editedDate = sheet.editedDate,
                            displayedMonth = sheet.displayedMonth,
                            range = if (sheet.isAllDay) {
                                sheet.dateOf(DateSelectionMode.START)..sheet.dateOf(DateSelectionMode.END)
                            } else null,
                            weekdayRowHeight = layout.weekdayRowHeight.dp,
                            firstDayOfWeek = firstDayOfWeek,
                            showMonthYearPicker = showMonthYearPicker,
                            onShowMonthYearPickerChange = { showMonthYearPicker = it },
                            onSelectDate = { date -> update { it.selectDate(it.side, date) } },
                            onMonth = { month -> update { it.setDisplayedMonth(month) } },
                        )
                        if (!sheet.isAllDay) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = (layout.dividerHeight.dp - 1.dp) / 2)
                            )
                            // Remounted per side so a fling still running on one end's wheel
                            // can't write into the other end after a flip.
                            key(sheet.side) {
                                val boundSide = sheet.side
                                val (hour, minute) = sheet.timeOf(boundSide)
                                SheetTimeWheel(
                                    hour = hour,
                                    minute = minute,
                                    layout = layout,
                                    use24Hour = use24Hour,
                                    timePattern = timePattern,
                                    showTimeDialog = showTimeDialog,
                                    onShowTimeDialog = { showTimeDialog = it },
                                    onTime = { h, m -> update { it.selectTime(boundSide, h, m) } },
                                )
                            }
                        } else {
                            SheetEndHint(show = sheet.showEndHint, height = layout.hintLineHeight.dp)
                        }
                        if (layout.showChips) {
                            SheetLengthChips(sheet = sheet, onChip = { length -> update { it.applyChip(length) } })
                        }
                    }
                }
            }

            // The Done bar takes its own vertical drags, the navigation bar band included, so only
            // the top strip moves the sheet.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .draggable(rememberDraggableState { }, Orientation.Vertical)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Button(
                    onClick = onDoneClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.action_done), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Returns the time wheel's shortest row: the 24sp line the selected time needs plus 8dp, so the
 * rows grow with the system font (non-linearly on Android 14 and later).
 */
internal fun Density.sheetWheelRowMinHeight(): Dp = 24.sp.toDp() + 8.dp

/**
 * Returns the height reserved for the all-day end hint: one 16sp line (the bodySmall line
 * height), never under 24dp, so it grows with the system font like the wheel's rows.
 */
internal fun Density.sheetHintLineHeight(): Dp = maxOf(24.dp, 16.sp.toDp())

/**
 * The sheet wheel's fades for 3 and 5 rows: lighter than [WheelFade.Default], so the rows above
 * and below the selection stay readable.
 */
private val SheetWheelFadeThreeRows = WheelFade(edgeRows = 0.3f, nearAlpha = 0.75f, farAlpha = 0.5f)
private val SheetWheelFadeFiveRows = WheelFade(edgeRows = 0.6f, nearAlpha = 0.75f, farAlpha = 0.5f)

/**
 * Consumes whatever drag or fling the sheet's middle leaves, so none reaches the bottom sheet's
 * own nested-scroll handler, which would move the sheet. The sheet then moves only from the top
 * strip, outside the middle.
 */
private object KeepDragsFromSheet : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/**
 * Shows the drag handle and the Starts | Ends switch. Each half is drawn [sheetSwitchHalfHeight]
 * tall and takes taps 16dp higher, into the handle strip, so it is at least a 56dp target; a drag
 * that starts there still moves the sheet. TalkBack users close the sheet with the strip's close
 * action.
 */
@Composable
private fun SheetTopStrip(
    sheet: DateTimeSheetState,
    timePattern: String,
    onFlip: (DateSelectionMode) -> Unit,
    onClose: () -> Unit,
) {
    val closeLabel = stringResource(R.string.action_close)
    val startLabel = stringResource(R.string.label_starts)
    val endLabel = stringResource(R.string.label_ends)
    val startValue = sideValue(sheet, DateSelectionMode.START, timePattern)
    val endValue = sideValue(sheet, DateSelectionMode.END, timePattern)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                dismiss(label = closeLabel) {
                    onClose()
                    true
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(width = 32.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
            )
        }
        Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            SheetSwitch(
                side = sheet.side,
                startLabel = startLabel,
                startValue = startValue,
                endLabel = endLabel,
                endValue = endValue,
                onFlip = onFlip,
            )
        }
    }
}

/**
 * Draws the two halves of the Starts | Ends switch the same way, each on one line: the label in
 * the small label style, then the value, which shrinks its text down to 9sp to fit its half and
 * then ellipsizes rather than wrap. The halves are [sheetSwitchHalfHeight] tall.
 */
@Composable
internal fun SheetSwitch(
    side: DateSelectionMode,
    startLabel: String,
    startValue: String,
    endLabel: String,
    endValue: String,
    onFlip: (DateSelectionMode) -> Unit,
) {
    val valueStyle = MaterialTheme.typography.labelLarge
    val halfHeight = LocalDensity.current.sheetSwitchHalfHeight(valueStyle.lineHeight)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .selectableGroup()
    ) {
        listOf(
            Triple(DateSelectionMode.START, startLabel, startValue),
            Triple(DateSelectionMode.END, endLabel, endValue),
        ).forEach { (half, label, value) ->
            val selected = side == half
            val shape = if (half == DateSelectionMode.START) {
                RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
            } else {
                RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
            }
            val textColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                modifier = Modifier
                    .weight(1f)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onFlip(half) })
                    .padding(top = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(halfHeight)
                        .clip(shape)
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.8f),
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        value,
                        modifier = Modifier.weight(1f, fill = false),
                        style = valueStyle,
                        color = textColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 9.sp,
                            maxFontSize = valueStyle.fontSize,
                            stepSize = 0.5.sp
                        )
                    )
                }
            }
        }
    }
}

/**
 * Returns a switch half's drawn height: the value's one line plus 8dp, never under 40dp. It
 * follows the font setting only, not the text, so the strip keeps its height and the grid below it
 * doesn't move while editing (a date added when the end moves to another day).
 */
internal fun Density.sheetSwitchHalfHeight(valueLineHeight: TextUnit): Dp =
    maxOf(40.dp, valueLineHeight.toDp() + 8.dp)

/**
 * Returns a switch half's value: the date for an all-day event; for a timed one the time, with a
 * short date only when the start and end fall on different days, so the usual same-day half
 * stays short.
 */
@Composable
private fun sideValue(sheet: DateTimeSheetState, side: DateSelectionMode, timePattern: String): String {
    val locale = LocalLocale.current.platformLocale
    val longDate = remember(locale) { DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("MMMEd", locale), locale) }
    val shortDate = remember(locale) { DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("MMMd", locale), locale) }
    val day = sheet.dateOf(side)
    if (sheet.isAllDay) return day.format(longDate)
    val (hour, minute) = sheet.timeOf(side)
    val time = DateTimeUtils.formatTime(hour, minute, timePattern)
    val sameDay = sheet.dateOf(DateSelectionMode.START) == sheet.dateOf(DateSelectionMode.END)
    return if (sameDay) time else "${day.format(shortDate)} $time"
}

/**
 * Shows the all-day end hint in its reserved line, empty but the same height when [show] is off,
 * so nothing below it moves; TalkBack announces it as it appears.
 */
@Composable
private fun SheetEndHint(show: Boolean, height: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (show) {
            Text(
                text = stringResource(R.string.sheet_hint_change_end),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

/**
 * Shows [hour]:[minute] on the wheel, or as tappable text when the minute is off the wheel's
 * 5-minute steps; the keyboard icon opens the exact-time dialog either way.
 */
@Composable
private fun SheetTimeWheel(
    hour: Int,
    minute: Int,
    layout: DateTimeSheetLayout,
    use24Hour: Boolean,
    timePattern: String,
    showTimeDialog: Boolean,
    onShowTimeDialog: (Boolean) -> Unit,
    onTime: (hour: Int, minute: Int) -> Unit,
) {
    val wheelHeight = layout.rowHeight.dp * layout.wheelRows

    Box(modifier = Modifier.fillMaxWidth()) {
        if (isOnWheelGrid(minute)) {
            WheelTimePicker(
                selectedHour = hour,
                selectedMinute = minute,
                onTimeSelected = { h, m ->
                    // Ignore late fling emissions once the dialog is open, so a settling wheel
                    // can't change the time behind the dialog.
                    if (!showTimeDialog) onTime(h, m)
                },
                use24Hour = use24Hour,
                visibleItems = layout.wheelRows,
                itemHeight = layout.rowHeight.dp,
                fade = if (layout.wheelRows <= 3) SheetWheelFadeThreeRows else SheetWheelFadeFiveRows,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )
        } else {
            // Off-grid minute (e.g. 9:47): the wheel would snap it to a 5-minute step, so the
            // exact time shows as tappable text that opens the dialog.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(wheelHeight)
                    .clickable { onShowTimeDialog(true) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = DateTimeUtils.formatTime(hour, minute, timePattern),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.time_entry_tap_to_edit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Overlaid in the top-end corner so the wheel keeps full width. It covers only a faded,
        // non-selected wheel edge.
        IconButton(
            onClick = { onShowTimeDialog(true) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 4.dp, y = (-8).dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Keyboard,
                contentDescription = stringResource(R.string.cd_time_entry_keyboard)
            )
        }
    }

    if (showTimeDialog) {
        ExactTimeDialog(
            initialHour = hour,
            initialMinute = minute,
            use24Hour = use24Hour,
            onConfirm = { h, m ->
                onTime(h, m)
                onShowTimeDialog(false)
            },
            onDismiss = { onShowTimeDialog(false) }
        )
    }
}

/**
 * Holds one length chip: its [value] (minutes or days), shown [label], [spoken] long form and
 * whether it is [selected].
 */
internal data class SheetChip(val value: Int, val label: String, val spoken: String, val selected: Boolean)

/**
 * Returns how many of [total] chips [chipWidth] wide fit whole in [available], [gap] apart; at
 * least one, which the row then caps at its width.
 */
internal fun sheetChipsThatFit(available: Float, chipWidth: Float, gap: Float, total: Int): Int =
    floor((available + gap) / (chipWidth + gap)).toInt().coerceIn(1, total)

/**
 * Shows the length chips: 30m, 1h and 2h for a timed event, 2, 5 and 7 days for an all-day one.
 * A tap sets the end that long after the start; the chip matching the current length is marked.
 */
@Composable
private fun SheetLengthChips(sheet: DateTimeSheetState, onChip: (Int) -> Unit) {
    val resources = LocalResources.current
    val values = if (sheet.isAllDay) ALL_DAY_LENGTH_CHIPS_DAYS else TIMED_LENGTH_CHIPS_MINUTES
    val selectedChip = sheet.selectedChip(values)
    val chips = values.map { length ->
        val label = if (sheet.isAllDay) pluralStringResource(R.plurals.time_days, length, length)
        else formatDurationShort(length, resources)
        val spoken = if (sheet.isAllDay) label else formatDuration(length, resources)
        SheetChip(value = length, label = label, spoken = spoken, selected = selectedChip == length)
    }
    SheetChipsRow(chips = chips, onChip = onChip)
}

/**
 * Lays the [chips] out on one centered line, each as wide as the widest. Chips that don't fit
 * whole are left out, never squeezed; a single chip wider than the row is capped at its width.
 */
@Composable
internal fun SheetChipsRow(chips: List<SheetChip>, onChip: (Int) -> Unit) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(SHEET_CHIPS_DP.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val gap = 8.dp
        // A filter chip pads its label by 16dp on each side and draws a 1dp border.
        val chipWidth = with(density) {
            chips.maxOf { measurer.measure(it.label, style, maxLines = 1).size.width }.toDp() + 34.dp
        }
        val width = minOf(chipWidth, maxWidth)
        val count = sheetChipsThatFit(maxWidth.value, width.value, gap.value, chips.size)
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            chips.take(count).forEach { chip ->
                FilterChip(
                    selected = chip.selected,
                    onClick = { onChip(chip.value) },
                    label = {
                        Text(
                            chip.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    modifier = Modifier
                        .width(width)
                        .semantics { contentDescription = chip.spoken }
                )
            }
        }
    }
}

/**
 * Shows a month grid with swipe and arrow month navigation, and a month/year wheel behind the
 * header.
 *
 * Days are read and written with java.time in the device zone ([SelectedDay]): the highlight is
 * [selectedDateMillis]'s day, and a tap gives [onDateSelect] the tapped day at
 * [selectedDateMillis]'s time of day, resolved by [SelectedDay.dateAtTimeOfDayMs].
 *
 * @param displayedMonth the month the grid shows; callers open it on [selectedDateMillis]'s month
 * @param showMonthYearPicker the wheel's visibility when the caller manages it with
 *   [onShowMonthYearPickerChange]; null keeps it internal.
 */
@Composable
fun InlineDatePickerContent(
    selectedDateMillis: Long,
    displayedMonth: YearMonth,
    onDateSelect: (Long) -> Unit,
    onMonthChange: (YearMonth) -> Unit,
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY,
    showMonthYearPicker: Boolean? = null,
    onShowMonthYearPickerChange: ((Boolean) -> Unit)? = null
) {
    val selectedDate = SelectedDay.localDate(selectedDateMillis)
    // Re-sample "today" whenever the screen resumes so the marker can't go stale
    // when the picker stays open across midnight or the app is backgrounded.
    var todayRefreshKey by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        todayRefreshKey++
        onPauseOrDispose { }
    }
    val today = remember(todayRefreshKey) { LocalDate.now() }

    val monthYearFormat = remember { DateTimeUtils.localizedFormatter("yMMMM") }
    // Internal when the caller doesn't manage visibility (the RecurrencePicker "Until" date,
    // the home screen date pickers).
    var localShowMonthYearPicker by remember { mutableStateOf(false) }
    val effectiveShowMonthYearPicker = showMonthYearPicker ?: localShowMonthYearPicker
    val setShowMonthYearPicker: (Boolean) -> Unit = { value ->
        if (onShowMonthYearPickerChange != null) onShowMonthYearPickerChange(value)
        else localShowMonthYearPicker = value
    }
    val cdShowCalendar = stringResource(R.string.cd_show_calendar)
    val cdPickMonthYear = stringResource(R.string.cd_pick_month_year)
    val cdPreviousMonth = stringResource(R.string.cd_previous_month)
    val cdNextMonth = stringResource(R.string.cd_next_month)
    // The arrows and swipes stop at the CalendarSpan's ends; from a month outside it (an
    // existing event's) they only move toward it.
    val canGoBack = CalendarSpan.canStepMonth(displayedMonth, -1, today)
    val canGoForward = CalendarSpan.canStepMonth(displayedMonth, 1, today)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .stepSwipe(
                enabled = !effectiveShowMonthYearPicker,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
            ) { step -> onMonthChange(displayedMonth.plusMonths(step.toLong())) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!effectiveShowMonthYearPicker) {
                IconButton(
                    onClick = { onMonthChange(displayedMonth.minusMonths(1)) },
                    enabled = canGoBack,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, cdPreviousMonth, modifier = Modifier.size(20.dp))
                }
            } else {
                Spacer(modifier = Modifier.size(32.dp))
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { setShowMonthYearPicker(!effectiveShowMonthYearPicker) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = if (effectiveShowMonthYearPicker) cdShowCalendar
                            else cdPickMonthYear
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = displayedMonth.format(monthYearFormat),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (effectiveShowMonthYearPicker) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = if (effectiveShowMonthYearPicker) Icons.Default.ExpandLess
                                  else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (effectiveShowMonthYearPicker) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!effectiveShowMonthYearPicker) {
                IconButton(
                    onClick = { onMonthChange(displayedMonth.plusMonths(1)) },
                    enabled = canGoForward,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, cdNextMonth, modifier = Modifier.size(20.dp))
                }
            } else {
                Spacer(modifier = Modifier.size(32.dp))
            }
        }

        Box(modifier = Modifier.heightIn(min = 220.dp)) {
            Crossfade(
                targetState = effectiveShowMonthYearPicker,
                animationSpec = tween(200),
                label = "calendar-wheel-crossfade"
            ) { showWheels ->
                if (showWheels) {
                    MonthYearWheelPicker(
                        selectedYear = displayedMonth.year,
                        // The wheel's months are 0-based.
                        selectedMonth = displayedMonth.monthValue - 1,
                        onMonthYearSelected = { year, month -> onMonthChange(YearMonth.of(year, month + 1)) },
                        yearRange = CalendarSpan.yearRange(today),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Column {
                        val orderedDays = remember(firstDayOfWeek) {
                            DateTimeUtils.getOrderedDaysOfWeek(firstDayOfWeek)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            val locale = LocalLocale.current.platformLocale
                            orderedDays.forEach { day ->
                                Text(
                                    text = day.getDisplayName(java.time.format.TextStyle.NARROW, locale),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        val gridOffset = DateTimeUtils.getDayOfWeekOffset(displayedMonth.atDay(1), firstDayOfWeek)
                        val daysInMonth = displayedMonth.lengthOfMonth()
                        val weeks = ((gridOffset + daysInMonth + 6) / 7)

                        for (week in 0 until weeks) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                for (dayOfWeekIdx in 0..6) {
                                    val dayIndex = week * 7 + dayOfWeekIdx - gridOffset + 1

                                    if (dayIndex in 1..daysInMonth) {
                                        val date = displayedMonth.atDay(dayIndex)
                                        val isSelected = date == selectedDate
                                        val isToday = date == today
                                        val cellStyle = dayCellStyle(isToday = isToday, isSelected = isSelected)

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(36.dp)
                                                .padding(2.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    when (cellStyle) {
                                                        DayCellStyle.SELECTED -> MaterialTheme.colorScheme.inverseSurface
                                                        DayCellStyle.TODAY -> MaterialTheme.colorScheme.primaryContainer
                                                        DayCellStyle.PLAIN -> Color.Transparent
                                                    }
                                                )
                                                // The today fill can wash out for pale accent
                                                // seeds; a hairline outline keeps it visible on any
                                                // theme.
                                                .then(
                                                    if (cellStyle == DayCellStyle.TODAY) {
                                                        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                                    } else {
                                                        Modifier
                                                    }
                                                )
                                                // TalkBack hears which day is selected.
                                                .semantics { selected = isSelected }
                                                .clickable {
                                                    onDateSelect(SelectedDay.dateAtTimeOfDayMs(date, selectedDateMillis))
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = dayIndex.toString(),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = when (cellStyle) {
                                                    DayCellStyle.SELECTED -> MaterialTheme.colorScheme.inverseOnSurface
                                                    DayCellStyle.TODAY -> MaterialTheme.colorScheme.onPrimaryContainer
                                                    DayCellStyle.PLAIN -> MaterialTheme.colorScheme.onSurface
                                                },
                                                fontWeight = if (cellStyle == DayCellStyle.PLAIN) FontWeight.Normal else FontWeight.Bold
                                            )
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== Helpers ====================

/**
 * Names the look of one day cell in the grid.
 *
 * Today and the selected day can land on the same cell; then [SELECTED] wins, its fill already
 * marks the cell. Otherwise today keeps its own fill, so it stays visible while another date is
 * selected.
 */
enum class DayCellStyle {
    PLAIN,
    TODAY,
    SELECTED
}

/** Returns a day cell's look; selection wins over today. */
fun dayCellStyle(isToday: Boolean, isSelected: Boolean): DayCellStyle = when {
    isSelected -> DayCellStyle.SELECTED
    isToday -> DayCellStyle.TODAY
    else -> DayCellStyle.PLAIN
}

/**
 * Returns whether the 5-minute wheel can show [minute] exactly. An off-grid minute (9:47 from
 * the exact-time dialog) shows as tappable text instead of the wheel.
 */
fun isOnWheelGrid(minute: Int): Boolean = minute % 5 == 0

/**
 * Shows a dialog for typing an exact time: hour and minute fields, plus AM/PM in 12-hour mode.
 * As a centered dialog it floats above the soft keyboard, which would cover inline sheet
 * content. Any minute is accepted, so 9:47 round-trips exactly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExactTimeDialog(
    initialHour: Int,
    initialMinute: Int,
    use24Hour: Boolean,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit
) {
    // TimePickerState.hour is always 0-23; is24Hour only affects display (two fields
    // vs three with AM/PM), so no manual 12<->24 conversion is needed.
    val timeState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = use24Hour
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(timeState.hour, timeState.minute) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        title = { Text(stringResource(R.string.time_entry_dialog_title)) },
        text = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimeInput(state = timeState)
            }
        }
    )
}

/** Returns whether the end time is earlier in the day than the start, crossing midnight. */
fun isMidnightCrossing(
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int
): Boolean {
    val startMinutes = startHour * 60 + startMinute
    val endMinutes = endHour * 60 + endMinute
    return endMinutes < startMinutes
}
