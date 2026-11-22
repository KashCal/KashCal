package org.onekash.kashcal.ui.screens.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.calendarbackup.BackupCalendarRow
import org.onekash.kashcal.calendarbackup.BackupNotificationState
import org.onekash.kashcal.calendarbackup.CalendarBackupNotifications
import org.onekash.kashcal.calendarbackup.CalendarBackupResult
import org.onekash.kashcal.calendarbackup.CalendarBackupSettings
import org.onekash.kashcal.calendarbackup.CalendarBackupStatus
import org.onekash.kashcal.calendarbackup.CalendarBackupUiModel
import org.onekash.kashcal.calendarbackup.FolderFailure
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.onekash.kashcal.ui.viewmodels.KeepAsk
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the Automatic calendar backup sheet and its Settings row subtitle: the
 * first-time intro, the switch with the last backup and Back up now under it, the
 * notifications-off hint, the folder, the inline interval picker, the step-down confirm, a folder
 * problem, empty and failed calendars and the off state. Times render as "T<millis>" so
 * assertions stay
 * independent of the locale's date format. Run the class alone: Robolectric runs of more than one
 * class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h2000dp-mdpi")
class CalendarBackupSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class Calls {
        var chooseFolder = 0
        val enabled = mutableListOf<Boolean>()
        val intervals = mutableListOf<Int>()
        val keeps = mutableListOf<Int>()
        var confirmKeep = 0
        var cancelKeep = 0
        var backUpNow = 0
        val notificationSettings = mutableListOf<String?>()
        var notNow = 0
    }

    private val tree = "content://test/tree/backups"
    private val on = CalendarBackupSettings(enabled = true, folderUri = tree, folderName = "KashCal backups")

    private fun row(id: Long, name: String, file: String, result: CalendarBackupResult?) =
        BackupCalendarRow(id, name, 0xFF00AA00.toInt(), file, result)

    private val healthyRows = listOf(
        row(1, "Local", "Local.ics", CalendarBackupResult(CalendarBackupResult.Kind.UPDATED, 300, "Local.ics")),
        row(2, "Trips", "Trips.ics", null),
        row(3, "Ideas", "Ideas.ics", CalendarBackupResult(CalendarBackupResult.Kind.EMPTY, 300, "Ideas.ics")),
    )

    private fun model(
        settings: CalendarBackupSettings = on,
        status: CalendarBackupStatus = CalendarBackupStatus(lastCheckedAt = 500),
        running: Boolean = false,
        rows: List<BackupCalendarRow> = healthyRows,
        hint: BackupNotificationState? = null,
    ) = CalendarBackupUiModel(settings, status, running, rows, hint)

    private fun render(
        model: CalendarBackupUiModel = model(),
        pickError: FolderFailure? = null,
        keepAsk: KeepAsk? = null,
        calls: Calls = Calls(),
        timeText: (Long) -> String = { "T$it" },
    ): Calls {
        composeTestRule.setContent {
            KashCalTheme {
                CalendarBackupSheet(
                    model = model,
                    pickError = pickError,
                    keepAsk = keepAsk,
                    timeText = timeText,
                    onChooseFolder = { calls.chooseFolder++ },
                    onSetEnabled = { calls.enabled += it },
                    onSetInterval = { calls.intervals += it },
                    onRequestKeep = { calls.keeps += it },
                    onConfirmKeep = { calls.confirmKeep++ },
                    onCancelKeep = { calls.cancelKeep++ },
                    onBackUpNow = { calls.backUpNow++ },
                    onOpenNotificationSettings = { calls.notificationSettings += it },
                    onDismissNotificationsHint = { calls.notNow++ },
                    onDismiss = {},
                )
            }
        }
        return calls
    }

    private fun text(value: String) = composeTestRule.onNodeWithText(value)

    @Test
    fun `without a folder it explains, offers Choose folder and the hint, and the settings`() {
        val calls = render(model(settings = CalendarBackupSettings(), status = CalendarBackupStatus(), rows = emptyList()))
        text("Saves each offline calendar as an ICS file in a folder you choose.").assertExists()
        text(
            "Choose or create a folder, for example in Documents. Android doesn't allow the top of " +
                "storage or the Download folder itself."
        ).assertExists()
        text("Frequency").assertExists()
        text("Every 12 hours").assertExists()
        text("Copies to keep").assertExists()
        text(FOOTER).assertExists()
        text("Back up now").assertDoesNotExist()
        text("Choose folder").performClick()
        assertEquals(1, calls.chooseFolder)
    }

    @Test
    fun `set up, it shows On, the last backup, Back up now, the folder and file names`() {
        val calls = render()
        text("On").assertExists()
        text("Last backup T500").assertExists()
        text("KashCal backups").assertExists()
        text("Local.ics").assertExists()
        text("Trips.ics").assertExists()
        text("Ideas.ics · No events, nothing saved").assertExists()
        text(FOOTER).performScrollTo().assertExists()
        text("Change").performClick()
        text("Back up now").performClick()
        text("On").performClick()
        assertEquals(1, calls.chooseFolder)
        assertEquals(1, calls.backUpNow)
        assertEquals(listOf(false), calls.enabled)
    }

    @Test
    fun `Back up now sits under the switch, above the folder, and appears once`() {
        render()
        composeTestRule.onAllNodesWithText("Back up now").assertCountEquals(1)
        val switch = text("On").getUnclippedBoundsInRoot()
        val button = text("Back up now").getUnclippedBoundsInRoot()
        val folder = text("Folder").getUnclippedBoundsInRoot()
        assertTrue(switch.bottom <= button.top)
        assertTrue(button.bottom <= folder.top)
    }

    @Test
    fun `the notifications hint sits between Back up now and the folder`() {
        render(model(hint = BackupNotificationState.APP_OFF))
        val button = text("Back up now").getUnclippedBoundsInRoot()
        val hint = composeTestRule.onNodeWithText("Not now").getUnclippedBoundsInRoot()
        val folder = text("Folder").getUnclippedBoundsInRoot()
        assertTrue(button.bottom <= hint.top)
        assertTrue(hint.bottom <= folder.top)
    }

    @Test
    fun `on but never backed up shows no subtitle`() {
        render(model(status = CalendarBackupStatus()))
        composeTestRule.onAllNodesWithText("Last backup", substring = true).assertCountEquals(0)
        text("Backing up…").assertDoesNotExist()
    }

    @Test
    fun `the switch tells TalkBack whether backup is on, what it switches and the last backup`() {
        render()
        composeTestRule.onNodeWithTag(SWITCH_ROW_TAG).assertIsOn()
        composeTestRule.onNodeWithTag(SWITCH_ROW_TAG).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription, listOf("Automatic calendar backup, Last backup T500")
            )
        )
    }

    @Test
    fun `an unreachable folder shows no last backup above its error`() {
        render(model(status = CalendarBackupStatus(lastCheckedAt = 500, folderProblem = FolderFailure.MISSING)))
        text("Last backup T500").assertDoesNotExist()
    }

    @Test
    fun `while off a failed calendar shows only its file`() {
        val rows = listOf(row(2, "Trips", "Trips.ics", CalendarBackupResult(CalendarBackupResult.Kind.FAILED, 500, "Trips.ics")))
        render(model(settings = on.copy(enabled = false), rows = rows))
        text("Trips.ics").assertExists()
        text("Trips.ics · Couldn't save. Will try again at the next backup.").assertDoesNotExist()
    }

    @Test
    fun `the switch reads off when backup is off`() {
        render(model(settings = on.copy(enabled = false)))
        text("Off").assertExists()
        composeTestRule.onNodeWithTag(SWITCH_ROW_TAG).assertIsOff()
    }

    @Test
    fun `while a backup runs the subtitle says so and Back up now is disabled`() {
        render(model(running = true))
        text("Backing up…").assertExists()
        text("Back up now").assertIsNotEnabled()
    }

    @Test
    fun `Frequency opens the picker in place and Done saves the hours`() {
        val calls = render()
        text("Every 12 hours").performClick()
        text("7 days").performClick()
        assertEquals(emptyList<Int>(), calls.intervals)
        text("Done").performScrollTo().performClick()
        assertEquals(listOf(168), calls.intervals)
    }

    @Test
    fun `the stepper asks for one more or one fewer copy`() {
        val calls = render()
        composeTestRule.onNodeWithContentDescription("Keep more copies").performClick()
        composeTestRule.onNodeWithContentDescription("Keep fewer copies").performClick()
        assertEquals(listOf(4, 2), calls.keeps)
    }

    @Test
    fun `TalkBack's adjust action goes through the same request`() {
        val calls = render()
        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        assertEquals(listOf(1), calls.keeps)
    }

    @Test
    fun `a step down that deletes copies asks inline`() {
        val calls = render(keepAsk = KeepAsk(next = 1, removed = 4))
        text("Deletes 4 older copies. This can't be undone.").assertExists()
        text("Delete").performClick()
        text("Cancel").performClick()
        assertEquals(1, calls.confirmKeep)
        assertEquals(1, calls.cancelKeep)
    }

    @Test
    fun `a step down with an unknown count still warns`() {
        render(keepAsk = KeepAsk(next = 1, removed = null))
        text("Deletes the older copies beyond this number. This can't be undone.").assertExists()
    }

    @Test
    fun `a folder problem spans the width with Choose again under it`() {
        val calls = render(model(status = CalendarBackupStatus(lastCheckedAt = 500, folderProblem = FolderFailure.MISSING)))
        val message = "Can't reach KashCal backups. It may have been moved, deleted or disconnected."
        // Unmerged: the problem box merges its text and button into one node for TalkBack.
        val line = composeTestRule.onNodeWithText(message, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val button = composeTestRule.onNodeWithText("Choose again", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(line.bottom <= button.top)
        assertTrue(line.right - line.left > 250.dp)
        text("Choose again").performClick()
        assertEquals(1, calls.chooseFolder)
    }

    @Test
    fun `an unsupported folder says files can't be saved there`() {
        render(model(status = CalendarBackupStatus(folderProblem = FolderFailure.UNSUPPORTED)))
        text("Can't save files in KashCal backups. Choose another folder.").assertExists()
    }

    @Test
    fun `a refused pick says so`() {
        render(pickError = FolderFailure.NO_ACCESS)
        text("KashCal can't use that folder. Choose another one.").assertExists()
    }

    @Test
    fun `a folder that can't hold new files is refused with its reason`() {
        render(pickError = FolderFailure.UNSUPPORTED)
        text("KashCal can't create files in that folder. Choose another one.").assertExists()
    }

    @Test
    fun `a failed calendar shows on its line and in the subtitle, and the switch keeps its place`() {
        val rows = healthyRows.take(1) + row(2, "Trips", "Trips.ics", CalendarBackupResult(CalendarBackupResult.Kind.FAILED, 500, "Trips.ics"))
        render(model(rows = rows), timeText = { "Wednesday, 28 October 2026 at 17:09:00 Central European Time" })
        text("Trips.ics · Couldn't save. Will try again at the next backup.").assertExists()
        val switch = composeTestRule.onNodeWithTag(SWITCH_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(switch.right <= 336.dp)
        assertTrue(switch.right - switch.left > 30.dp)
    }

    @Test
    fun `with KashCal notifications off the hint opens app settings or goes with Not now`() {
        val calls = render(model(hint = BackupNotificationState.APP_OFF))
        text("Notifications are off for KashCal, so it can't notify you if a backup fails.").assertExists()
        text("Open settings").performClick()
        text("Not now").performClick()
        assertEquals(listOf<String?>(null), calls.notificationSettings)
        assertEquals(1, calls.notNow)
    }

    @Test
    fun `with the backup channel off the hint opens that channel`() {
        val calls = render(model(hint = BackupNotificationState.CHANNEL_OFF))
        text("Backup notifications are off, so KashCal can't notify you if a backup fails.").assertExists()
        text("Open settings").performClick()
        assertEquals(listOf<String?>(CalendarBackupNotifications.CHANNEL_ID), calls.notificationSettings)
    }

    @Test
    fun `without a hint there is no notifications row`() {
        render()
        text("Open settings").assertDoesNotExist()
        text("Not now").assertDoesNotExist()
    }

    private fun subtitleOf(model: CalendarBackupUiModel?): Pair<String, Boolean> {
        var result: Pair<String, Boolean>? = null
        composeTestRule.setContent { KashCalTheme { result = calendarBackupRowSubtitle(model) } }
        composeTestRule.waitForIdle()
        return result!!
    }

    private val failedRows = healthyRows + listOf(
        row(4, "Work", "Work.ics", CalendarBackupResult(CalendarBackupResult.Kind.FAILED, 500, "Work.ics")),
        row(5, "Home", "Home.ics", CalendarBackupResult(CalendarBackupResult.Kind.FAILED, 500, "Home.ics")),
    )

    @Test
    fun `the Settings row reads Off until backup is on with a folder`() {
        assertEquals("Off" to false, subtitleOf(model(settings = on.copy(enabled = false), rows = failedRows)))
    }

    @Test
    fun `the Settings row shows how often and where`() {
        assertEquals("Every 12 hours · KashCal backups" to false, subtitleOf(model()))
    }

    @Test
    fun `the Settings row shows a folder problem in the error color`() {
        assertEquals(
            "Can't reach the backup folder" to true,
            subtitleOf(model(status = CalendarBackupStatus(folderProblem = FolderFailure.NO_ACCESS), rows = failedRows)),
        )
    }

    @Test
    fun `the Settings row counts failed calendars in the error color`() {
        assertEquals("2 calendars failed" to true, subtitleOf(model(rows = failedRows)))
    }

    @Test
    fun `off keeps the folder and says the files stay`() {
        render(model(settings = on.copy(enabled = false), hint = null))
        text("Existing files stay in KashCal backups.").assertExists()
        text("KashCal backups").assertExists()
        text("Back up now").assertDoesNotExist()
    }

    private companion object {
        const val FOOTER = "KashCal overwrites these files at each backup, so changes made to them in other " +
            "apps don't last. To restore a calendar, use Import events from file."
    }
}
