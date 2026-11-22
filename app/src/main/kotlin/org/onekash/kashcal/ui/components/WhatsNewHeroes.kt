package org.onekash.kashcal.ui.components

import android.content.ContentResolver
import android.content.res.Resources
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.onekash.kashcal.R
import org.onekash.kashcal.calendarbackup.BackupCalendarName
import org.onekash.kashcal.calendarbackup.CalendarBackupInterval
import org.onekash.kashcal.calendarbackup.CalendarBackupNames
import org.onekash.kashcal.calendarbackup.CalendarBackupSettings
import org.onekash.kashcal.domain.changes.RecentChangeItem
import org.onekash.kashcal.domain.whatsnew.WhatsNewHero
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.ChangedField
import org.onekash.kashcal.sync.notification.buildChangeNotificationContent
import org.onekash.kashcal.ui.appicon.AppIconPreset
import org.onekash.kashcal.ui.appicon.DayIcons
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.print.formatPrintWeekLabel
import org.onekash.kashcal.ui.screens.settings.OfflineCalendarRowContent
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Which frame an illustration shows while it isn't playing. */
internal enum class HeroPhase { START, END }

/**
 * Returns the frame an illustration rests on: the start, ready to play, or with motion off the
 * end, which shows the whole idea in one still.
 */
internal fun initialHeroPhase(motionEnabled: Boolean): HeroPhase =
    if (motionEnabled) HeroPhase.START else HeroPhase.END

/** Returns false when the system's "Remove animations" sets the animator duration scale to 0. */
internal fun heroMotionEnabled(resolver: ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

/** Returns yesterday's and today's day of the month, the two dates the icon turns over between. */
internal fun heroDays(today: LocalDate): Pair<Int, Int> =
    today.minusDays(1).dayOfMonth to today.dayOfMonth

/**
 * Returns the sample Recent changes rows the illustrations show: a new event, a moved one and a
 * removed one, timed from [now] in [zone]. They are display-only; their ids reach no database.
 */
internal fun whatsNewSampleRows(resources: Resources, now: Long, zone: ZoneId): List<RecentChangeItem> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun at(days: Long, hour: Int) = today.plusDays(days).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    val calendar = resources.getString(R.string.whats_new_hero_sample_calendar)
    fun row(
        id: Long,
        type: ChangeType,
        titleRes: Int,
        start: Long,
        previousStart: Long? = null,
        changed: Set<ChangedField> = emptySet(),
    ) = RecentChangeItem(
        id = id, calendarId = 0, eventUid = "", instanceTs = 0, eventId = null, changeType = type,
        title = resources.getString(titleRes), startTs = start, endTs = start + HOUR_MS,
        isAllDay = false, isRecurring = false, detectedAt = now, changedFields = changed,
        previousStartTs = previousStart, previousIsAllDay = previousStart?.let { false },
        calendarName = calendar, calendarColor = SAMPLE_CALENDAR_COLOR,
    )
    return listOf(
        row(1, ChangeType.NEW, R.string.whats_new_hero_sample_lunch, at(2, 12)),
        row(
            2, ChangeType.MODIFIED, R.string.whats_new_hero_sample_review, at(1, 16),
            previousStart = at(1, 15), changed = setOf(ChangedField.TIME),
        ),
        row(3, ChangeType.DELETED, R.string.whats_new_hero_sample_book_club, at(3, 19)),
    )
}

private const val HOUR_MS = 60 * 60 * 1000L

/** The dot color of the one sample calendar every sample row sits in. */
private const val SAMPLE_CALENDAR_COLOR = 0xFF22A06B.toInt()

/** One sample offline calendar the illustrations show; display-only, it reaches no database. */
internal data class SampleOfflineCalendar(
    val name: String,
    val color: Int,
    val eventCount: Int,
    val isDefault: Boolean,
)

/**
 * Returns the sample offline calendars: the built-in one, named as the Offline calendars screen
 * names it and set as the default, then two of the user's own. The last is the one the offline
 * calendars illustration recolors and the backup illustration saves.
 */
