package org.onekash.kashcal.util

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.util.TimeZone

/** Tests that [ZoneSource] follows the device zone only when refreshed, and only on a change. */
class ZoneSourceTest {

    private lateinit var savedZone: TimeZone

    @Before
    fun setUp() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(LA))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(savedZone)
    }

    @Test
    fun `starts at the device zone`() {
        assertEquals(LA, ZoneSource().zone.value)
    }

    @Test
    fun `refresh picks up a new device zone and emits once`() = runTest(UnconfinedTestDispatcher()) {
        val source = ZoneSource()
        val seen = mutableListOf<ZoneId>()
        val job = launch { source.zone.toList(seen) }

        TimeZone.setDefault(TimeZone.getTimeZone(TOKYO))
        source.refresh()
        source.refresh()

        assertEquals(listOf(LA, TOKYO), seen)
        job.cancel()
    }

    @Test
    fun `the zone does not change until refresh is called`() {
        val source = ZoneSource()

        TimeZone.setDefault(TimeZone.getTimeZone(TOKYO))

        assertEquals(LA, source.zone.value)
    }

    private companion object {
        val LA: ZoneId = ZoneId.of("America/Los_Angeles")
        val TOKYO: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}
