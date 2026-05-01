package org.onekash.icaldav.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests for ICalLocation, GeoCoordinates, StructuredAddress, and LocationType (RFC 9073).
 */
class ICalLocationTest {

    @Nested
    inner class LocationTypeTests {

        @Test
        fun `fromString parses INDOOR correctly`() {
            assertEquals(LocationType.INDOOR, LocationType.fromString("INDOOR"))
            assertEquals(LocationType.INDOOR, LocationType.fromString("indoor"))
            assertEquals(LocationType.INDOOR, LocationType.fromString("Indoor"))
        }

        @Test
        fun `fromString parses OUTDOOR correctly`() {
            assertEquals(LocationType.OUTDOOR, LocationType.fromString("OUTDOOR"))
            assertEquals(LocationType.OUTDOOR, LocationType.fromString("outdoor"))
        }

        @Test
        fun `fromString parses ONLINE correctly`() {
            assertEquals(LocationType.ONLINE, LocationType.fromString("ONLINE"))
            assertEquals(LocationType.ONLINE, LocationType.fromString("online"))
        }

        @Test
        fun `fromString parses PARKING correctly`() {
            assertEquals(LocationType.PARKING, LocationType.fromString("PARKING"))
        }

        @Test
        fun `fromString parses PRIVATE correctly`() {
            assertEquals(LocationType.PRIVATE, LocationType.fromString("PRIVATE"))
        }

        @Test
        fun `fromString parses PUBLIC correctly`() {
            assertEquals(LocationType.PUBLIC, LocationType.fromString("PUBLIC"))
        }

        @Test
        fun `fromString returns null for unknown value`() {
            assertNull(LocationType.fromString("UNKNOWN"))
            assertNull(LocationType.fromString("invalid"))
        }

        @Test
        fun `fromString returns null for null or blank`() {
            assertNull(LocationType.fromString(null))
            assertNull(LocationType.fromString(""))
            assertNull(LocationType.fromString("   "))
        }

        @Test
        fun `toICalString returns correct values`() {
            assertEquals("INDOOR", LocationType.INDOOR.toICalString())
            assertEquals("OUTDOOR", LocationType.OUTDOOR.toICalString())
            assertEquals("ONLINE", LocationType.ONLINE.toICalString())
            assertEquals("PARKING", LocationType.PARKING.toICalString())
            assertEquals("PRIVATE", LocationType.PRIVATE.toICalString())
            assertEquals("PUBLIC", LocationType.PUBLIC.toICalString())
        }
    }

    @Nested
    inner class GeoCoordinatesTests {

        @Test
        fun `parse valid coordinates`() {
            val coords = GeoCoordinates.parse("37.386013;-122.082932")

            assertEquals(37.386013, coords?.latitude)
            assertEquals(-122.082932, coords?.longitude)
        }

        @Test
        fun `parse coordinates with whitespace`() {
            val coords = GeoCoordinates.parse("  37.5  ;  -122.5  ")

            assertEquals(37.5, coords?.latitude)
            assertEquals(-122.5, coords?.longitude)
        }

        @Test
        fun `parse returns null for invalid format`() {
            assertNull(GeoCoordinates.parse("37.5"))
            assertNull(GeoCoordinates.parse("37.5;-122.5;100"))
            assertNull(GeoCoordinates.parse("invalid"))
        }

        @Test
        fun `parse returns null for non-numeric values`() {
            assertNull(GeoCoordinates.parse("abc;def"))
        }

        @Test
        fun `parse returns null for null or blank`() {
            assertNull(GeoCoordinates.parse(null))
            assertNull(GeoCoordinates.parse(""))
            assertNull(GeoCoordinates.parse("   "))
        }

        @Test
        fun `toICalString formats correctly`() {
            val coords = GeoCoordinates(37.386013, -122.082932)
            assertEquals("37.386013;-122.082932", coords.toICalString())
        }
    }

