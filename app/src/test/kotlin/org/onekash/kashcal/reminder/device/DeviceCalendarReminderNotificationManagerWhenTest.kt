package org.onekash.kashcal.reminder.device

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.reminder.notification.ReminderNotificationChannels
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceCalendarReminderNotificationManagerWhenTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var context: Context
    private lateinit var channels: ReminderNotificationChannels
    private lateinit var dataStore: KashCalDataStore
    private lateinit var manager: DeviceCalendarReminderNotificationManager
    private lateinit var testDataStoreFile: File

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        context = RuntimeEnvironment.getApplication()
        channels = ReminderNotificationChannels(context)
        channels.createChannels()

        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        testDataStoreFile = File(context.filesDir, "test_prefs_${System.nanoTime()}.preferences_pb")
        val testPrefsDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope
        ) { testDataStoreFile }
        dataStore = KashCalDataStore(context, testPrefsDataStore)
        manager = DeviceCalendarReminderNotificationManager(context, channels, dataStore)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
        unmockkAll()
        testDataStoreFile.delete()
    }

    @Test
    fun `buildNotification sets when to occurrenceTs plus 30s rounding offset`() = runBlocking {
        val occurrenceTs = System.currentTimeMillis() + 900_000L
        val triggerTime = occurrenceTs - 900_000L

        val notification = manager.buildNotification(
            eventId = 100L,
            occurrenceTs = occurrenceTs,
            title = "Test Meeting",
            location = null,
            isAllDay = false,
            calendarColor = 0xFF6200EE.toInt(),
            calendarId = 1L,
            triggerTime = triggerTime,
            notificationId = 20001
        )

        assertEquals(
            "setWhen should include 30s rounding offset to convert Android's floor to round",
            occurrenceTs + 30_000L,
            notification.`when`
        )
    }
}
