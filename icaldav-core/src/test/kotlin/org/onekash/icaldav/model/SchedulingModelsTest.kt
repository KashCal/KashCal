package org.onekash.icaldav.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Scheduling Models Tests")
class SchedulingModelsTest {

    @Nested
    @DisplayName("ITipMethod Tests")
    inner class ITipMethodTests {
        @Test
        fun `fromString parses all methods`() {
            assertEquals(ITipMethod.PUBLISH, ITipMethod.fromString("PUBLISH"))
            assertEquals(ITipMethod.REQUEST, ITipMethod.fromString("REQUEST"))
            assertEquals(ITipMethod.REPLY, ITipMethod.fromString("REPLY"))
            assertEquals(ITipMethod.ADD, ITipMethod.fromString("ADD"))
            assertEquals(ITipMethod.CANCEL, ITipMethod.fromString("CANCEL"))
            assertEquals(ITipMethod.REFRESH, ITipMethod.fromString("REFRESH"))
            assertEquals(ITipMethod.COUNTER, ITipMethod.fromString("COUNTER"))
            assertEquals(ITipMethod.DECLINECOUNTER, ITipMethod.fromString("DECLINECOUNTER"))
        }

        @Test
        fun `fromString is case insensitive`() {
            assertEquals(ITipMethod.REQUEST, ITipMethod.fromString("request"))
            assertEquals(ITipMethod.REQUEST, ITipMethod.fromString("Request"))
            assertEquals(ITipMethod.REQUEST, ITipMethod.fromString("REQUEST"))
        }

        @Test
        fun `fromString returns null for unknown`() {
            assertNull(ITipMethod.fromString("UNKNOWN"))
            assertNull(ITipMethod.fromString(""))
            assertNull(ITipMethod.fromString("INVALID"))
        }
    }

    @Nested
    @DisplayName("ScheduleAgent Tests")
    inner class ScheduleAgentTests {
        @Test
        fun `fromString parses all agents`() {
            assertEquals(ScheduleAgent.SERVER, ScheduleAgent.fromString("SERVER"))
            assertEquals(ScheduleAgent.CLIENT, ScheduleAgent.fromString("CLIENT"))
            assertEquals(ScheduleAgent.NONE, ScheduleAgent.fromString("NONE"))
        }

        @Test
        fun `fromString defaults to SERVER`() {
            assertEquals(ScheduleAgent.SERVER, ScheduleAgent.fromString("UNKNOWN"))
            assertEquals(ScheduleAgent.SERVER, ScheduleAgent.fromString(""))
        }
    }

    @Nested
    @DisplayName("ScheduleStatus Tests")
    inner class ScheduleStatusTests {
        @Test
        fun `parses status code and description`() {
            val status = ScheduleStatus.fromString("2.0;Success")
            assertEquals("2.0", status.code)
            assertEquals("Success", status.description)
        }

        @Test
        fun `handles status without description`() {
            val status = ScheduleStatus.fromString("2.0")
            assertEquals("2.0", status.code)
            assertNull(status.description)
        }

        @Test
        fun `categorizes 1_x as PENDING`() {
            val status = ScheduleStatus.fromString("1.0;Pending")
            assertEquals(ScheduleStatus.StatusCategory.PENDING, status.category)
            assertTrue(status.isPending)
            assertFalse(status.isSuccess)
        }

        @Test
        fun `categorizes 2_x as SUCCESS`() {
            val status = ScheduleStatus.fromString("2.0;Success")
            assertEquals(ScheduleStatus.StatusCategory.SUCCESS, status.category)
            assertTrue(status.isSuccess)
            assertFalse(status.isPending)
        }

        @Test
        fun `categorizes 3_x as PERMISSION_ERROR`() {
            val status = ScheduleStatus.fromString("3.7;Invalid calendar user")
            assertEquals(ScheduleStatus.StatusCategory.PERMISSION_ERROR, status.category)
        }

        @Test
        fun `categorizes 5_x as DELIVERY_ERROR`() {
            val status = ScheduleStatus.fromString("5.1;Could not deliver")
            assertEquals(ScheduleStatus.StatusCategory.DELIVERY_ERROR, status.category)
        }

        @Test
        fun `pre-defined constants exist`() {
            assertEquals("1.0", ScheduleStatus.PENDING.code)
            assertEquals("2.0", ScheduleStatus.SUCCESS.code)
            assertEquals("3.7", ScheduleStatus.INVALID_USER.code)
            assertEquals("5.1", ScheduleStatus.DELIVERY_FAILED.code)
        }
    }