internal fun whatsNewSampleOfflineCalendars(resources: Resources): List<SampleOfflineCalendar> = listOf(
    SampleOfflineCalendar(resources.getString(R.string.calendar_local), 0xFF0E6E62.toInt(), 12, isDefault = true),
    SampleOfflineCalendar(resources.getString(R.string.whats_new_hero_sample_family), 0xFFE11D48.toInt(), 8, isDefault = false),
    SampleOfflineCalendar(resources.getString(R.string.whats_new_hero_sample_garden), 0xFF3B82F6.toInt(), 5, isDefault = false),
)

/** The color the offline calendars illustration gives its last sample calendar. */
private const val SAMPLE_RECOLOR = SAMPLE_CALENDAR_COLOR

/**
 * The files one run of automatic backup leaves in the backup illustration's folder: the last
 * sample calendar's [latest] file, the [newCopy] the run adds, the [oldCopies] already there
 * (newest first), the ones the run [removed], and the built-in calendar's file. The run happens at
 * [runAt]; the one before it, which last wrote [latest], [intervalMs] earlier.
 */
internal data class SampleBackupFiles(
    val latest: String,
    val builtInLatest: String,
    val newCopy: String,
    val oldCopies: List<String>,
    val removed: List<String>,
    val runAt: Long,
    val intervalMs: Long,
)

/**
 * Returns the sample backup folder at the default interval and copy count, named and trimmed by
 * [CalendarBackupNames] as a real run is: the copy a run adds is named after the latest file's
 * last write, the previous run, so each copy is one interval older than the next. The run is
 * placed on the hour before [now], so the sample names read as whole hours.
 */
internal fun whatsNewSampleBackupFiles(resources: Resources, now: Long): SampleBackupFiles {
    val calendars = whatsNewSampleOfflineCalendars(resources)
    val bases = CalendarBackupNames.planBaseNames(
        listOf(
            BackupCalendarName(calendarId = 1, displayName = calendars.first().name, isBuiltIn = true),
            BackupCalendarName(calendarId = 2, displayName = calendars.last().name, isBuiltIn = false),
        ),
    )
    val base = bases.getValue(2)
    val runAt = Instant.ofEpochMilli(now).truncatedTo(ChronoUnit.HOURS)
    val interval = Duration.ofHours(CalendarBackupInterval.DEFAULT_HOURS.toLong())
    fun copy(intervalsBack: Int) =
        CalendarBackupNames.datedCopyName(base, runAt.minus(interval.multipliedBy(intervalsBack.toLong())))
    val latest = CalendarBackupNames.latestFileName(base)
    val newCopy = copy(1)
    // Before the run the folder holds the latest file and DEFAULT_KEEP - 1 older copies.
    val oldCopies = (2..CalendarBackupSettings.DEFAULT_KEEP).map(::copy)
    return SampleBackupFiles(
        latest = latest,
        builtInLatest = CalendarBackupNames.latestFileName(bases.getValue(1)),
        newCopy = newCopy,
        oldCopies = oldCopies,
        removed = CalendarBackupNames.copiesBeyondCount(
            listOf(latest, newCopy) + oldCopies,
            base,
            CalendarBackupSettings.DEFAULT_KEEP,
            justAdded = newCopy,
        ),
        runAt = runAt.toEpochMilli(),
        intervalMs = interval.toMillis(),
    )
}

/** What the backup illustration shows in one frame: the new copy added, the old ones trimmed. */
internal data class BackupArtFrame(val copied: Boolean, val trimmed: Boolean)

/** Frame delays of the backup illustration: before the run, copied, trimmed. */
internal val BACKUP_ART_FRAMES = listOf(1000L, 800L, 3400L)

/** Returns what frame [frame] of [BACKUP_ART_FRAMES] shows. */
internal fun backupArtFrame(frame: Int): BackupArtFrame = BackupArtFrame(copied = frame >= 1, trimmed = frame >= 2)

/** One sample event on the printed week: [day] counts from the week's first day. */
internal data class SamplePrintEvent(
    val number: Int,
    val day: Int,
    val startHour: Int,
    val hours: Int,
    val color: Int,
    val title: String,
)

