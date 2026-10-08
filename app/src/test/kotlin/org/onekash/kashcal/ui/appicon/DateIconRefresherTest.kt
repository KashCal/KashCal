package org.onekash.kashcal.ui.appicon

import android.content.ComponentName
import android.content.pm.PackageManager
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests [DateIconRefresher] over Robolectric's PackageManager with fixed dates, a fake in-use flag
 * and a fake list of the app's tasks (base and top activity): it follows the date,
 * leaves the static icons alone, lands on the right day across month ends, and waits while a
 * KashCal screen is up or another app's screen sits in KashCal's task.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DateIconRefresherTest {

    private val context = RuntimeEnvironment.getApplication()
    private val pm: PackageManager = context.packageManager
    private var anyActivityStarted = false
    private var tasks: List<DateIconRefresher.OwnTask> = emptyList()
    private val refresher = DateIconRefresher(context, { anyActivityStarted }) { tasks }

    private val launcherBase = ComponentName(context, context.packageName + ".MainActivityDay7")

    private fun launcherTask(top: ComponentName?) = DateIconRefresher.OwnTask(launcherBase, top)

    private fun alias(suffix: String) = ComponentName(context, context.packageName + suffix)

    private fun enabledAliases(): List<String> = LauncherAliases.ALL.filter {
        val state = pm.getComponentEnabledSetting(alias(it))
        state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (it == ".MainActivityDefault" && state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
    }

    private fun dateIconOn(day: LocalDate) =
        AppIconUtility(context).setAppIcon(AppIconPreset.TODAYS_DATE, day)

    @Test
    fun `the date icon moves to the new day`() {
        dateIconOn(LocalDate.of(2026, 10, 7))

        refresher.refresh(LocalDate.of(2026, 10, 8))

        assertEquals(listOf(".MainActivityDay8"), enabledAliases())
    }

    @Test
    fun `a single static icon is left alone`() {
        refresher.refresh(LocalDate.of(2026, 10, 8))
        assertEquals(listOf(".MainActivityDefault"), enabledAliases())

        AppIconUtility(context).setAppIcon(AppIconPreset.SUPPORTER, LocalDate.of(2026, 10, 8))
        refresher.refresh(LocalDate.of(2026, 10, 9))
        assertEquals(listOf(".MainActivitySupporter"), enabledAliases())
    }

    @Test
    fun `month ends land on the right day`() {
        val steps = listOf(
            LocalDate.of(2026, 1, 31) to LocalDate.of(2026, 2, 1),
            LocalDate.of(2026, 2, 28) to LocalDate.of(2026, 3, 1),
            LocalDate.of(2028, 2, 28) to LocalDate.of(2028, 2, 29),
            LocalDate.of(2028, 2, 29) to LocalDate.of(2028, 3, 1),
        )
        steps.forEach { (from, to) ->
            dateIconOn(from)
            refresher.refresh(to)
            assertEquals("$from -> $to", listOf(".MainActivityDay${to.dayOfMonth}"), enabledAliases())
        }
    }

    @Test
    fun `waits while a KashCal screen is up, then switches`() {
        dateIconOn(LocalDate.of(2026, 10, 7))
        anyActivityStarted = true

        refresher.refresh(LocalDate.of(2026, 10, 8))
        assertEquals(listOf(".MainActivityDay7"), enabledAliases())

        anyActivityStarted = false
        refresher.refresh(LocalDate.of(2026, 10, 8))
        assertEquals(listOf(".MainActivityDay8"), enabledAliases())
    }

    @Test
    fun `waits while another app's screen sits in KashCal's task`() {
        dateIconOn(LocalDate.of(2026, 10, 7))
        tasks = listOf(launcherTask(ComponentName("com.example.mail", "com.example.mail.Compose")))

        refresher.refresh(LocalDate.of(2026, 10, 8))

        assertEquals(listOf(".MainActivityDay7"), enabledAliases())
    }

    @Test
    fun `an emptied top activity counts as another app's`() {
        dateIconOn(LocalDate.of(2026, 10, 7))
        tasks = listOf(launcherTask(ComponentName("", "")))

        refresher.refresh(LocalDate.of(2026, 10, 8))

        assertEquals(listOf(".MainActivityDay7"), enabledAliases())
    }

    @Test
    fun `a task with no running activity or KashCal on top doesn't hold the switch`() {
        dateIconOn(LocalDate.of(2026, 10, 7))
        tasks = listOf(
            launcherTask(null),
            launcherTask(ComponentName(context, "org.onekash.kashcal.MainActivity")),
        )

        refresher.refresh(LocalDate.of(2026, 10, 8))

        assertEquals(listOf(".MainActivityDay8"), enabledAliases())
    }

    @Test
    fun `a failing task lookup waits and doesn't throw`() {
        dateIconOn(LocalDate.of(2026, 10, 7))
        val failing = DateIconRefresher(context, { false }) { error("task list unavailable") }

        failing.refresh(LocalDate.of(2026, 10, 8))

        assertEquals(listOf(".MainActivityDay7"), enabledAliases())
    }

    @Test
    fun `another app's screen in a task started from a notification doesn't hold the switch`() {
        // Disabling an alias only removes tasks launched from that alias.
        dateIconOn(LocalDate.of(2026, 10, 7))
        tasks = listOf(
            DateIconRefresher.OwnTask(
                base = ComponentName(context, "org.onekash.kashcal.MainActivity"),
                top = ComponentName("com.example.mail", "com.example.mail.Compose"),
            )
        )

        refresher.refresh(LocalDate.of(2026, 10, 8))

        assertEquals(listOf(".MainActivityDay8"), enabledAliases())
    }
}
