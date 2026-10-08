package org.onekash.kashcal.ui.appicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Tests the app-icon model without Robolectric: preset labels and previews, alias resolution, the
 * alias list, the Default companion and the enable/disable switch plan. The PackageManager
 * component-state side is in [AppIconUtilityTest].
 */
class AppIconPresetTest {

    private val sampleDays = listOf(
        LocalDate.of(2026, 10, 1),
        LocalDate.of(2026, 2, 28),
        LocalDate.of(2028, 2, 29),
        LocalDate.of(2026, 9, 30),
        LocalDate.of(2026, 10, 31),
    )

    @Test
    fun `every preset exposes a preview and a label`() {
        AppIconPreset.entries.forEach { preset ->
            assertTrue("preview set for $preset", preset.previewForegroundFor(sampleDays[0]) != 0)
            assertTrue("labelRes set for $preset", preset.labelRes != 0)
        }
    }

    @Test
    fun `the date icon previews today's number and static icons their own artwork`() {
        sampleDays.forEach { day ->
            assertEquals(
                DayIcons.foregroundFor(day.dayOfMonth),
                AppIconPreset.TODAYS_DATE.previewForegroundFor(day),
            )
        }
        assertEquals(
            org.onekash.kashcal.R.mipmap.ic_launcher_foreground,
            AppIconPreset.DEFAULT.previewForegroundFor(sampleDays[0]),
        )
        assertEquals(
            org.onekash.kashcal.R.mipmap.ic_launcher_supporter_foreground,
            AppIconPreset.SUPPORTER.previewForegroundFor(sampleDays[0]),
        )
    }

    @Test
    fun `static presets resolve to their own fixed alias on any date`() {
        val expected = mapOf(
            AppIconPreset.DEFAULT to ".MainActivityDefault",
            AppIconPreset.SUPPORTER to ".MainActivitySupporter",
            AppIconPreset.SUPPORTER_CALENDAR to ".MainActivitySupporterCalendar",
        )
        expected.forEach { (preset, suffix) ->
            sampleDays.forEach { day -> assertEquals(suffix, preset.aliasSuffixFor(day)) }
        }
    }

    @Test
    fun `the date preset resolves to the alias for the day of the month`() {
        sampleDays.forEach { day ->
            assertEquals(
                ".MainActivityDay${day.dayOfMonth}",
                AppIconPreset.TODAYS_DATE.aliasSuffixFor(day),
            )
        }
    }

    @Test
    fun `the alias list holds the three static aliases and one per day, all distinct`() {
        assertEquals(34, LauncherAliases.ALL.size)
        assertEquals(34, LauncherAliases.ALL.toSet().size)
        assertTrue(LauncherAliases.ALL.containsAll((1..31).map { ".MainActivityDay$it" }))
        assertTrue(LauncherAliases.ALL.contains(".MainActivityDefault"))
    }

    @Test
    fun `the two supporter variants share one icon but differ in label`() {
        assertEquals(
            "supporter variants share the same preview icon",
            AppIconPreset.SUPPORTER.previewForegroundFor(sampleDays[0]),
            AppIconPreset.SUPPORTER_CALENDAR.previewForegroundFor(sampleDays[0]),
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
    fun `switch plan enables the target's alias and disables every other alias`() {
        AppIconPreset.entries.forEach { target ->
            sampleDays.forEach { day ->
                val plan = AppIconSwitchPlan.forTarget(target, day)
                assertEquals(target.aliasSuffixFor(day), plan.toEnable)
                assertEquals(
                    "toDisable must be all other aliases for $target on $day",
                    LauncherAliases.ALL.filter { it != plan.toEnable }.toSet(),
                    plan.toDisable.toSet(),
                )
            }
        }
    }

    @Test
    fun `switch plan covers every alias exactly once`() {
        AppIconPreset.entries.forEach { target ->
            sampleDays.forEach { day ->
                val plan = AppIconSwitchPlan.forTarget(target, day)
                val covered = plan.toDisable + plan.toEnable
                assertEquals(LauncherAliases.ALL.toSet(), covered.toSet())
                assertEquals("no alias counted twice", LauncherAliases.ALL.size, covered.size)
            }
        }
    }
}
