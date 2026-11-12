package org.onekash.icaldav.parser

import io.mockk.mockk
import net.fortuna.ical4j.model.TimeZone
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.DateTimeException
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [SimpleTimeZoneRegistry], which works on Android without ZoneRulesProvider: getTimeZone
 * returns null, register and clear are no-ops, getZoneRules is empty, getTzId passes its input
 * through, and getZoneId resolves IANA IDs and Windows names (hardcoded aliases and the
 * properties-file fallback, in any case) and throws DateTimeException for blank or unknown IDs.
 */
@DisplayName("SimpleTimeZoneRegistry")
class SimpleTimeZoneRegistryTest {

    private val registry = SimpleTimeZoneRegistry()

    @Test
    @DisplayName("getTimeZone returns null for any ID")
    fun `getTimeZone returns null`() {
        assertNull(registry.getTimeZone("America/New_York"))
        assertNull(registry.getTimeZone("Europe/London"))
        assertNull(registry.getTimeZone("UTC"))
        assertNull(registry.getTimeZone(""))
    }

    @Test
    @DisplayName("register does not throw")
    fun `register is no-op`() {
        // Passes if it doesn't throw.
        val timezone = mockk<TimeZone>()
        registry.register(timezone)
        registry.register(timezone, false)
        registry.register(timezone, true)
    }

    @Test
    @DisplayName("clear does not throw")
    fun `clear is no-op`() {
        // Passes if it doesn't throw.
        registry.clear()
    }

    @Test
    @DisplayName("getZoneRules returns empty map")
    fun `getZoneRules returns empty`() {
        val rules = registry.getZoneRules()
        assertTrue(rules.isEmpty())
    }

    @Test
    @DisplayName("getZoneId returns valid ZoneId for standard timezone")
    fun `getZoneId works for standard timezones`() {
        val nyZone = registry.getZoneId("America/New_York")
        assertEquals(ZoneId.of("America/New_York"), nyZone)

        val utcZone = registry.getZoneId("UTC")
        assertEquals(ZoneId.of("UTC"), utcZone)

        val tokyoZone = registry.getZoneId("Asia/Tokyo")
        assertEquals(ZoneId.of("Asia/Tokyo"), tokyoZone)
    }

    @Test
    @DisplayName("getZoneId throws DateTimeException for invalid timezone")
    fun `getZoneId throws for invalid`() {
        for (id in listOf("Invalid/Timezone", "", "   ")) {
            assertFailsWith<DateTimeException>(id) { registry.getZoneId(id) }
        }
    }

    @Test
    @DisplayName("getZoneId normalizes Windows timezone names")
    fun `getZoneId normalizes Windows timezones`() {
        val pacific = registry.getZoneId("Pacific Standard Time")
        assertEquals(ZoneId.of("America/Los_Angeles"), pacific)

        val eastern = registry.getZoneId("Eastern Standard Time")
        assertEquals(ZoneId.of("America/New_York"), eastern)

        val central = registry.getZoneId("Central Standard Time")
        assertEquals(ZoneId.of("America/Chicago"), central)

        val mountain = registry.getZoneId("Mountain Standard Time")
        assertEquals(ZoneId.of("America/Denver"), mountain)
    }

    @Test
    @DisplayName("getTzId returns input as-is")
    fun `getTzId passes through`() {
        assertEquals("America/New_York", registry.getTzId("America/New_York"))
        assertEquals("UTC", registry.getTzId("UTC"))
    }

    // ==================== Properties File Fallback (Issue #45) ====================

    @Test
    @DisplayName("getZoneId resolves India Standard Time via properties file")
    fun `getZoneId resolves India Standard Time`() {
        val zone = registry.getZoneId("India Standard Time")
        assertNotNull(zone, "India Standard Time should resolve via properties file")
        // UTC+5:30, which has no DST.
        val offset = zone!!.rules.getOffset(java.time.Instant.now())
        assertEquals(java.time.ZoneOffset.ofHoursMinutes(5, 30), offset)
    }

    @Test
    @DisplayName("getZoneId resolves Tokyo Standard Time via properties file")
    fun `getZoneId resolves Tokyo Standard Time`() {
        val zone = registry.getZoneId("Tokyo Standard Time")
        assertNotNull(zone, "Tokyo Standard Time should resolve via properties file")
        val offset = zone!!.rules.getOffset(java.time.Instant.now())
        assertEquals(java.time.ZoneOffset.ofHours(9), offset)
    }

    @Test
    @DisplayName("getZoneId is case insensitive for Windows timezone names")
    fun `getZoneId case insensitive for Windows names`() {
        // Hardcoded aliases (ignoreCase = true)
        assertNotNull(registry.getZoneId("eastern standard time"))
        assertNotNull(registry.getZoneId("PACIFIC STANDARD TIME"))
        // Properties file fallback (TreeMap CASE_INSENSITIVE_ORDER)
        assertNotNull(registry.getZoneId("india standard time"))
        assertNotNull(registry.getZoneId("TOKYO STANDARD TIME"))
    }
}