/** Returns the printed week's sample events, numbered in time order like the printout's. */
internal fun whatsNewSamplePrintEvents(resources: Resources): List<SamplePrintEvent> = listOf(
    SamplePrintEvent(1, 1, 8, 2, 0xFF3B82F6.toInt(), resources.getString(R.string.whats_new_hero_sample_dentist)),
    SamplePrintEvent(2, 1, 13, 1, 0xFF22A06B.toInt(), resources.getString(R.string.whats_new_hero_sample_lunch)),
    SamplePrintEvent(3, 3, 15, 2, 0xFFD97706.toInt(), resources.getString(R.string.whats_new_hero_sample_review)),
    SamplePrintEvent(4, 4, 9, 2, 0xFF9333EA.toInt(), resources.getString(R.string.whats_new_hero_sample_yoga)),
    SamplePrintEvent(5, 5, 17, 2, 0xFFE11D48.toInt(), resources.getString(R.string.whats_new_hero_sample_book_club)),
)

/** What the print illustration shows in one frame: which pages have come out. */
internal data class PrintArtFrame(val detailsOut: Boolean, val weekOut: Boolean)

/** Frame delays of the print illustration: nothing printed, the details page, both pages. */
internal val PRINT_ART_FRAMES = listOf(500L, 900L, 4200L)

/** Returns what frame [frame] of [PRINT_ART_FRAMES] shows. */
internal fun printArtFrame(frame: Int): PrintArtFrame = PrintArtFrame(detailsOut = frame >= 1, weekOut = frame >= 2)

/** What the offline calendars illustration shows in one frame. */
internal data class OfflineArtFrame(val rows: Int, val recolored: Boolean)

/** Frame delays of the offline calendars illustration: empty, one, two, three rows, recolored. */
internal val OFFLINE_ART_FRAMES = listOf(400L, 380L, 380L, 1200L, 2600L)

/** Returns what frame [frame] of [OFFLINE_ART_FRAMES] shows. */
internal fun offlineArtFrame(frame: Int): OfflineArtFrame =
    OfflineArtFrame(rows = minOf(frame, 3), recolored = frame >= 4)

/**
 * Draws [hero]'s illustration, decorative and hidden from TalkBack. It loops while [playing]
 * and [motionEnabled]; otherwise it rests on [initialHeroPhase]'s frame. Times use [timePattern];
 * sample events are placed relative to [now]. Its text ignores the font scale, as a picture's
 * would: the frame has a fixed height, and larger text would push the sample rows out of it.
 */
@Composable
internal fun WhatsNewHeroArt(
    hero: WhatsNewHero,
    timePattern: String,
    now: Long,
    playing: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            val rest = initialHeroPhase(motionEnabled)
            val play = playing && motionEnabled
            when (hero) {
                WhatsNewHero.DATE_ICON -> DateIconArt(timePattern, now, play, rest)
                WhatsNewHero.RECENT_CHANGES -> RecentChangesArt(timePattern, now, play, rest)
                WhatsNewHero.CHANGE_NOTIFICATIONS -> NotificationArt(timePattern, now, play, rest)
                WhatsNewHero.OFFLINE_CALENDARS -> OfflineCalendarsArt(play, rest)
                WhatsNewHero.CALENDAR_BACKUP -> CalendarBackupArt(timePattern, now, play, rest)
                WhatsNewHero.PRINT_WEEK -> PrintWeekArt(now, play, rest)
            }
        }
    }
}

/**
 * Runs [frames] in a loop while [play] is true, holding each for its delay in milliseconds, and
 * returns the current frame index. Not playing, it holds the first frame for [HeroPhase.START]
 * and the last for [HeroPhase.END].
 */
@Composable
private fun loopFrames(play: Boolean, rest: HeroPhase, frames: List<Long>): Int {
    val restFrame = if (rest == HeroPhase.START) 0 else frames.lastIndex
    var frame by remember { mutableIntStateOf(restFrame) }
    LaunchedEffect(play, rest) {
        frame = restFrame
        if (!play) return@LaunchedEffect
        while (true) {
            for (index in frames.indices) {
                frame = index
                delay(frames[index])
            }
        }
    }
    return frame
}

