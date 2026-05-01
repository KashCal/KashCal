package org.onekash.icaldav.scheduling

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.*
import org.onekash.icaldav.parser.ICalParser
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("ITipBuilder Tests")
class ITipBuilderTest {
    private val builder = ITipBuilder()
    private val parser = ICalParser()

    private fun createTestEvent(sequence: Int = 0): ICalEvent {
        return ICalEvent(
            uid = "test-event-123",
            importId = "test-event-123",
            summary = "Test Meeting",
            description = null,
            location = null,
            dtStart = ICalDateTime.parse("20231215T140000Z"),
            dtEnd = ICalDateTime.parse("20231215T150000Z"),
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = sequence,
            rrule = null,
            exdates = emptyList(),
            recurrenceId = null,
            alarms = emptyList(),
            categories = emptyList(),
            organizer = Organizer(
                email = "organizer@example.com",
                name = "Organizer",
                sentBy = null
            ),
            attendees = listOf(
                Attendee(
                    email = "attendee1@example.com",
                    name = "Attendee 1",
                    partStat = PartStat.NEEDS_ACTION,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                ),
                Attendee(
                    email = "attendee2@example.com",
                    name = "Attendee 2",
                    partStat = PartStat.ACCEPTED,
                    role = AttendeeRole.OPT_PARTICIPANT,
                    rsvp = false
                )
            ),
            color = null,
            dtstamp = ICalDateTime.parse("20231215T120000Z"),
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )
    }

    @Nested
    @DisplayName("createRequest Tests")
    inner class CreateRequestTests {
        @Test
        fun `createRequest sets METHOD REQUEST`() {
            val event = createTestEvent()
            val attendees = listOf(
                Attendee(
                    email = "new@example.com",
                    name = "New Attendee",
                    partStat = PartStat.ACCEPTED,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                )
            )

            val ics = builder.createRequest(event, attendees)
            assertTrue(ics.contains("METHOD:REQUEST"))
        }

        @Test
        fun `createRequest sets PARTSTAT to NEEDS-ACTION`() {
            val event = createTestEvent()
            val attendees = listOf(
                Attendee(
                    email = "new@example.com",
                    name = "New Attendee",
                    partStat = PartStat.ACCEPTED,  // Will be changed to NEEDS_ACTION
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                )
            )

            val ics = builder.createRequest(event, attendees)
            assertTrue(ics.contains("PARTSTAT=NEEDS-ACTION"))
        }

        @Test
        fun `createRequest sets RSVP TRUE`() {
            val event = createTestEvent()
            val attendees = listOf(
                Attendee(
                    email = "new@example.com",
                    name = "New Attendee",
                    partStat = PartStat.NEEDS_ACTION,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false  // Will be changed to true
                )
            )

            val ics = builder.createRequest(event, attendees)
            assertTrue(ics.contains("RSVP=TRUE"))
        }
    }

    @Nested
    @DisplayName("createReply Tests")
    inner class CreateReplyTests {
        @Test
        fun `createReply sets METHOD REPLY`() {
            val event = createTestEvent(sequence = 5)
            val attendee = Attendee(
                email = "responder@example.com",
                name = "Responder",
                partStat = PartStat.ACCEPTED,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )

            val ics = builder.createReply(event, attendee)
            assertTrue(ics.contains("METHOD:REPLY"))
        }

        @Test
        fun `createReply includes only responding attendee`() {
            val event = createTestEvent()
            val attendee = Attendee(
                email = "responder@example.com",
                name = "Responder",
                partStat = PartStat.ACCEPTED,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )

            val ics = builder.createReply(event, attendee)
            // Should contain only one ATTENDEE (the responder)
            val attendeeCount = ics.split("ATTENDEE").size - 1
            assertEquals(1, attendeeCount)
            assertTrue(ics.contains("responder@example.com"))
        }

        @Test
        fun `createReply preserves original SEQUENCE - RFC 5546 requirement`() {
            val event = createTestEvent(sequence = 42)
            val attendee = Attendee(
                email = "responder@example.com",
                name = "Responder",
                partStat = PartStat.ACCEPTED,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )

            val ics = builder.createReply(event, attendee)
            assertTrue(ics.contains("SEQUENCE:42"), "REPLY must preserve original SEQUENCE")
        }
    }