    @Nested
    inner class StructuredAddressTests {

        @Test
        fun `toDisplayString with all fields`() {
            val address = StructuredAddress(
                streetAddress = "123 Main Street",
                locality = "Mountain View",
                region = "CA",
                postalCode = "94043",
                country = "USA"
            )

            assertEquals(
                "123 Main Street, Mountain View, CA, 94043, USA",
                address.toDisplayString()
            )
        }

        @Test
        fun `toDisplayString with partial fields`() {
            val address = StructuredAddress(
                locality = "San Francisco",
                region = "CA"
            )

            assertEquals("San Francisco, CA", address.toDisplayString())
        }

        @Test
        fun `toDisplayString skips blank fields`() {
            val address = StructuredAddress(
                streetAddress = "123 Main St",
                locality = "",
                region = "CA",
                postalCode = "   ",
                country = "USA"
            )

            assertEquals("123 Main St, CA, USA", address.toDisplayString())
        }

        @Test
        fun `toDisplayString with no fields returns empty string`() {
            val address = StructuredAddress()
            assertEquals("", address.toDisplayString())
        }
    }

    @Nested
    inner class ICalLocationConstructorTests {

        @Test
        fun `create location with uid only`() {
            val location = ICalLocation(uid = "loc-1")

            assertEquals("loc-1", location.uid)
            assertNull(location.name)
            assertNull(location.description)
            assertNull(location.geo)
            assertTrue(location.locationTypes.isEmpty())
            assertNull(location.url)
            assertNull(location.structuredAddress)
        }

        @Test
        fun `create location with all parameters`() {
            val location = ICalLocation(
                uid = "loc-1",
                name = "Conference Room A",
                description = "Building 1, Floor 3",
                geo = GeoCoordinates(37.386013, -122.082932),
                locationTypes = listOf(LocationType.INDOOR, LocationType.PRIVATE),
                url = "https://example.com/rooms/a",
                structuredAddress = StructuredAddress(
                    streetAddress = "123 Main St",
                    locality = "Mountain View",
                    region = "CA"
                )
            )

            assertEquals("loc-1", location.uid)
            assertEquals("Conference Room A", location.name)
            assertEquals("Building 1, Floor 3", location.description)
            assertEquals(37.386013, location.geo?.latitude)
            assertEquals(2, location.locationTypes.size)
            assertTrue(location.locationTypes.contains(LocationType.INDOOR))
            assertEquals("https://example.com/rooms/a", location.url)
            assertEquals("Mountain View", location.structuredAddress?.locality)
        }
    }

    @Nested
    inner class ICalLocationHelperMethodsTests {

        @Test
        fun `isOnline returns true when ONLINE type present`() {
            val location = ICalLocation(
                uid = "loc-1",
                name = "Zoom Meeting",
                locationTypes = listOf(LocationType.ONLINE)
            )

            assertTrue(location.isOnline())
        }

        @Test
        fun `isOnline returns false when ONLINE type not present`() {
            val location = ICalLocation(
                uid = "loc-1",
                name = "Conference Room",
                locationTypes = listOf(LocationType.INDOOR)
            )

            assertFalse(location.isOnline())
        }

        @Test
        fun `isOnline returns false when no types`() {
            val location = ICalLocation(uid = "loc-1")
            assertFalse(location.isOnline())
        }

        @Test
        fun `hasCoordinates returns true when geo present`() {
            val location = ICalLocation(
                uid = "loc-1",
                geo = GeoCoordinates(37.5, -122.5)
            )

            assertTrue(location.hasCoordinates())
        }

        @Test
        fun `hasCoordinates returns false when geo null`() {
            val location = ICalLocation(uid = "loc-1")
            assertFalse(location.hasCoordinates())
        }
    }

    @Nested
    inner class CompanionFactoryMethodsTests {

        @Test
        fun `simple creates location with name`() {
            val location = ICalLocation.simple("Meeting Room", uid = "loc-123")

            assertEquals("loc-123", location.uid)
            assertEquals("Meeting Room", location.name)
        }

        @Test
        fun `online creates virtual location`() {
            val location = ICalLocation.online(
                name = "Zoom Call",
                url = "https://zoom.us/j/123456",
                uid = "loc-456"
            )

            assertEquals("loc-456", location.uid)
            assertEquals("Zoom Call", location.name)
            assertEquals("https://zoom.us/j/123456", location.url)
            assertTrue(location.isOnline())
        }
    }
}