@Composable
private fun DateIconArt(timePattern: String, now: Long, play: Boolean, rest: HeroPhase) {
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val (yesterday, todayDay) = heroDays(today)
    val turned = loopFrames(play, rest, listOf(1300L, 3300L)) == 1
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (turned) {
                DateTimeUtils.formatTime(0, 0, timePattern)
            } else {
                DateTimeUtils.formatTime(23, 59, timePattern)
            },
            style = MaterialTheme.typography.labelLarge,
            color = onContainer.copy(alpha = 0.8f),
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeighborIcon(onContainer)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(colorResource(R.color.ic_launcher_background)),
                ) {
                    AnimatedContent(
                        targetState = if (turned) todayDay else yesterday,
                        transitionSpec = {
                            (slideInVertically(tween(550)) { it / 5 } + fadeIn(tween(550)) + scaleIn(tween(550), 0.86f))
                                .togetherWith(
                                    slideOutVertically(tween(450)) { -it / 5 } + fadeOut(tween(450)) +
                                        scaleOut(tween(450), targetScale = 0.86f),
                                )
                        },
                        label = "day",
                    ) { day ->
                        // Composited like the icon picker's preview
                        // ([AppIconPreset.previewForegroundFor]).
                        Image(
                            painter = painterResource(DayIcons.foregroundFor(day)),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .scale(AppIconPreset.PREVIEW_FOREGROUND_SCALE),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                )
            }
            NeighborIcon(onContainer)
        }
    }
}

/** A blank launcher icon either side of KashCal's, so the tile reads as a home screen. */
@Composable
private fun NeighborIcon(color: Color) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f)),
    )
}

