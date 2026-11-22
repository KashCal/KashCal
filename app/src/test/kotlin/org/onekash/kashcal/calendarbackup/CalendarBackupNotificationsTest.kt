package org.onekash.kashcal.calendarbackup

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.SettingsActivity
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarBackupNotificationsTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var notifications: CalendarBackupNotifications

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications = CalendarBackupNotifications(context)
        notifications.createChannel()
    }

    private fun shown() = shadowOf(manager).getNotification(
        CalendarBackupNotifications.NOTIFICATION_TAG, CalendarBackupNotifications.NOTIFICATION_ID
    )

    @Test
    fun `the channel is Automatic calendar backup at default importance`() {
        val channel = manager.getNotificationChannel(CalendarBackupNotifications.CHANNEL_ID)
        assertEquals("Automatic calendar backup", channel.name)
        assertEquals("Tells you when a calendar backup fails", channel.description)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun `a lost folder posts its text and a tap opens the backup sheet in Settings`() {
        notifications.post(BackupAlert.FolderUnreachable("KashCal backups"))

        val notification = shown()
        assertNotNull(notification)
        assertEquals("Calendar backup failed", notification.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals(
            "Can't reach KashCal backups. Tap to choose it again.",
            notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString(),
        )
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(SettingsActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(SettingsActivity.EXTRA_OPEN_CALENDAR_BACKUP, false))
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
        assertTrue(shadowOf(notification.contentIntent).isImmutable)
    }

    @Test
    fun `failure texts name the folder or the files`() {
        fun text(alert: BackupAlert): String {
            notifications.post(alert)
            return shown().extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString()
        }
        assertEquals(
            "Can't save files in Drive. Tap to choose another folder.",
            text(BackupAlert.FolderUnsupported("Drive")),
        )
        assertEquals("Couldn't save Trips.ics. KashCal will try again at the next backup.", text(BackupAlert.CalendarsFailed(listOf("Trips.ics"))))
        assertEquals(
            "Couldn't save 2 calendars. KashCal will try again at the next backup.",
            text(BackupAlert.CalendarsFailed(listOf("Trips.ics", "Work.ics"))),
        )
        assertEquals(
            "Can't reach the backup folder. Tap to choose it again.",
            text(BackupAlert.FolderUnreachable(null)),
        )
    }

    @Test
    fun `cancel removes only the backup notification`() {
        val other = androidx.core.app.NotificationCompat.Builder(context, CalendarBackupNotifications.CHANNEL_ID)
            .setSmallIcon(org.onekash.kashcal.R.drawable.ic_notification)
            .build()
        manager.notify(CalendarBackupNotifications.NOTIFICATION_ID, other)
        notifications.post(BackupAlert.FolderUnreachable("x"))
        notifications.cancel()
        assertNull(shown())
        assertNotNull(shadowOf(manager).getNotification(null, CalendarBackupNotifications.NOTIFICATION_ID))
    }

    @Test
    fun `nothing can be posted with notifications off or without the channel`() {
        assertTrue(notifications.canPost())
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(notifications.canPost())
        shadowOf(manager).setNotificationsEnabled(true)
        manager.deleteNotificationChannel(CalendarBackupNotifications.CHANNEL_ID)
        assertFalse(notifications.canPost())
    }

    @Test
    fun `the state tells app-level off from the backup channel off`() {
        assertEquals(BackupNotificationState.ON, notifications.notificationState())
        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals(BackupNotificationState.APP_OFF, notifications.notificationState())
        shadowOf(manager).setNotificationsEnabled(true)
        manager.getNotificationChannel(CalendarBackupNotifications.CHANNEL_ID).let {
            it.importance = NotificationManager.IMPORTANCE_NONE
            manager.createNotificationChannel(it)
        }
        assertEquals(BackupNotificationState.CHANNEL_OFF, notifications.notificationState())
    }

    @Test
    fun `a missing channel reads as the channel off`() {
        manager.deleteNotificationChannel(CalendarBackupNotifications.CHANNEL_ID)
        assertEquals(BackupNotificationState.CHANNEL_OFF, notifications.notificationState())
    }
}
