package org.onekash.kashcal.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.ui.viewmodels.EditScope

/**
 * Unit tests for the [ScopeOption] data class and the [ScopeTint]
 * enum that drive [RecurringScopeSheet] rendering. The sheet itself
 * is a thin Composable that maps the option list to cards; its
 * rendering is exercised by Compose previews. The interesting state —
 * which scopes are enabled, which tint applies, which icon — is data
 * that lives on [ScopeOption].
 *
 * The tinting decisions reflect the design: edit's "All events" gets
 * Warn (subtle visual brake), delete's "All events" gets Destructive
 * (genuinely removes data). Disabled cards stay visible at reduced
 * opacity — no explanatory sub-copy is rendered.
 */
class RecurringScopeSheetTest {

    private val testIcon = Icons.Default.CalendarToday

    @Test
    fun `ScopeOption defaults to neutral tint`() {
        val option = ScopeOption(
            scope = EditScope.THIS_EVENT,
            label = "This event",
            icon = testIcon,
            enabled = true,
        )
        assertEquals(ScopeTint.Neutral, option.tint)
    }

    @Test
    fun `ScopeOption can be tinted Warn`() {
        val option = ScopeOption(
            scope = EditScope.ALL_EVENTS,
            label = "All events",
            icon = testIcon,
            enabled = true,
            tint = ScopeTint.Warn,
        )
        assertEquals(ScopeTint.Warn, option.tint)
    }

    @Test
    fun `ScopeOption can be tinted Destructive`() {
        val option = ScopeOption(
            scope = EditScope.ALL_EVENTS,
            label = "All events",
            icon = testIcon,
            enabled = true,
            tint = ScopeTint.Destructive,
        )
        assertEquals(ScopeTint.Destructive, option.tint)
    }

    @Test
    fun `ScopeOption disabled honors enabled flag`() {
        val option = ScopeOption(
            scope = EditScope.THIS_AND_FUTURE,
            label = "This and future",
            icon = testIcon,
            enabled = false,
        )
        assertFalse(option.enabled)
    }

    @Test
    fun `ScopeOption carries an icon for the sheet's tile`() {
        val option = ScopeOption(
            scope = EditScope.THIS_EVENT,
            label = "This event",
            icon = testIcon,
            enabled = true,
        )
        // The sheet renders an icon-tile alongside the label; the
        // option's icon must be a non-null ImageVector.
        @Suppress("USELESS_IS_CHECK")
        assertTrue(option.icon is androidx.compose.ui.graphics.vector.ImageVector)
    }

    @Test
    fun `EditScope enum exposes all three scopes`() {
        val values = EditScope.entries.map { it.name }
        assertTrue("THIS_EVENT present", values.contains("THIS_EVENT"))
        assertTrue("THIS_AND_FUTURE present", values.contains("THIS_AND_FUTURE"))
        assertTrue("ALL_EVENTS present", values.contains("ALL_EVENTS"))
        assertEquals(3, EditScope.entries.size)
    }

    @Test
    fun `ScopeTint enum exposes Neutral Warn Destructive`() {
        val values = ScopeTint.entries.map { it.name }
        assertEquals(setOf("Neutral", "Warn", "Destructive"), values.toSet())
    }

    // Option-tap contract: tapping a scope option commits that scope
    // and nothing else. The host (MainActivity) is responsible for
    // dismissing the sheet by clearing the pending state — the card
    // does NOT also fire the cancel callback. Firing both used to
    // race a `signalFormSaveFailed` against an in-flight save and
    // re-enable the form's Save button mid-flight.

    @Test
    fun `tapping an enabled option commits the scope`() {
        val option = ScopeOption(
            scope = EditScope.THIS_AND_FUTURE,
            label = "This and future",
            icon = testIcon,
            enabled = true,
        )
        var selected: EditScope? = null
        scopeOptionTap(option) { selected = it }
        assertEquals(EditScope.THIS_AND_FUTURE, selected)
    }

    @Test
    fun `tapping a disabled option commits nothing`() {
        val option = ScopeOption(
            scope = EditScope.THIS_AND_FUTURE,
            label = "This and future",
            icon = testIcon,
            enabled = false,
        )
        var selected: EditScope? = null
        scopeOptionTap(option) { selected = it }
        assertNull(selected)
    }
}