    @Nested
    @DisplayName("createCancel Tests")
    inner class CreateCancelTests {
        @Test
        fun `createCancel sets METHOD CANCEL`() {
            val event = createTestEvent()

            val ics = builder.createCancel(event)
            assertTrue(ics.contains("METHOD:CANCEL"))
        }

        @Test
        fun `createCancel sets STATUS CANCELLED for full cancel`() {
            val event = createTestEvent()

            val ics = builder.createCancel(event)
            assertTrue(ics.contains("STATUS:CANCELLED"))
        }

        @Test
        fun `createCancel preserves SEQUENCE`() {
            val event = createTestEvent(sequence = 10)

            val ics = builder.createCancel(event)
            assertTrue(ics.contains("SEQUENCE:10"))
        }

        @Test
        fun `createCancel for specific attendees includes only those attendees`() {
            val event = createTestEvent()
            val attendeesToCancel = listOf(
                Attendee(
                    email = "removed@example.com",
                    name = "Removed",
                    partStat = PartStat.DECLINED,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                )
            )

            val ics = builder.createCancel(event, attendeesToCancel)
            assertTrue(ics.contains("removed@example.com"))
            val attendeeCount = ics.split("ATTENDEE").size - 1
            assertEquals(1, attendeeCount)
        }
    }

    @Nested
    @DisplayName("createUpdate Tests")
    inner class CreateUpdateTests {
        @Test
        fun `createUpdate increments SEQUENCE`() {
            val event = createTestEvent(sequence = 5)

            val ics = builder.createUpdate(event)
            assertTrue(ics.contains("SEQUENCE:6"), "Update should increment SEQUENCE")
        }

        @Test
        fun `createUpdate sets METHOD REQUEST`() {
            val event = createTestEvent()

            val ics = builder.createUpdate(event)
            assertTrue(ics.contains("METHOD:REQUEST"))
        }
    }

    @Nested
    @DisplayName("createCounter Tests")
    inner class CreateCounterTests {
        @Test
        fun `createCounter sets METHOD COUNTER`() {
            val event = createTestEvent()
            val attendee = Attendee(
                email = "proposer@example.com",
                name = "Proposer",
                partStat = PartStat.TENTATIVE,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )
            val proposedStart = ICalDateTime.parse("20231216T100000Z")
            val proposedEnd = ICalDateTime.parse("20231216T110000Z")

            val ics = builder.createCounter(event, attendee, proposedStart, proposedEnd)
            assertTrue(ics.contains("METHOD:COUNTER"))
        }

        @Test
        fun `createCounter includes proposed times`() {
            val event = createTestEvent()
            val attendee = Attendee(
                email = "proposer@example.com",
                name = "Proposer",
                partStat = PartStat.TENTATIVE,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )
            val proposedStart = ICalDateTime.parse("20231216T100000Z")
            val proposedEnd = ICalDateTime.parse("20231216T110000Z")

            val ics = builder.createCounter(event, attendee, proposedStart, proposedEnd)
            assertTrue(ics.contains("DTSTART:20231216T100000Z"))
            assertTrue(ics.contains("DTEND:20231216T110000Z"))
        }

        @Test
        fun `createCounter preserves original SEQUENCE - RFC 5546 requirement`() {
            val event = createTestEvent(sequence = 7)
            val attendee = Attendee(
                email = "proposer@example.com",
                name = "Proposer",
                partStat = PartStat.TENTATIVE,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )
            val proposedStart = ICalDateTime.parse("20231216T100000Z")
            val proposedEnd = ICalDateTime.parse("20231216T110000Z")

            val ics = builder.createCounter(event, attendee, proposedStart, proposedEnd)
            assertTrue(ics.contains("SEQUENCE:7"), "COUNTER must preserve original SEQUENCE")
        }
    }

