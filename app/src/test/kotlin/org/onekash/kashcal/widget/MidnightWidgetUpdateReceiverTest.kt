package org.onekash.kashcal.widget

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the midnight widget refresh still runs when the platform refuses to
 * re-arm tomorrow's alarm because the app is at its 500 pending-alarm limit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MidnightWidgetUpdateReceiverTest {

    private lateinit var alarmManager: AlarmManager
    private lateinit var manager: WidgetUpdateManager

    @Before
    fun setup() {
        alarmManager = mockk()
        every { alarmManager.canScheduleExactAlarms() } returns true
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws
            IllegalStateException("Maximum limit of concurrent alarms 500 reached")

        val context = spyk(ApplicationProvider.getApplicationContext<Context>())
        every { context.getSystemService(Context.ALARM_SERVICE) } returns alarmManager
        manager = spyk(WidgetUpdateManager(context))
        coEvery { manager.updateAllWidgets(any()) } just runs
    }

    @Test
    fun `midnight refresh still updates widgets when re-arming the alarm is refused`() = runBlocking {
        MidnightWidgetUpdateReceiver().handleMidnight(manager, pendingResult = null).join()

        verify(exactly = 1) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
        coVerify(exactly = 1) { manager.updateAllWidgets("midnight") }
    }
}