@Composable
private fun RecentChangesArt(timePattern: String, now: Long, play: Boolean, rest: HeroPhase) {
    val resources = LocalResources.current
    val formatter = remember(resources, timePattern) { RecentChangeFormatter(resources, timePattern) }
    val rows = remember(resources, now) { whatsNewSampleRows(resources, now, ZoneId.systemDefault()) }
    // Frames: empty, one row, two rows, three rows, then the removed row swipes away.
    val frame = loopFrames(play, rest, listOf(400L, 380L, 380L, 2000L, 1800L))
    val visible = minOf(frame, rows.size)
    val removedGone = play && frame == 4
    // The sheet dismisses a row end to start, which is rightward in right-to-left layouts.
    val towardStart = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1 else -1

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .wrapContentHeight(unbounded = true)
            // The real row's sizes, drawn smaller so three rows fit the frame.
            .graphicsLayer {
                scaleX = 0.86f
                scaleY = 0.86f
            },
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(
                text = formatter.dayHeader(now, now),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            rows.forEachIndexed { index, item ->
                val removed = item.changeType == ChangeType.DELETED
                AnimatedVisibility(
                    visible = index < visible && !(removedGone && removed),
                    enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 3 },
                    // The removed row swipes away as in the sheet; the rest fade as the
                    // loop restarts.
                    exit = if (removed) {
                        slideOutHorizontally(tween(400)) { towardStart * it } + fadeOut(tween(400)) +
                            shrinkVertically(tween(400, delayMillis = 200))
                    } else {
                        fadeOut(tween(300))
                    },
                ) {
                    RecentChangeRowContent(
                        item = item,
                        whenText = formatter.whenText(item, now),
                        detail = formatter.detail(item),
                        verticalPadding = 5.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationArt(timePattern: String, now: Long, play: Boolean, rest: HeroPhase) {
    val resources = LocalResources.current
    val content = remember(resources, timePattern, now) {
        val moved = whatsNewSampleRows(resources, now, ZoneId.systemDefault())
            .first { it.changeType == ChangeType.MODIFIED }
        buildChangeNotificationContent(listOf(moved), resources, RecentChangeFormatter(resources, timePattern), now)
    }
    val clock = remember(timePattern, now) {
        DateTimeUtils.formatEventTime(now, isAllDay = false, pattern = timePattern)
    }
    val arrived = loopFrames(play, rest, listOf(500L, 4300L)) == 1
    val separator = stringResource(R.string.recent_changes_detail_separator)
    val header = listOfNotNull(
        stringResource(R.string.app_name),
        content.subText,
        stringResource(R.string.whats_new_hero_now),
    ).joinToString(separator)

    // A lock screen stays dark in either theme, like a real one.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF14213B), Color(0xFF0B3A3C)))),
    ) {
        Text(
            text = clock,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 40.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
        )
        AnimatedVisibility(
            visible = arrived,
            enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow)) { -it * 2 } +
                fadeIn(tween(200)),
            exit = fadeOut(tween(300)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(14.dp),
        ) {
            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xF22D3036), shadowElevation = 6.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_notification),
                            contentDescription = null,
                            tint = Color(0xFF82D5C8),
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = header,
                            color = Color(0xFFA8ACB3),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = content.title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = content.text,
                        color = Color(0xFFBFC3C9),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineCalendarsArt(play: Boolean, rest: HeroPhase) {
    val resources = LocalResources.current
    val calendars = remember(resources) { whatsNewSampleOfflineCalendars(resources) }
    val shown = offlineArtFrame(loopFrames(play, rest, OFFLINE_ART_FRAMES))
    val lastColor by animateColorAsState(
        targetValue = Color(if (shown.recolored) SAMPLE_RECOLOR else calendars.last().color),
        animationSpec = tween(500),
        label = "recolor",
    )

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .wrapContentHeight(unbounded = true)
            // The real row, drawn smaller so three rows fit the frame.
            .graphicsLayer {
                scaleX = 0.86f
                scaleY = 0.86f
            },
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.offline_calendars_row_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            calendars.forEachIndexed { index, calendar ->
                AnimatedVisibility(
                    visible = index < shown.rows,
                    enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 3 },
                    exit = fadeOut(tween(300)),
                ) {
                    OfflineCalendarRowContent(
                        color = if (index == calendars.lastIndex) lastColor.toArgb() else calendar.color,
                        name = calendar.name,
                        eventCount = calendar.eventCount,
                        isDefault = calendar.isDefault,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        minHeight = 0.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarBackupArt(timePattern: String, now: Long, play: Boolean, rest: HeroPhase) {
    val resources = LocalResources.current
    val files = remember(resources, now) { whatsNewSampleBackupFiles(resources, now) }
    BackupFolderCard(files, backupArtFrame(loopFrames(play, rest, BACKUP_ART_FRAMES)), timePattern)
}

/** Draws the backup folder as [shown] has it; internal so a test can draw its fullest frame. */
@Composable
internal fun BackupFolderCard(files: SampleBackupFiles, shown: BackupArtFrame, timePattern: String) {
    val written = DateTimeUtils.formatEventTime(
        if (shown.copied) files.runAt else files.runAt - files.intervalMs,
        isAllDay = false,
        pattern = timePattern,
    )
    // A trimmed copy leaves toward the start edge, as the Recent changes row does.
    val towardStart = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1 else -1

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(0.94f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.whats_new_hero_sample_folder),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            BackupFileRow(files.latest, latest = true, time = written)
            AnimatedVisibility(
                visible = shown.copied,
                enter = fadeIn(tween(400)) + expandVertically(tween(400)),
                exit = fadeOut(tween(300)) + shrinkVertically(tween(300)),
            ) {
                BackupFileRow(files.newCopy, latest = false)
            }
            files.oldCopies.forEach { name ->
                AnimatedVisibility(
                    visible = !(shown.trimmed && name in files.removed),
                    enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                    exit = slideOutHorizontally(tween(400)) { towardStart * it } + fadeOut(tween(400)) +
                        shrinkVertically(tween(400, delayMillis = 200)),
                ) {
                    BackupFileRow(name, latest = false)
                }
            }
            BackupFileRow(files.builtInLatest, latest = true, time = written)
        }
    }
}

/** One file in the backup folder: a latest file shows its last write [time]; copies are dimmed. */
@Composable
private fun BackupFileRow(name: String, latest: Boolean, time: String? = null) {
    val color = if (latest) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            tint = if (latest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (time != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// A printout stays white with dark ink in either theme; the ink colors match the printout's CSS.
private val PAPER = Color.White
private val INK = Color(0xFF1B1B1F)
private val INK_MUTED = Color(0xFF5F6068)
private val RULE = Color(0xFFC9C9CF)

/** The first and last hour the printed week's grid shows, around the sample events. */
private const val GRID_FIRST_HOUR = 8
private const val GRID_LAST_HOUR = 20

@Composable
private fun PrintWeekArt(now: Long, play: Boolean, rest: HeroPhase) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = resources.configuration.locales[0]
    // The print sheet opens on this week when KashCal's first-day setting is the default.
    val start = remember(now, locale) {
        WeekViewUtils.getWeekStart(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate(), 0)
    }
    val label = remember(context, start) { formatPrintWeekLabel(context, start) }
    val events = remember(resources) { whatsNewSamplePrintEvents(resources) }
    val shown = printArtFrame(loopFrames(play, rest, PRINT_ART_FRAMES))
    val pageOut = slideInVertically(tween(1400)) { -it } + fadeIn(tween(300))

    Box(modifier = Modifier.fillMaxSize()) {
        // The pages come out of the slot: they slide down inside an area that starts at the
        // slot's middle and are drawn under the slot.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 13.dp)
                .clipToBounds(),
        ) {
            AnimatedVisibility(
                visible = shown.detailsOut,
                enter = pageOut,
                exit = fadeOut(tween(300)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 7.dp, start = 28.dp)
                    .graphicsLayer { rotationZ = 4f },
            ) {
                PrintedPage(footer = null) {
                    PageHeading(stringResource(R.string.print_page_details))
                    val separator = stringResource(R.string.recent_changes_detail_separator)
                    events.forEach { event ->
                        val day = start.plusDays(event.day.toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(Color(event.color)),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${event.number}. $day$separator${event.title}",
                                color = INK,
                                fontSize = 7.sp,
                                lineHeight = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = shown.weekOut,
                enter = pageOut,
                exit = fadeOut(tween(300)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 3.dp),
            ) {
                PrintedPage(footer = stringResource(R.string.print_doc_page_of, "1", "2")) {
                    PageHeading(label)
                    WeekGrid(start, locale, events)
                }
            }
        }
        // The printer's paper slot.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .fillMaxWidth(0.76f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)),
        )
    }
}

/** A sheet of printed paper with an optional [footer] at its bottom right, as the printout has. */
@Composable
private fun PrintedPage(footer: String?, content: @Composable () -> Unit) {
    Surface(
        color = PAPER,
        shape = RoundedCornerShape(3.dp),
        shadowElevation = 4.dp,
        modifier = Modifier.size(width = 196.dp, height = 150.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
            Column(modifier = Modifier.weight(1f)) { content() }
            if (footer != null) {
                Text(
                    text = footer,
                    color = INK_MUTED,
                    fontSize = 6.sp,
                    lineHeight = 7.sp,
                    maxLines = 1,
                    // The printout's page counter sits bottom right in every language.
                    modifier = Modifier.align(AbsoluteAlignment.Right),
                )
            }
        }
    }
}

@Composable
private fun PageHeading(text: String) {
    Text(
        text = text,
        color = INK,
        fontSize = 9.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 3.dp),
    )
}

/** Seven day columns from [start], each event a numbered block placed by its hours. */
@Composable
private fun WeekGrid(start: LocalDate, locale: Locale, events: List<SamplePrintEvent>) {
    val hours = (GRID_LAST_HOUR - GRID_FIRST_HOUR).toFloat()
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(7) { index ->
            val date = start.plusDays(index.toLong())
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Text(
                    text = "${date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale)} ${date.dayOfMonth}",
                    color = INK_MUTED,
                    fontSize = 6.sp,
                    lineHeight = 7.sp,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 2.dp)
                        .background(RULE.copy(alpha = 0.25f)),
                ) {
                    events.filter { it.day == index }.forEach { event ->
                        Box(
                            modifier = Modifier
                                .padding(top = maxHeight * ((event.startHour - GRID_FIRST_HOUR) / hours))
                                .fillMaxWidth()
                                // Tall enough to hold its number, as the printout's blocks are.
                                .height(maxOf(maxHeight * (event.hours / hours), 14.dp))
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(event.color)),
                        ) {
                            Text(
                                text = event.number.toString(),
                                color = Color.White,
                                fontSize = 6.sp,
                                lineHeight = 7.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
