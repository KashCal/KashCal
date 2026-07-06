package org.onekash.kashcal.ui.appicon

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Component-state behavior of [AppIconUtility] against a real (Robolectric) PackageManager:
 * fresh-install detection, switching, keeping exactly one alias enabled, and self-heal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppIconUtilityTest {

    private val context = RuntimeEnvironment.getApplication()
    private val pm: PackageManager = context.packageManager
    private val utility = AppIconUtility(context)

    private fun stateOf(preset: AppIconPreset): Int =
        pm.getComponentEnabledSetting(preset.componentName(context))

    @Test
    fun `fresh install reports DEFAULT as the active preset`() {
        // DEFAULT ships without android:enabled, so its state is COMPONENT_ENABLED_STATE_DEFAULT.
        assertEquals(AppIconPreset.DEFAULT, utility.currentPreset())
    }

    @Test
    fun `switching to a supporter icon makes it the active preset`() {
        utility.setAppIcon(AppIconPreset.SUPPORTER)
        assertEquals(AppIconPreset.SUPPORTER, utility.currentPreset())
    }

    @Test
    fun `switching enables exactly the target and disables all others`() {
        utility.setAppIcon(AppIconPreset.SUPPORTER)

        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            stateOf(AppIconPreset.SUPPORTER),
        )
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            stateOf(AppIconPreset.DEFAULT),
        )
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            stateOf(AppIconPreset.SUPPORTER_CALENDAR),
        )
    }

    @Test
    fun `switching back to default enables default and disables supporters`() {
        utility.setAppIcon(AppIconPreset.SUPPORTER)
        utility.setAppIcon(AppIconPreset.DEFAULT)

        assertEquals(AppIconPreset.DEFAULT, utility.currentPreset())
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            stateOf(AppIconPreset.SUPPORTER),
        )
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            stateOf(AppIconPreset.SUPPORTER_CALENDAR),
        )
    }

    @Test
    fun `only one alias is ever enabled after a switch`() {
        AppIconPreset.entries.forEach { target ->
            utility.setAppIcon(target)
            val enabledCount = AppIconPreset.entries.count {
                stateOf(it) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            // The target is explicitly ENABLED; every other is explicitly DISABLED.
            assertEquals("exactly one alias enabled after switching to $target", 1, enabledCount)
        }
    }

    @Test
    fun `currentPreset reports DEFAULT without writing when every alias is disabled`() {
        // Force the pathological state: nothing enabled.
        AppIconPreset.entries.forEach {
            pm.setComponentEnabledSetting(
                it.componentName(context),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        // Reading is pure: it reports DEFAULT but must NOT re-enable anything as a side effect.
        assertEquals(AppIconPreset.DEFAULT, utility.currentPreset())
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            stateOf(AppIconPreset.DEFAULT),
        )
    }

    @Test
    fun `switching to DEFAULT repairs the all-disabled state`() {
        AppIconPreset.entries.forEach {
            pm.setComponentEnabledSetting(
                it.componentName(context),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        utility.setAppIcon(AppIconPreset.DEFAULT)

        assertEquals(AppIconPreset.DEFAULT, utility.currentPreset())
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            stateOf(AppIconPreset.DEFAULT),
        )
    }
}
