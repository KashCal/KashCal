package org.onekash.kashcal.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [shouldShowReadOnlyOptionalField], which decides whether an optional event field
 * (location, notes) renders on the form.
 *
 * In editable mode the row always shows, with its placeholder, so the user can add a value.
 * In read-only mode an empty row would invite an edit the viewer can't make, so a blank or
 * whitespace-only value hides it; a present value shows as read-only text.
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
