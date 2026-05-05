package org.onekash.kashcal.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for default calendar preference with prefixed string storage.
 *
 * Format: "room:123" or "device:456"
 * Legacy: Plain Long (e.g., "123") treated as Room calendar
 *
 * TDD pre-tests for C2: DataStore default calendar prefixed string.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultCalendarPreferenceTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher + Job())

    private lateinit var testDataStore: DataStore<Preferences>
    private lateinit var dataStoreScope: CoroutineScope

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        dataStoreScope = CoroutineScope(testDispatcher + Job())
        testDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(tmpFolder.root, "test_prefs.preferences_pb") }
        )
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
    }

    // ========== Prefixed String Parsing ==========

    @Test
    fun `parseDefaultCalendar parses room prefix correctly`() = runTest {
        val result = DefaultCalendar.parse("room:123")

        assertTrue("Should parse as Room", result is DefaultCalendar.Room)
        assertEquals(123L, (result as DefaultCalendar.Room).calendarId)
    }

    @Test
    fun `parseDefaultCalendar parses device prefix correctly`() = runTest {
        val result = DefaultCalendar.parse("device:456")

        assertTrue("Should parse as Device", result is DefaultCalendar.Device)
        assertEquals(456L, (result as DefaultCalendar.Device).calendarId)
    }

    @Test
    fun `parseDefaultCalendar handles large calendar IDs`() = runTest {
        val largeId = Long.MAX_VALUE
        val result = DefaultCalendar.parse("room:$largeId")

        assertTrue("Should parse as Room", result is DefaultCalendar.Room)
        assertEquals(largeId, (result as DefaultCalendar.Room).calendarId)
    }

    @Test
    fun `parseDefaultCalendar is case sensitive for prefix`() = runTest {
        // Only lowercase prefixes are valid
        assertNull("Uppercase ROOM should not parse", DefaultCalendar.parse("ROOM:123"))
        assertNull("Uppercase DEVICE should not parse", DefaultCalendar.parse("DEVICE:456"))
        assertNull("Mixed case should not parse", DefaultCalendar.parse("Room:123"))
    }

    // ========== Invalid Format Handling ==========

    @Test
    fun `parseDefaultCalendar returns null for invalid format`() = runTest {
        assertNull("Empty string", DefaultCalendar.parse(""))
        assertNull("No prefix", DefaultCalendar.parse("123"))
        assertNull("Invalid prefix", DefaultCalendar.parse("invalid:123"))
        assertNull("Missing colon", DefaultCalendar.parse("room123"))
        assertNull("Empty value", DefaultCalendar.parse("room:"))
        assertNull("Non-numeric value", DefaultCalendar.parse("room:abc"))
        assertNull("Negative ID", DefaultCalendar.parse("room:-1"))
    }

    @Test
    fun `parseDefaultCalendar returns null for null input`() = runTest {
        assertNull(DefaultCalendar.parse(null))
    }

    @Test
    fun `parseDefaultCalendar returns null for whitespace-only input`() = runTest {
        assertNull("Spaces", DefaultCalendar.parse("   "))
        assertNull("Tabs", DefaultCalendar.parse("\t\t"))
        assertNull("Newlines", DefaultCalendar.parse("\n\n"))
    }

    // ========== Serialization ==========

    @Test
    fun `Room toStorageString produces correct format`() = runTest {
        val calendar = DefaultCalendar.Room(789L)
        assertEquals("room:789", calendar.toStorageString())
    }

    @Test
    fun `Device toStorageString produces correct format`() = runTest {
        val calendar = DefaultCalendar.Device(101L)
        assertEquals("device:101", calendar.toStorageString())
    }

    @Test
    fun `round trip Room preserves value`() = runTest {
        val original = DefaultCalendar.Room(42L)
        val serialized = original.toStorageString()
        val parsed = DefaultCalendar.parse(serialized)

        assertEquals(original, parsed)
    }

    @Test
    fun `round trip Device preserves value`() = runTest {
        val original = DefaultCalendar.Device(99L)
        val serialized = original.toStorageString()
        val parsed = DefaultCalendar.parse(serialized)

        assertEquals(original, parsed)
    }

    // ========== Legacy Migration ==========

    @Test
    fun `parseLegacy converts plain Long to Room`() = runTest {
        // Plain numeric string (legacy format) should be treated as Room
        val result = DefaultCalendar.parseLegacy("123")

        assertTrue("Legacy Long should parse as Room", result is DefaultCalendar.Room)
        assertEquals(123L, (result as DefaultCalendar.Room).calendarId)
    }

    @Test
    fun `parseLegacy handles max Long value`() = runTest {
        val result = DefaultCalendar.parseLegacy(Long.MAX_VALUE.toString())

        assertTrue("Should parse as Room", result is DefaultCalendar.Room)
        assertEquals(Long.MAX_VALUE, (result as DefaultCalendar.Room).calendarId)
    }

    @Test
    fun `parseLegacy returns null for invalid Long`() = runTest {
        assertNull("Empty string", DefaultCalendar.parseLegacy(""))
        assertNull("Non-numeric", DefaultCalendar.parseLegacy("abc"))
        assertNull("Negative", DefaultCalendar.parseLegacy("-1"))
    }

    @Test
    fun `parseLegacy prefers new format over legacy`() = runTest {
        // If already in new format, parse as new format
        val roomResult = DefaultCalendar.parseLegacy("room:123")
        assertTrue("New room format should parse correctly", roomResult is DefaultCalendar.Room)

        val deviceResult = DefaultCalendar.parseLegacy("device:456")
        assertTrue("New device format should parse correctly", deviceResult is DefaultCalendar.Device)
    }

    // ========== Equality ==========

    @Test
    fun `Room equals works correctly`() = runTest {
        assertEquals(DefaultCalendar.Room(1L), DefaultCalendar.Room(1L))
        assertTrue(DefaultCalendar.Room(1L) != DefaultCalendar.Room(2L))
    }

    @Test
    fun `Device equals works correctly`() = runTest {
        assertEquals(DefaultCalendar.Device(1L), DefaultCalendar.Device(1L))
        assertTrue(DefaultCalendar.Device(1L) != DefaultCalendar.Device(2L))
    }

    @Test
    fun `Room does not equal Device with same ID`() = runTest {
        val room = DefaultCalendar.Room(1L)
        val device = DefaultCalendar.Device(1L)

        assertTrue("Different types should not be equal", room != device)
    }
}
