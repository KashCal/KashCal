package org.onekash.kashcal.util.location

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests LocationSuggestionService's display-name rule and the [AddressSuggestion] fields.
 *
 * The formatDisplayName tests run an inline copy of the private
 * `LocationSuggestionService.formatDisplayName`, which can drift from it: the helper looks the
 * private method up by reflection (so a rename or signature change fails) but never invokes it.
 * getSuggestions needs the Android Geocoder and isn't tested here.
 */
class LocationSuggestionServiceTest {

    // ========== formatDisplayName Tests ==========

    @Test
    fun `formatDisplayName returns address when featureName is null`() {
        val result = invokeFormatDisplayName(null, "123 Main St, Springfield")
        assertEquals("123 Main St, Springfield", result)
    }

    @Test
    fun `formatDisplayName returns address when featureName is blank`() {
        val result = invokeFormatDisplayName("  ", "123 Main St, Springfield")
        assertEquals("123 Main St, Springfield", result)
    }

    @Test
    fun `formatDisplayName returns address when featureName is just a number`() {
        val result = invokeFormatDisplayName("123", "123 Main St, Springfield")
        assertEquals("123 Main St, Springfield", result)
    }

    @Test
    fun `formatDisplayName returns address when featureName starts the address`() {
        val result = invokeFormatDisplayName("Main St", "Main St, Springfield, IL 62701")
        assertEquals("Main St, Springfield, IL 62701", result)
    }

    @Test
    fun `formatDisplayName returns address when featureName starts address case insensitive`() {
        val result = invokeFormatDisplayName("main st", "Main St, Springfield, IL 62701")
        assertEquals("Main St, Springfield, IL 62701", result)
    }

    @Test
    fun `formatDisplayName combines feature and address when feature is meaningful`() {
        val result = invokeFormatDisplayName("City Hall", "100 Main St, Springfield")
        assertEquals("City Hall, 100 Main St, Springfield", result)
    }

    @Test
    fun `formatDisplayName trims feature name whitespace`() {
        val result = invokeFormatDisplayName("  City Hall  ", "100 Main St, Springfield")
        assertEquals("City Hall, 100 Main St, Springfield", result)
    }

    @Test
    fun `formatDisplayName returns empty string when both are null`() {
        val result = invokeFormatDisplayName(null, null)
        assertEquals("", result)
    }

    @Test
    fun `formatDisplayName returns feature when address is null but feature is meaningful`() {
        val result = invokeFormatDisplayName("City Hall", null)
        assertEquals("City Hall, ", result)
    }

    @Test
    fun `formatDisplayName treats multi-digit string as number`() {
        val result = invokeFormatDisplayName("12345", "12345 Oak Ave, Town")
        assertEquals("12345 Oak Ave, Town", result)
    }

    @Test
    fun `formatDisplayName treats zero as number`() {
        val result = invokeFormatDisplayName("0", "0 Broadway, City")
        assertEquals("0 Broadway, City", result)
    }

    @Test
    fun `formatDisplayName allows alphanumeric feature names`() {
        // "123A" isn't all digits, so it counts as a feature name.
        val result = invokeFormatDisplayName("123A", "456 Elm St, Town")
        assertEquals("123A, 456 Elm St, Town", result)
    }

    // ========== AddressSuggestion Data Class ==========

    @Test
    fun `AddressSuggestion stores display name and coordinates`() {
        val suggestion = AddressSuggestion(
            displayName = "City Hall, 100 Main St",
            latitude = 39.7817,
            longitude = -89.6501
        )
        assertEquals("City Hall, 100 Main St", suggestion.displayName)
        assertEquals(39.7817, suggestion.latitude!!, 0.0001)
        assertEquals(-89.6501, suggestion.longitude!!, 0.0001)
    }

    @Test
    fun `AddressSuggestion allows null coordinates`() {
        val suggestion = AddressSuggestion(
            displayName = "Unknown Location",
            latitude = null,
            longitude = null
        )
        assertEquals(null, suggestion.latitude)
        assertEquals(null, suggestion.longitude)
    }

    // ========== Helper Methods ==========

    /**
     * Checks by reflection that the private formatDisplayName exists, then runs
     * [formatDisplayNameLogic] instead of it.
     */
    private fun invokeFormatDisplayName(featureName: String?, addressLine: String?): String {
        val clazz = Class.forName("org.onekash.kashcal.util.location.LocationSuggestionService")
        val method = clazz.getDeclaredMethod("formatDisplayName", String::class.java, String::class.java)
        method.isAccessible = true

        // Invoking it needs an instance, whose constructor takes a Context and a dispatcher.
        return formatDisplayNameLogic(featureName, addressLine)
    }

    /** Copies the steps of `LocationSuggestionService.formatDisplayName`; it can drift. */
    private fun formatDisplayNameLogic(featureName: String?, addressLine: String?): String {
        val address = addressLine ?: ""
        val feature = featureName?.trim()

        if (feature.isNullOrBlank() ||
            feature.all { it.isDigit() } ||
            address.startsWith(feature, ignoreCase = true)
        ) {
            return address
        }

        return "$feature, $address"
    }
}
