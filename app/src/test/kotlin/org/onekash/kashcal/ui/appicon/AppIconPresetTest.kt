package org.onekash.kashcal.ui.appicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the app-icon model: preset metadata and the enable/disable switch plan.
 * No Robolectric / PackageManager — the component-state side lives in AppIconUtilityTest.
 */
class AppIconPresetTest {

    @Test
    fun `every preset exposes a distinct alias suffix, preview, and label`() {
        AppIconPreset.entries.forEach { preset ->
            assertTrue("aliasSuffix set for $preset", preset.aliasSuffix.startsWith("."))
            assertTrue("previewForegroundRes set for $preset", preset.previewForegroundRes != 0)
            assertTrue("labelRes set for $preset", preset.labelRes != 0)
        }
        val suffixes = AppIconPreset.entries.map { it.aliasSuffix }
        assertEquals("alias suffixes must be unique", suffixes.size, suffixes.toSet().size)
    }

    @Test
    fun `the two supporter variants share one icon but differ in label`() {
        assertEquals(
            "supporter variants share the same preview icon",
            AppIconPreset.SUPPORTER.previewForegroundRes,
            AppIconPreset.SUPPORTER_CALENDAR.previewForegroundRes,
        )
        assertNotEquals(
            "supporter variants differ in picker label",
            AppIconPreset.SUPPORTER.labelRes,
            AppIconPreset.SUPPORTER_CALENDAR.labelRes,
        )
    }

    @Test
    fun `Default companion points at DEFAULT`() {
        assertEquals(AppIconPreset.DEFAULT, AppIconPreset.Companion.Default)
    }

    // ---- switch plan ----

    @Test
    fun `switch plan enables the target and disables every other preset`() {
        AppIconPreset.entries.forEach { target ->
            val plan = AppIconSwitchPlan.forTarget(target)
            assertEquals(target, plan.toEnable)
            assertEquals(
                "toDisable must be all other presets",
                AppIconPreset.entries.filter { it != target }.toSet(),
                plan.toDisable.toSet(),
            )
        }
    }

    @Test
    fun `switch plan never disables the target`() {
        AppIconPreset.entries.forEach { target ->
            val plan = AppIconSwitchPlan.forTarget(target)
            assertTrue(
                "target $target must not appear in toDisable",
                plan.toDisable.none { it == target },
            )
        }
    }

    @Test
    fun `switch plan covers exactly all presets with no overlap`() {
        val plan = AppIconSwitchPlan.forTarget(AppIconPreset.SUPPORTER)
        val covered = plan.toDisable + plan.toEnable
        assertEquals(AppIconPreset.entries.toSet(), covered.toSet())
        assertEquals("no preset counted twice", AppIconPreset.entries.size, covered.size)
    }
}