    @Nested
    @DisplayName("ScheduleForceSend Tests")
    inner class ScheduleForceSendTests {
        @Test
        fun `fromString parses REQUEST and REPLY`() {
            assertEquals(ScheduleForceSend.REQUEST, ScheduleForceSend.fromString("REQUEST"))
            assertEquals(ScheduleForceSend.REPLY, ScheduleForceSend.fromString("REPLY"))
        }

        @Test
        fun `fromString returns null for unknown`() {
            assertNull(ScheduleForceSend.fromString("UNKNOWN"))
            assertNull(ScheduleForceSend.fromString(""))
        }
    }

    @Nested
    @DisplayName("RequestStatus Tests")
    inner class RequestStatusTests {
        @Test
        fun `fromCode returns correct enum`() {
            assertEquals(RequestStatus.SUCCESS, RequestStatus.fromCode("2.0"))
            assertEquals(RequestStatus.SUCCESS_FALLBACK, RequestStatus.fromCode("2.1"))
            assertEquals(RequestStatus.INVALID_CALENDAR_USER, RequestStatus.fromCode("3.7"))
        }

        @Test
        fun `isSuccess returns true for 2_x codes`() {
            assertTrue(RequestStatus.SUCCESS.isSuccess)
            assertTrue(RequestStatus.SUCCESS_FALLBACK.isSuccess)
            assertTrue(RequestStatus.SUCCESS_IGNORED.isSuccess)
        }

        @Test
        fun `isError returns true for 3_x and 5_x codes`() {
            assertTrue(RequestStatus.INVALID_PROPERTY_NAME.isError)
            assertTrue(RequestStatus.INVALID_CALENDAR_USER.isError)
            assertTrue(RequestStatus.SERVICE_UNAVAILABLE.isError)
        }
    }

    @Nested
    @DisplayName("CUType Tests")
    inner class CUTypeTests {
        @Test
        fun `fromString parses all types`() {
            assertEquals(CUType.INDIVIDUAL, CUType.fromString("INDIVIDUAL"))
            assertEquals(CUType.GROUP, CUType.fromString("GROUP"))
            assertEquals(CUType.RESOURCE, CUType.fromString("RESOURCE"))
            assertEquals(CUType.ROOM, CUType.fromString("ROOM"))
            assertEquals(CUType.UNKNOWN, CUType.fromString("UNKNOWN"))
        }

        @Test
        fun `fromString defaults to INDIVIDUAL`() {
            assertEquals(CUType.INDIVIDUAL, CUType.fromString(null))
            assertEquals(CUType.INDIVIDUAL, CUType.fromString(""))
            assertEquals(CUType.INDIVIDUAL, CUType.fromString("INVALID"))
        }

        @Test
        fun `toICalString returns correct values`() {
            assertEquals("INDIVIDUAL", CUType.INDIVIDUAL.toICalString())
            assertEquals("GROUP", CUType.GROUP.toICalString())
            assertEquals("RESOURCE", CUType.RESOURCE.toICalString())
            assertEquals("ROOM", CUType.ROOM.toICalString())
        }
    }

    @Nested
    @DisplayName("SchedulingUrls Tests")
    inner class SchedulingUrlsTests {
        @Test
        fun `supportsScheduling returns true when both URLs present`() {
            val urls = SchedulingUrls(
                scheduleInboxUrl = "https://example.com/inbox",
                scheduleOutboxUrl = "https://example.com/outbox"
            )
            assertTrue(urls.supportsScheduling)
        }

        @Test
        fun `supportsScheduling returns false when inbox missing`() {
            val urls = SchedulingUrls(
                scheduleInboxUrl = null,
                scheduleOutboxUrl = "https://example.com/outbox"
            )
            assertFalse(urls.supportsScheduling)
        }

        @Test
        fun `supportsScheduling returns false when outbox missing`() {
            val urls = SchedulingUrls(
                scheduleInboxUrl = "https://example.com/inbox",
                scheduleOutboxUrl = null
            )
            assertFalse(urls.supportsScheduling)
        }
    }
}
