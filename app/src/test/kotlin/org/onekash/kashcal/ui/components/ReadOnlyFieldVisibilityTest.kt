package org.onekash.kashcal.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [shouldShowReadOnlyOptionalField], the pure predicate deciding
 * whether an optional event field (location, notes) renders on the form.
 *
 * In editable mode the row is always shown so the user can add a value
 * (it carries an "Add …" placeholder). In read-only / attendee-viewer mode
 * an empty field is just dead space with a misleading "Add" affordance, so
 * a blank value hides the row entirely; a present value still shows (as
 * read-only display text).
 */
class ReadOnlyFieldVisibilityTest {

    @Test
    fun `editable mode always shows the field, even when blank`() {
        assertTrue(shouldShowReadOnlyOptionalField(value = "", isReadOnly = false))
        assertTrue(shouldShowReadOnlyOptionalField(value = "Room 4B", isReadOnly = false))
    }

    @Test
    fun `read-only mode shows a field that has a value`() {
        assertTrue(shouldShowReadOnlyOptionalField(value = "Room 4B", isReadOnly = true))
    }

    @Test
    fun `read-only mode hides an empty field`() {
        assertFalse(shouldShowReadOnlyOptionalField(value = "", isReadOnly = true))
    }

    @Test
    fun `read-only mode treats whitespace-only as empty and hides it`() {
        assertFalse(shouldShowReadOnlyOptionalField(value = "   ", isReadOnly = true))
        assertFalse(shouldShowReadOnlyOptionalField(value = "\n\t", isReadOnly = true))
    }
}