    @Nested
    @DisplayName("createDeclineCounter Tests")
    inner class CreateDeclineCounterTests {
        @Test
        fun `createDeclineCounter sets METHOD DECLINECOUNTER`() {
            val event = createTestEvent()
            val attendee = Attendee(
                email = "declined@example.com",
                name = "Declined",
                partStat = PartStat.DECLINED,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )

            val ics = builder.createDeclineCounter(event, attendee)
            assertTrue(ics.contains("METHOD:DECLINECOUNTER"))
        }
    }

    @Nested
    @DisplayName("createRefresh Tests")
    inner class CreateRefreshTests {
        @Test
        fun `createRefresh sets METHOD REFRESH`() {
            val event = createTestEvent()
            val attendee = Attendee(
                email = "requester@example.com",
                name = "Requester",
                partStat = PartStat.NEEDS_ACTION,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )

            val ics = builder.createRefresh(event, attendee)
            assertTrue(ics.contains("METHOD:REFRESH"))
        }
    }

    @Test
    fun `default companion instance works`() {
        val event = createTestEvent()
        val ics = ITipBuilder.default.createCancel(event)
        assertTrue(ics.contains("METHOD:CANCEL"))
    }

    @Nested
    @DisplayName("createAdd Tests - RFC 5546 Section 3.2.4")
    inner class CreateAddTests {

        private fun createRecurringMasterEvent(sequence: Int = 0): ICalEvent {
            return ICalEvent(
                uid = "recurring-master-123",
                importId = "recurring-master-123",
                summary = "Weekly Team Meeting",
                description = null,
                location = null,
                dtStart = ICalDateTime.parse("20231215T140000Z"),
                dtEnd = ICalDateTime.parse("20231215T150000Z"),
                duration = null,
                isAllDay = false,
                status = EventStatus.CONFIRMED,
                sequence = sequence,
                rrule = RRule(
                    freq = Frequency.WEEKLY,
                    interval = 1,
                    byDay = listOf(WeekdayNum(java.time.DayOfWeek.FRIDAY))
                ),
                exdates = emptyList(),
                recurrenceId = null,
                alarms = emptyList(),
                categories = emptyList(),
                organizer = Organizer(
                    email = "organizer@example.com",
                    name = "Organizer",
                    sentBy = null
                ),
                attendees = emptyList(),
                color = null,
                dtstamp = ICalDateTime.parse("20231215T120000Z"),
                lastModified = null,
                created = null,
                transparency = Transparency.OPAQUE,
                url = null,
                rawProperties = emptyMap()
            )
        }

        private fun createNewInstance(recurrenceId: ICalDateTime?): ICalEvent {
            return ICalEvent(
                uid = "new-instance-temp",  // Will be replaced with master UID
                importId = "new-instance-temp",
                summary = "Special Team Meeting - Extra Session",
                description = "Additional meeting added to the series",
                location = "Conference Room B",
                dtStart = ICalDateTime.parse("20231220T140000Z"),
                dtEnd = ICalDateTime.parse("20231220T160000Z"),  // Longer meeting
                duration = null,
                isAllDay = false,
                status = EventStatus.CONFIRMED,
                sequence = 99,  // Will be replaced with master sequence
                rrule = RRule(  // Should be cleared
                    freq = Frequency.DAILY,
                    interval = 1
                ),
                exdates = emptyList(),
                recurrenceId = recurrenceId,
                alarms = emptyList(),
                categories = emptyList(),
                organizer = null,
                attendees = emptyList(),
                color = null,
                dtstamp = ICalDateTime.parse("20231218T100000Z"),
                lastModified = null,
                created = null,
                transparency = Transparency.OPAQUE,
                url = null,
                rawProperties = emptyMap()
            )
        }

        @Test
        fun `createAdd sets METHOD ADD`() {
            val master = createRecurringMasterEvent()
            val newInstance = createNewInstance(ICalDateTime.parse("20231220T140000Z"))
            val attendees = listOf(
                Attendee(
                    email = "attendee@example.com",
                    name = "Attendee",
                    partStat = PartStat.ACCEPTED,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                )
            )

            val ics = builder.createAdd(master, newInstance, attendees)
            assertTrue(ics.contains("METHOD:ADD"), "ADD message must have METHOD:ADD")
        }

        @Test
        fun `createAdd preserves master UID`() {
            val master = createRecurringMasterEvent()
            val newInstance = createNewInstance(ICalDateTime.parse("20231220T140000Z"))
            val attendees = listOf(
                Attendee(
                    email = "attendee@example.com",
                    name = "Attendee",
                    partStat = PartStat.ACCEPTED,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                )
            )

            val ics = builder.createAdd(master, newInstance, attendees)
            assertTrue(ics.contains("UID:recurring-master-123"), "ADD must use master event UID")
            assertTrue(!ics.contains("UID:new-instance-temp"), "ADD must NOT use instance's original UID")
        }

        @Test
        fun `createAdd preserves master SEQUENCE`() {
            val master = createRecurringMasterEvent(sequence = 7)
            val newInstance = createNewInstance(ICalDateTime.parse("20231220T140000Z"))
            val attendees = listOf(
                Attendee(
                    email = "attendee@example.com",
                    name = "Attendee",
                    partStat = PartStat.ACCEPTED,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                )
            )

            val ics = builder.createAdd(master, newInstance, attendees)
            assertTrue(ics.contains("SEQUENCE:7"), "ADD must preserve master SEQUENCE")
            assertTrue(!ics.contains("SEQUENCE:99"), "ADD must NOT use instance's original SEQUENCE")
        }

        @Test
        fun `createAdd includes RECURRENCE-ID`() {
            val master = createRecurringMasterEvent()
            val recurrenceId = ICalDateTime.parse("20231220T140000Z")
            val newInstance = createNewInstance(recurrenceId)
            val attendees = listOf(
                Attendee(
                    email = "attendee@example.com",
                    name = "Attendee",
                    partStat = PartStat.ACCEPTED,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                )
            )

            val ics = builder.createAdd(master, newInstance, attendees)
            assertTrue(ics.contains("RECURRENCE-ID"), "ADD must include RECURRENCE-ID")
            assertTrue(ics.contains("20231220T140000Z"), "ADD must include correct RECURRENCE-ID value")
        }

        @Test
        fun `createAdd clears RRULE from instance`() {
            val master = createRecurringMasterEvent()
            val newInstance = createNewInstance(ICalDateTime.parse("20231220T140000Z"))
            val attendees = listOf(
                Attendee(
                    email = "attendee@example.com",
                    name = "Attendee",
                    partStat = PartStat.ACCEPTED,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                )
            )

            val ics = builder.createAdd(master, newInstance, attendees)
            // RRULE should not appear in the output (instances don't have RRULE)
            assertTrue(!ics.contains("RRULE:FREQ=DAILY"), "ADD instance must NOT have RRULE")
        }

        @Test
        fun `createAdd sets PARTSTAT to NEEDS-ACTION for attendees`() {
            val master = createRecurringMasterEvent()
            val newInstance = createNewInstance(ICalDateTime.parse("20231220T140000Z"))
            val attendees = listOf(
                Attendee(
                    email = "attendee1@example.com",
                    name = "Attendee 1",
                    partStat = PartStat.ACCEPTED,  // Will be changed
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                ),
                Attendee(
                    email = "attendee2@example.com",
                    name = "Attendee 2",
                    partStat = PartStat.DECLINED,  // Will be changed
                    role = AttendeeRole.OPT_PARTICIPANT,
                    rsvp = false
                )
            )

            val ics = builder.createAdd(master, newInstance, attendees)
            // All attendees should have PARTSTAT=NEEDS-ACTION
            val needsActionCount = ics.split("PARTSTAT=NEEDS-ACTION").size - 1
            assertEquals(2, needsActionCount, "All attendees must have PARTSTAT=NEEDS-ACTION")
        }

        @Test
        fun `createAdd sets RSVP TRUE for attendees`() {
            val master = createRecurringMasterEvent()
            val newInstance = createNewInstance(ICalDateTime.parse("20231220T140000Z"))
            val attendees = listOf(
                Attendee(
                    email = "attendee@example.com",
                    name = "Attendee",
                    partStat = PartStat.ACCEPTED,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false  // Will be changed to true
                )
            )

            val ics = builder.createAdd(master, newInstance, attendees)
            assertTrue(ics.contains("RSVP=TRUE"), "ADD must set RSVP=TRUE for attendees")
        }

        @Test
        fun `createAdd throws when RECURRENCE-ID is null`() {
            val master = createRecurringMasterEvent()
            val newInstance = createNewInstance(null)  // No RECURRENCE-ID
            val attendees = listOf(
                Attendee(
                    email = "attendee@example.com",
                    name = "Attendee",
                    partStat = PartStat.ACCEPTED,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                )
            )

            val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                builder.createAdd(master, newInstance, attendees)
            }
            assertTrue(
                exception.message?.contains("RECURRENCE-ID") == true,
                "Exception message should mention RECURRENCE-ID"
            )
        }

