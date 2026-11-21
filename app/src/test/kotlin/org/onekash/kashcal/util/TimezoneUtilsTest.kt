package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

/** Tests [TimezoneUtils]: which stored timezone IDs resolve, and the device-zone fallback. */
class TimezoneUtilsTest {

    @Test
    fun `getDeviceTimezone is the system zone`() {
        assertEquals(ZoneId.systemDefault().id, TimezoneUtils.getDeviceTimezone())
    }

    @Test
    fun `region and offset ids resolve`() {
        assertEquals(ZoneId.of("Asia/Kolkata"), TimezoneUtils.resolveZoneOrNull("Asia/Kolkata"))
        assertEquals(ZoneId.of("UTC+05:00"), TimezoneUtils.resolveZoneOrNull("UTC+05:00"))
    }

    @Test
    fun `the unambiguous legacy names resolve to their offsets`() {
        for ((id, offset) in listOf("EST" to -5, "MST" to -7, "HST" to -10)) {
            assertEquals(id, ZoneOffset.ofHours(offset), TimezoneUtils.resolveZoneOrNull(id)?.normalized())
        }
    }

    @Test
    fun `ambiguous, unknown and blank ids do not resolve`() {
        for (id in listOf("BST", "IST", "CST", "Somewhere Standard Time", "", " ", null)) {
            assertNull(id, TimezoneUtils.resolveZoneOrNull(id))
        }
    }

    @Test
    fun `resolveZone falls back to the device zone`() {
        assertEquals(ZoneId.systemDefault(), TimezoneUtils.resolveZone("Somewhere Standard Time"))
        assertEquals(ZoneId.of("Europe/Berlin"), TimezoneUtils.resolveZone("Europe/Berlin"))
    }
}
