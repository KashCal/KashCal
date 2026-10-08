package org.onekash.kashcal.ui.appicon

import android.content.ComponentName
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Tests [AppIconUtility]'s component state against Robolectric's PackageManager, at API 32 (one
 * call per component) and API 34 (one batch): fresh-install detection, switching between the
 * static presets and the date icon, keeping exactly one alias enabled, the date refresh, and the
 * lock that serializes switches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34])
class AppIconUtilityTest {

    private val context = RuntimeEnvironment.getApplication()
    private val pm: PackageManager = context.packageManager
    private val oct7 = LocalDate.of(2026, 10, 7)
    private val oct8 = LocalDate.of(2026, 10, 8)

    private fun component(suffix: String) = ComponentName(context, context.packageName + suffix)

    private fun stateOf(suffix: String): Int = pm.getComponentEnabledSetting(component(suffix))

    private fun enabledAliases(): List<String> = LauncherAliases.ALL.filter {
        stateOf(it) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (it == DEFAULT_ALIAS && stateOf(it) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
    }

    private fun disableEverything() {
        LauncherAliases.ALL.forEach {
            pm.setComponentEnabledSetting(
                component(it),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    /** Records every write, then applies it to the real PackageManager so reads stay true. */
    private class RecordingWriter(
        private val delegate: ComponentStateWriter,
        private val beforeWrite: () -> Unit = {},
    ) : ComponentStateWriter {
        val batches = mutableListOf<List<Pair<ComponentName, Int>>>()
        val singles = mutableListOf<Pair<ComponentName, Int>>()

        override fun setAll(changes: List<Pair<ComponentName, Int>>) {
            beforeWrite()
            synchronized(this) { batches += changes }
            delegate.setAll(changes)
        }

        override fun setOne(component: ComponentName, state: Int) {
            beforeWrite()
            synchronized(this) { singles += component to state }
            delegate.setOne(component, state)
        }

        val writeCount: Int get() = synchronized(this) { batches.size + singles.size }
    }

    private fun realWriter() = PackageManagerComponentStateWriter(pm)

    @Test
    fun `fresh install reports DEFAULT as the active preset`() {
        // DEFAULT ships without android:enabled, so its state is COMPONENT_ENABLED_STATE_DEFAULT.
        assertEquals(AppIconPreset.DEFAULT, AppIconUtility(context).currentPreset())
    }

    @Test
    fun `switching to a supporter icon makes it the active preset`() {
        val utility = AppIconUtility(context)
        utility.setAppIcon(AppIconPreset.SUPPORTER, oct7)
        assertEquals(AppIconPreset.SUPPORTER, utility.currentPreset())
        assertEquals(listOf(".MainActivitySupporter"), enabledAliases())
    }

    @Test
    fun `switching to the date icon enables only today's day alias`() {
        val utility = AppIconUtility(context)
        utility.setAppIcon(AppIconPreset.TODAYS_DATE, oct7)
        assertEquals(AppIconPreset.TODAYS_DATE, utility.currentPreset())
        assertEquals(listOf(".MainActivityDay7"), enabledAliases())
    }

    @Test
    fun `exactly one alias is enabled after any switch`() {
        val utility = AppIconUtility(context)
        val days = listOf(oct7, LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1))
        AppIconPreset.entries.forEach { target ->
            days.forEach { day ->
                utility.setAppIcon(target, day)
                assertEquals(
                    "after switching to $target on $day",
                    listOf(target.aliasSuffixFor(day)),
                    enabledAliases(),
                )
            }
        }
    }

    @Test
    fun `currentPreset reports DEFAULT without writing when every alias is disabled`() {
        disableEverything()
        val writer = RecordingWriter(realWriter())

        assertEquals(AppIconPreset.DEFAULT, AppIconUtility(context, writer).currentPreset())
        assertEquals(0, writer.writeCount)
        assertTrue(enabledAliases().isEmpty())
    }

    @Test
    fun `switching to DEFAULT repairs the all-disabled state`() {
        disableEverything()
        val utility = AppIconUtility(context)

        utility.setAppIcon(AppIconPreset.DEFAULT, oct7)

        assertEquals(AppIconPreset.DEFAULT, utility.currentPreset())
        assertEquals(listOf(DEFAULT_ALIAS), enabledAliases())
    }

    // ---- write order and batching ----

    @Test
    // The batch call exists from API 33, so this only runs on the newer runtime.
    @Config(sdk = [34])
    fun `on API 33 and later a switch is one batch`() {
        val writer = RecordingWriter(realWriter())
        AppIconUtility(context, writer, sdkInt = 33).setAppIcon(AppIconPreset.TODAYS_DATE, oct7)

        assertEquals(0, writer.singles.size)
        assertEquals(1, writer.batches.size)
        val batch = writer.batches.single()
        assertEquals(34, batch.size)
        assertEquals(
            component(".MainActivityDay7") to PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            batch.first(),
        )
        assertTrue(
            batch.drop(1).all { it.second == PackageManager.COMPONENT_ENABLED_STATE_DISABLED }
        )
    }

    @Test
    fun `on API 31 and 32 the target is enabled before any alias is disabled`() {
        val writer = RecordingWriter(realWriter())
        AppIconUtility(context, writer, sdkInt = 32).setAppIcon(AppIconPreset.SUPPORTER, oct7)

        assertEquals(0, writer.batches.size)
        assertEquals(34, writer.singles.size)
        assertEquals(
            component(".MainActivitySupporter") to PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            writer.singles.first(),
        )
        assertTrue(
            writer.singles.drop(1).all { it.second == PackageManager.COMPONENT_ENABLED_STATE_DISABLED }
        )
    }

    // ---- date refresh ----

    @Test
    fun `refresh moves the date icon to the new day across a month end`() {
        val utility = AppIconUtility(context)
        utility.setAppIcon(AppIconPreset.TODAYS_DATE, LocalDate.of(2026, 10, 31))

        utility.refreshDateIcon(LocalDate.of(2026, 11, 1))

        assertEquals(listOf(".MainActivityDay1"), enabledAliases())
    }

    @Test
    fun `refresh writes nothing when the date icon is off`() {
        val writer = RecordingWriter(realWriter())
        val utility = AppIconUtility(context, writer)

        // Fresh install: DEFAULT in its manifest state.
        utility.refreshDateIcon(oct8)
        assertEquals(0, writer.writeCount)

        AppIconUtility(context).setAppIcon(AppIconPreset.SUPPORTER, oct7)
        utility.refreshDateIcon(oct8)
        assertEquals(0, writer.writeCount)
        assertEquals(listOf(".MainActivitySupporter"), enabledAliases())
    }

    @Test
    fun `refresh writes nothing when the icon already shows today`() {
        AppIconUtility(context).setAppIcon(AppIconPreset.TODAYS_DATE, oct8)
        val writer = RecordingWriter(realWriter())

        AppIconUtility(context, writer).refreshDateIcon(oct8)

        assertEquals(0, writer.writeCount)
    }

    @Test
    fun `refresh repairs the all-disabled state by enabling DEFAULT`() {
        disableEverything()

        AppIconUtility(context).refreshDateIcon(oct8)

        assertEquals(listOf(DEFAULT_ALIAS), enabledAliases())
    }

    @Test
    fun `refresh writes nothing when it may not switch`() {
        AppIconUtility(context).setAppIcon(AppIconPreset.TODAYS_DATE, oct7)
        val writer = RecordingWriter(realWriter())

        val switched = AppIconUtility(context, writer).refreshDateIcon(oct8) { false }

        assertFalse(switched)
        assertEquals(0, writer.writeCount)
        assertEquals(listOf(".MainActivityDay7"), enabledAliases())
    }

    @Test
    fun `refresh doesn't ask whether it may switch when nothing needs switching`() {
        AppIconUtility(context).setAppIcon(AppIconPreset.TODAYS_DATE, oct8)
        var asked = false

        AppIconUtility(context).refreshDateIcon(oct8) { asked = true; true }

        assertFalse(asked)
    }

    @Test
    fun `restoring the default icon from the all-disabled state doesn't wait`() {
        disableEverything()

        AppIconUtility(context).refreshDateIcon(oct8) { false }

        assertEquals(listOf(DEFAULT_ALIAS), enabledAliases())
    }

    private fun enableOnly(vararg suffixes: String) {
        disableEverything()
        suffixes.forEach {
            pm.setComponentEnabledSetting(
                component(it),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    @Test
    fun `refresh repairs a static and a day alias both enabled to the static one`() {
        // The state an interrupted date-to-default switch on API 31-32 would leave.
        enableOnly(DEFAULT_ALIAS, ".MainActivityDay7")
        AppIconUtility(context).refreshDateIcon(oct8)
        assertEquals(listOf(DEFAULT_ALIAS), enabledAliases())

        enableOnly(".MainActivitySupporter", ".MainActivityDay8")
        AppIconUtility(context).refreshDateIcon(oct8)
        assertEquals(listOf(".MainActivitySupporter"), enabledAliases())
    }

    @Test
    fun `refresh leaves two day aliases as today's only`() {
        enableOnly(".MainActivityDay7", ".MainActivityDay8")

        AppIconUtility(context).refreshDateIcon(oct8)

        assertEquals(listOf(".MainActivityDay8"), enabledAliases())
    }

    // ---- lock ----

    @Test
    fun `concurrent switches always end with exactly one alias enabled`() {
        repeat(10) { round ->
            val writer = RecordingWriter(realWriter(), beforeWrite = { Thread.sleep(1) })
            val a = AppIconUtility(context, writer, sdkInt = 32)
            val b = AppIconUtility(context, writer, sdkInt = 32)
            val t1 = thread { a.setAppIcon(AppIconPreset.SUPPORTER, oct7) }
            val t2 = thread { b.setAppIcon(AppIconPreset.TODAYS_DATE, oct8) }
            t1.join(10_000)
            t2.join(10_000)
            assertEquals("round $round", 1, enabledAliases().size)
        }
    }

    @Test
    fun `a refresh in progress can't re-enable the date icon after the user picks DEFAULT`() {
        AppIconUtility(context).setAppIcon(AppIconPreset.TODAYS_DATE, oct7)
        val refreshWriting = CountDownLatch(1)
        val release = CountDownLatch(1)
        var blockOnce = true
        val writer = RecordingWriter(realWriter(), beforeWrite = {
            if (blockOnce) {
                blockOnce = false
                refreshWriting.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        })
        val refresher = AppIconUtility(context, writer, sdkInt = 32)

        val refresh = thread { refresher.refreshDateIcon(oct8) }
        assertTrue(refreshWriting.await(10, TimeUnit.SECONDS))
        val pick = thread { AppIconUtility(context, sdkInt = 32).setAppIcon(AppIconPreset.DEFAULT, oct8) }
        pick.join(300)
        assertTrue("the user's pick waits for the refresh to finish", pick.isAlive)

        release.countDown()
        refresh.join(10_000)
        pick.join(10_000)

        assertFalse(pick.isAlive)
        assertEquals(listOf(DEFAULT_ALIAS), enabledAliases())
    }

    private companion object {
        const val DEFAULT_ALIAS = ".MainActivityDefault"
    }
}
