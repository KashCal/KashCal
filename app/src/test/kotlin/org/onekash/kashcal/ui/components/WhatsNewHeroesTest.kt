package org.onekash.kashcal.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.calendarbackup.CalendarBackupInterval
import org.onekash.kashcal.calendarbackup.CalendarBackupNames
import org.onekash.kashcal.domain.whatsnew.WhatsNewHero
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.notification.buildChangeNotificationContent
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.print.formatPrintWeekLabel
import org.onekash.kashcal.util.DateTimeUtils
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Tests the What's New illustrations ([WhatsNewHeroArt]): the dates the icon turns over between,
 * the frame each starts on with and without motion ([initialHeroPhase], [heroMotionEnabled]), that
 * their sample text is worded by the same code as the real Recent changes row, notification and
 * offline calendar row ([OfflineCalendarRowContent]), that the backup folder's files are named and
 * trimmed by automatic backup's own rules ([CalendarBackupNames]), that the printed week is headed
 * like the printout ([formatPrintWeekLabel]), and that TalkBack reads none of it.
 *
 * Runs under Robolectric in en-US with a 12-hour pattern, with native graphics so text has its
 * real size (the fit-at-large-text tests measure it); run the class alone given the repo's
 * multi-class native-crash flake.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h9999dp-mdpi")
class WhatsNewHeroesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.systemDefault()
    private val now = LocalDateTime.of(2026, 10, 11, 9, 41).atZone(zone).toInstant().toEpochMilli()

    private fun render(hero: WhatsNewHero, playing: Boolean, motionEnabled: Boolean, fontScale: Float = 1f) {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    // The size of the frame the banner draws an illustration in.
                    Box(modifier = Modifier.size(312.dp, 188.dp).testTag(FRAME)) {
                        WhatsNewHeroArt(
                            hero = hero,
                            timePattern = "h:mm a",
                            now = now,
                            playing = playing,
                            motionEnabled = motionEnabled,
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun bounds(text: String) =
        composeTestRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /**
     * Returns true when [text] is cut short with an ellipsis, as a one-line text that doesn't fit
     * is. didOverflowWidth isn't used: a wrap-content text can report a sub-pixel overflow.
     */
    private fun overflows(text: String): Boolean {
        val layouts = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        return layout.isLineEllipsized(layout.lineCount - 1)
    }

    private fun exists(text: String) =
        composeTestRule.onNodeWithText(text, useUnmergedTree = true).assertExists()

    @Test
    fun `icon turns over from yesterday's day of the month to today's`() {
        assertEquals(10 to 11, heroDays(LocalDate.of(2026, 10, 11)))
        assertEquals(31 to 1, heroDays(LocalDate.of(2026, 11, 1)))
        assertEquals(29 to 1, heroDays(LocalDate.of(2028, 3, 1)))
        assertEquals(28 to 1, heroDays(LocalDate.of(2027, 3, 1)))
    }

    @Test
    fun `without motion an illustration starts on its end frame`() {
        assertEquals(HeroPhase.END, initialHeroPhase(motionEnabled = false))
        assertEquals(HeroPhase.START, initialHeroPhase(motionEnabled = true))
    }

    @Test
    fun `motion follows the system animator duration scale`() {
        val resolver = context.contentResolver
        assertTrue(heroMotionEnabled(resolver))
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertFalse(heroMotionEnabled(resolver))
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertTrue(heroMotionEnabled(resolver))
    }

    @Test
    fun `date icon without motion shows today at midnight`() {
        render(WhatsNewHero.DATE_ICON, playing = true, motionEnabled = false)
        exists("12:00 AM")
        composeTestRule.onNodeWithText("11:59 PM", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `date icon off screen waits on yesterday at 11 59`() {
        render(WhatsNewHero.DATE_ICON, playing = false, motionEnabled = true)
        exists("11:59 PM")
        composeTestRule.onNodeWithText("12:00 AM", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `recent changes card words its rows like the sheet`() {
        render(WhatsNewHero.RECENT_CHANGES, playing = true, motionEnabled = false)
        exists(context.getString(R.string.whats_new_hero_sample_lunch))
        exists(context.getString(R.string.whats_new_hero_sample_review))
        exists(context.getString(R.string.whats_new_hero_sample_book_club))
        exists("Moved from 3:00 PM")
        exists(context.getString(R.string.label_today))
    }

    @Test
    fun `notification reads as the real change notification for the moved row`() {
        render(WhatsNewHero.CHANGE_NOTIFICATIONS, playing = true, motionEnabled = false)
        val moved = whatsNewSampleRows(context.resources, now, zone).single { it.changeType == ChangeType.MODIFIED }
        val content = buildChangeNotificationContent(
            listOf(moved),
            context.resources,
            RecentChangeFormatter(context.resources, "h:mm a"),
            now,
        )
        exists(content.title)
        exists(content.text)
    }

    @Test
    fun `TalkBack reads none of the sample text`() {
        render(WhatsNewHero.RECENT_CHANGES, playing = true, motionEnabled = false)
        composeTestRule.onNodeWithText(context.getString(R.string.whats_new_hero_sample_lunch)).assertDoesNotExist()
        composeTestRule.onNodeWithText("Moved from 3:00 PM").assertDoesNotExist()
    }

    @Test
    fun `recent changes card fits its frame at large text`() {
        render(WhatsNewHero.RECENT_CHANGES, playing = true, motionEnabled = false, fontScale = 2f)
        val frame = composeTestRule.onNodeWithTag(FRAME).fetchSemanticsNode().boundsInRoot
        val header = bounds(context.getString(R.string.label_today))
        val lastRow = bounds(context.getString(R.string.whats_new_hero_sample_book_club))
        assertTrue("header cut off: $header in $frame", header.top >= frame.top)
        assertTrue("last row cut off: $lastRow in $frame", lastRow.bottom <= frame.bottom)
    }

    @Test
    fun `notification card stays clear of the clock at large text`() {
        render(WhatsNewHero.CHANGE_NOTIFICATIONS, playing = true, motionEnabled = false, fontScale = 2f)
        val moved = whatsNewSampleRows(context.resources, now, zone).single { it.changeType == ChangeType.MODIFIED }
        val content = buildChangeNotificationContent(
            listOf(moved), context.resources, RecentChangeFormatter(context.resources, "h:mm a"), now,
        )
        val clock = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US))
        assertTrue(bounds(content.text).bottom <= bounds(clock).top)
    }

    @Test
    fun `offline calendars card names the built-in calendar and counts events like the screen`() {
        render(WhatsNewHero.OFFLINE_CALENDARS, playing = true, motionEnabled = false)
        exists(context.getString(R.string.offline_calendars_row_label))
        whatsNewSampleOfflineCalendars(context.resources).forEach { calendar ->
            exists(calendar.name)
            exists(context.resources.getQuantityString(R.plurals.event_count, calendar.eventCount, calendar.eventCount))
        }
        val calendars = whatsNewSampleOfflineCalendars(context.resources)
        assertEquals(context.getString(R.string.calendar_local), calendars.first().name)
        assertEquals(listOf(true, false, false), calendars.map { it.isDefault })
    }

    @Test
    fun `offline calendars card ends on every row with the new color`() {
        assertEquals(OfflineArtFrame(rows = 0, recolored = false), offlineArtFrame(0))
        assertEquals(OfflineArtFrame(rows = 3, recolored = false), offlineArtFrame(OFFLINE_ART_FRAMES.lastIndex - 1))
        assertEquals(OfflineArtFrame(rows = 3, recolored = true), offlineArtFrame(OFFLINE_ART_FRAMES.lastIndex))
    }

    @Test
    fun `offline calendars card off screen waits empty`() {
        render(WhatsNewHero.OFFLINE_CALENDARS, playing = false, motionEnabled = true)
        exists(context.getString(R.string.offline_calendars_row_label))
        composeTestRule.onNodeWithText(context.getString(R.string.whats_new_hero_sample_family), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `TalkBack reads none of the offline calendars card`() {
        render(WhatsNewHero.OFFLINE_CALENDARS, playing = true, motionEnabled = false)
        composeTestRule.onNodeWithText(context.getString(R.string.whats_new_hero_sample_family)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.calendar_local)).assertDoesNotExist()
    }

    @Test
    fun `offline calendars card fits its frame at large text`() {
        render(WhatsNewHero.OFFLINE_CALENDARS, playing = true, motionEnabled = false, fontScale = 2f)
        val frame = composeTestRule.onNodeWithTag(FRAME).fetchSemanticsNode().boundsInRoot
        val header = bounds(context.getString(R.string.offline_calendars_row_label))
        val lastRow = bounds(context.getString(R.string.whats_new_hero_sample_garden))
        assertTrue("header cut off: $header in $frame", header.top >= frame.top)
        assertTrue("last row cut off: $lastRow in $frame", lastRow.bottom <= frame.bottom)
    }

    @Test
    fun `backup card names files like the backup does`() {
        val files = whatsNewSampleBackupFiles(context.resources, now)
        val garden = context.getString(R.string.whats_new_hero_sample_garden)
        val interval = CalendarBackupInterval.DEFAULT_HOURS * 3_600_000L
        val runAt = java.time.Instant.ofEpochMilli(now).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        fun copyAt(intervalsBack: Long) =
            CalendarBackupNames.datedCopyName(garden, runAt.minusMillis(intervalsBack * interval))

        assertEquals("$garden.ics", files.latest)
        assertEquals("Local.ics", files.builtInLatest)
        // A run copies the latest file under the time of its last write, one interval back.
        assertEquals(copyAt(1), files.newCopy)
        assertEquals(listOf(copyAt(2), copyAt(3)), files.oldCopies)
        assertEquals(listOf(copyAt(3)), files.removed)
    }

    @Test
    fun `backup card without motion shows the trimmed copies`() {
        render(WhatsNewHero.CALENDAR_BACKUP, playing = true, motionEnabled = false)
        val files = whatsNewSampleBackupFiles(context.resources, now)
        exists(files.latest)
        exists(files.newCopy)
        exists(files.builtInLatest)
        (files.oldCopies - files.removed.toSet()).forEach { exists(it) }
        // Both latest files were written by the run.
        val written = DateTimeUtils.formatEventTime(files.runAt, isAllDay = false, pattern = "h:mm a")
        assertEquals(2, composeTestRule.onAllNodesWithText(written, useUnmergedTree = true).fetchSemanticsNodes().size)
        files.removed.forEach {
            composeTestRule.onNodeWithText(it, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun `backup card off screen waits before the run`() {
        render(WhatsNewHero.CALENDAR_BACKUP, playing = false, motionEnabled = true)
        val files = whatsNewSampleBackupFiles(context.resources, now)
        files.oldCopies.forEach { exists(it) }
        composeTestRule.onNodeWithText(files.newCopy, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `TalkBack reads none of the backup card`() {
        render(WhatsNewHero.CALENDAR_BACKUP, playing = true, motionEnabled = false)
        val files = whatsNewSampleBackupFiles(context.resources, now)
        composeTestRule.onNodeWithText(files.latest).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.whats_new_hero_sample_folder)).assertDoesNotExist()
    }

    @Test
    fun `backup card fits its frame at its fullest`() {
        assertBackupCardFits()
    }

    @Test
    fun `backup card ignores large text`() {
        render(WhatsNewHero.CALENDAR_BACKUP, playing = false, motionEnabled = true, fontScale = 2f)
        val frame = composeTestRule.onNodeWithTag(FRAME).fetchSemanticsNode().boundsInRoot
        val last = bounds(whatsNewSampleBackupFiles(context.resources, now).builtInLatest)
        assertTrue("last file cut off: $last in $frame", last.bottom <= frame.bottom)
    }

    @Test
    @Config(qualifiers = "de-w360dp-h9999dp-mdpi")
    fun `backup card fits its frame in German`() {
        assertBackupCardFits()
    }

    private fun assertBackupCardFits() {
        val files = whatsNewSampleBackupFiles(context.resources, now)
        // The fullest frame: the new copy has arrived and the oldest hasn't left yet. The art
        // draws at font scale 1 whatever the system's, so this renders it as the art does.
        composeTestRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.size(312.dp, 188.dp).testTag(FRAME)) {
                    BackupFolderCard(files, BackupArtFrame(copied = true, trimmed = false), "h:mm a")
                }
            }
        }
        composeTestRule.waitForIdle()
        val frame = composeTestRule.onNodeWithTag(FRAME).fetchSemanticsNode().boundsInRoot
        val header = bounds(context.getString(R.string.whats_new_hero_sample_folder))
        val last = bounds(files.builtInLatest)
        assertTrue("header cut off: $header in $frame", header.top >= frame.top)
        assertTrue("last file cut off: $last in $frame", last.bottom <= frame.bottom)
        // An ellipsis would hide the date stamp the picture is about.
        (files.oldCopies + files.newCopy + files.latest).forEach { name ->
            assertFalse("$name is cut off", overflows(name))
        }
    }

    @Test
    fun `print card heads the page with the week holding now and page 1 of 2`() {
        render(WhatsNewHero.PRINT_WEEK, playing = true, motionEnabled = false)
        // In en-US the week starts on Sunday, the day [now] falls on.
        exists(formatPrintWeekLabel(context, LocalDate.of(2026, 10, 11)))
        exists(context.getString(R.string.print_doc_page_of, "1", "2"))
        exists(context.getString(R.string.print_page_details))
        val sunday = LocalDate.of(2026, 10, 11)
        val separator = context.getString(R.string.recent_changes_detail_separator)
        whatsNewSamplePrintEvents(context.resources).forEach {
            val day = sunday.plusDays(it.day.toLong()).dayOfWeek
                .getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)
            exists("${it.number}. $day$separator${it.title}")
        }
    }

    @Test
    fun `print card ends with both pages out`() {
        assertEquals(PrintArtFrame(detailsOut = false, weekOut = false), printArtFrame(0))
        assertEquals(PrintArtFrame(detailsOut = true, weekOut = false), printArtFrame(1))
        assertEquals(PrintArtFrame(detailsOut = true, weekOut = true), printArtFrame(PRINT_ART_FRAMES.lastIndex))
    }

    @Test
    fun `print card off screen rests before printing`() {
        render(WhatsNewHero.PRINT_WEEK, playing = false, motionEnabled = true)
        composeTestRule.onNodeWithText(printedWeekLabel(), useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.print_page_details), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `TalkBack reads none of the print card`() {
        render(WhatsNewHero.PRINT_WEEK, playing = true, motionEnabled = false)
        composeTestRule.onNodeWithText(printedWeekLabel()).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.print_page_details)).assertDoesNotExist()
    }

    @Test
    fun `print card fits its frame at large text`() {
        assertPrintCardFits()
    }

    @Test
    @Config(qualifiers = "de-w360dp-h9999dp-mdpi")
    fun `print card fits its frame in German`() {
        assertPrintCardFits()
    }

    /** The heading the printed page shows for [now]'s week, in the test's locale. */
    private fun printedWeekLabel(): String =
        formatPrintWeekLabel(context, WeekViewUtils.getWeekStart(LocalDate.of(2026, 10, 11), 0))

    private fun assertPrintCardFits() {
        render(WhatsNewHero.PRINT_WEEK, playing = true, motionEnabled = false, fontScale = 2f)
        val frame = composeTestRule.onNodeWithTag(FRAME).fetchSemanticsNode().boundsInRoot
        val label = bounds(printedWeekLabel())
        assertTrue("week label above the frame: $label in $frame", label.top >= frame.top)
        assertFalse("week label is cut off", overflows(printedWeekLabel()))
        assertFalse("page footer is cut off", overflows(context.getString(R.string.print_doc_page_of, "1", "2")))
    }

    private companion object {
        const val FRAME = "frame"
    }
}