        @Test
        fun `createAdd preserves instance event details`() {
            val master = createRecurringMasterEvent()
            val newInstance = createNewInstance(ICalDateTime.parse("20231220T140000Z"))
            val attendees = listOf(
                Attendee(
                    email = "attendee@example.com",
                    name = "Attendee",
                    partStat = PartStat.ACCEPTED,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                )
            )

            val ics = builder.createAdd(master, newInstance, attendees)

            // Instance-specific details should be preserved
            assertTrue(ics.contains("Special Team Meeting"), "ADD should preserve instance SUMMARY")
            assertTrue(ics.contains("Additional meeting"), "ADD should preserve instance DESCRIPTION")
            assertTrue(ics.contains("Conference Room B"), "ADD should preserve instance LOCATION")
            assertTrue(ics.contains("20231220T140000Z"), "ADD should use instance DTSTART")
            assertTrue(ics.contains("20231220T160000Z"), "ADD should use instance DTEND")
        }

        @Test
        fun `createAdd preserves DTSTAMP`() {
            val master = createRecurringMasterEvent()
            val newInstance = createNewInstance(ICalDateTime.parse("20231220T140000Z"))
            val attendees = emptyList<Attendee>()

            val ics = builder.createAdd(master, newInstance, attendees)

            // DTSTAMP should be preserved (preserveDtstamp = true)
            assertTrue(ics.contains("DTSTAMP:20231218T100000Z"), "ADD should preserve original DTSTAMP")
        }

