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
 * Tests the data behind [RecurringScopeSheet]: [ScopeOption]'s default tint and fields, the
 * [ScopeTint] and [EditScope] entries, and [scopeOptionTap]. The sheet maps the options to
 * cards and isn't rendered here; which scope is enabled, its tint and its icon live on
 * [ScopeOption].
 *
 * Which option gets which tint is documented on [ScopeTint], and how a disabled card looks on
 * [ScopeOption] (not asserted here).
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

    // Tapping an enabled option commits its scope and nothing else; a disabled one commits
    // nothing. Why the tap never cancels is documented on [scopeOptionTap].

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
