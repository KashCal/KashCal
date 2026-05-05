package org.onekash.kashcal.data.calendar_provider

import android.provider.CalendarContract
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [CalendarProviderRepository.getDeviceEventWithExceptions] via the
 * fake implementation. The fake is the contract — any production impl must match
 * these behaviors.
 *
 * Contract:
 * - Returns null when master event ID doesn't exist.
 * - Returns (master, emptyList()) when master exists but has no exceptions.
 * - Returns exceptions sorted by originalInstanceTime ASC.
 * - Preserves STATUS_CANCELED exceptions (not filtered out — key difference from the
 *   Instances view, which suppresses them).
 * - Returns null when permission is revoked (SecurityException surface).
 */
class GetDeviceEventWithExceptionsTest {

    @Test
    fun `returns null when master not found`() = runTest {
        val fake = FakeCalendarProviderRepository()
        assertNull(fake.getDeviceEventWithExceptions(masterEventId = 42L))
    }

    @Test
    fun `returns master with empty exceptions when none exist`() = runTest {
        val fake = FakeCalendarProviderRepository()
        val master = createDeviceEvent(id = 100L, rrule = "FREQ=DAILY")
        fake.deviceEvents[master.id] = master

        val result = fake.getDeviceEventWithExceptions(masterEventId = 100L)

        assertNotNull(result)
        assertEquals(master, result!!.first)
        assertTrue("expected empty exceptions", result.second.isEmpty())
    }

    @Test
    fun `returns exceptions sorted by originalInstanceTime ascending`() = runTest {
        val fake = FakeCalendarProviderRepository()
        val master = createDeviceEvent(id = 100L, rrule = "FREQ=WEEKLY")
        val ex1 = createDeviceEvent(id = 201L, originalId = 100L, originalInstanceTime = 3000L)
        val ex2 = createDeviceEvent(id = 202L, originalId = 100L, originalInstanceTime = 1000L)
        val ex3 = createDeviceEvent(id = 203L, originalId = 100L, originalInstanceTime = 2000L)

        fake.deviceEvents[master.id] = master
        fake.deviceEvents[ex1.id] = ex1
        fake.deviceEvents[ex2.id] = ex2
        fake.deviceEvents[ex3.id] = ex3

        val result = fake.getDeviceEventWithExceptions(masterEventId = 100L)!!

        // Order: ex2 (1000), ex3 (2000), ex1 (3000)
        assertEquals(listOf(1000L, 2000L, 3000L), result.second.map { it.originalInstanceTime })
    }

    @Test
    fun `preserves STATUS_CANCELED exceptions in the exceptions list`() = runTest {
        // The entire point of this method vs fetching from Instances: STATUS_CANCELED
        // exception rows represent deleted occurrences and MUST survive to be exported
        // as cancelled VEVENTs.
        val fake = FakeCalendarProviderRepository()
        val master = createDeviceEvent(id = 100L, rrule = "FREQ=DAILY")
        val canceledException = createDeviceEvent(
            id = 201L,
            originalId = 100L,
            originalInstanceTime = 1000L,
            status = CalendarContract.Events.STATUS_CANCELED
        )
        val confirmedException = createDeviceEvent(
            id = 202L,
            originalId = 100L,
            originalInstanceTime = 2000L,
            status = CalendarContract.Events.STATUS_CONFIRMED
        )

        fake.deviceEvents[master.id] = master
        fake.deviceEvents[canceledException.id] = canceledException
        fake.deviceEvents[confirmedException.id] = confirmedException

        val result = fake.getDeviceEventWithExceptions(masterEventId = 100L)!!

        assertEquals(2, result.second.size)
        val canceled = result.second.find { it.id == 201L }
        assertNotNull("STATUS_CANCELED exception must not be filtered out", canceled)
        assertEquals(CalendarContract.Events.STATUS_CANCELED, canceled!!.status)
    }

    @Test
    fun `filters exceptions to the given master only`() = runTest {
        val fake = FakeCalendarProviderRepository()
        val master1 = createDeviceEvent(id = 100L, rrule = "FREQ=DAILY")
        val master2 = createDeviceEvent(id = 200L, rrule = "FREQ=WEEKLY")
        val exOfMaster1 = createDeviceEvent(id = 300L, originalId = 100L, originalInstanceTime = 1000L)
        val exOfMaster2 = createDeviceEvent(id = 400L, originalId = 200L, originalInstanceTime = 2000L)

        fake.deviceEvents[master1.id] = master1
        fake.deviceEvents[master2.id] = master2
        fake.deviceEvents[exOfMaster1.id] = exOfMaster1
        fake.deviceEvents[exOfMaster2.id] = exOfMaster2

        val result = fake.getDeviceEventWithExceptions(masterEventId = 100L)!!

        assertEquals(1, result.second.size)
        assertEquals(300L, result.second[0].id)
    }

    @Test
    fun `returns null when permission is revoked`() = runTest {
        val fake = FakeCalendarProviderRepository()
        val master = createDeviceEvent(id = 100L, rrule = "FREQ=DAILY")
        fake.deviceEvents[master.id] = master
        fake.shouldThrowSecurityException = true

        assertNull(fake.getDeviceEventWithExceptions(masterEventId = 100L))
    }

    private fun createDeviceEvent(
        id: Long = 1L,
        calendarId: Long = 1L,
        rrule: String? = null,
        originalId: Long? = null,
        originalInstanceTime: Long? = null,
        status: Int = 1
    ) = DeviceEvent(
        id = id,
        calendarId = calendarId,
        title = "Event $id",
        description = null,
        location = null,
        startTs = 0L,
        endTs = 0L,
        duration = null,
        isAllDay = false,
        rrule = rrule,
        rdate = null,
        exdate = null,
        exrule = null,
        timezone = "UTC",
        originalId = originalId,
        originalInstanceTime = originalInstanceTime,
        status = status,
        availability = 0,
        accessLevel = 700,
        calendarColor = null,
        eventColor = null
    )
}