        @Test
        fun `createAdd handles empty attendee list`() {
            val master = createRecurringMasterEvent()
            val newInstance = createNewInstance(ICalDateTime.parse("20231220T140000Z"))
            val attendees = emptyList<Attendee>()

            // Should not throw, even with empty attendee list
            val ics = builder.createAdd(master, newInstance, attendees)
            assertTrue(ics.contains("METHOD:ADD"))
            assertTrue(ics.contains("UID:recurring-master-123"))
        }

        @Test
        fun `createAdd round-trip parsing`() {
            val master = createRecurringMasterEvent(sequence = 3)
            val recurrenceId = ICalDateTime.parse("20231220T140000Z")
            val newInstance = createNewInstance(recurrenceId)
            val attendees = listOf(
                Attendee(
                    email = "attendee@example.com",
                    name = "Attendee",
                    partStat = PartStat.TENTATIVE,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                )
            )

            val ics = builder.createAdd(master, newInstance, attendees)

            // Parse it back and verify
            val parsed = parser.parseWithMethod(ics)
            assertTrue(parsed is ParseResult.Success)

            val result = parsed.getOrNull()!!
            assertEquals(ITipMethod.ADD, result.method, "Parsed METHOD should be ADD")
            assertEquals(1, result.events.size, "Should have exactly 1 event")

            val parsedEvent = result.events[0]
            assertEquals("recurring-master-123", parsedEvent.uid, "Parsed UID should match master")
            assertEquals(3, parsedEvent.sequence, "Parsed SEQUENCE should match master")
            assertNotNull(parsedEvent.recurrenceId, "Parsed event should have RECURRENCE-ID")
        }
    }
}
