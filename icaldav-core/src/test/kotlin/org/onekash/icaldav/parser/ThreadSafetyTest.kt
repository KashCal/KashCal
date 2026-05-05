package org.onekash.icaldav.parser

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.Frequency
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.model.Transparency
import org.onekash.icaldav.recurrence.RRuleExpander
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Thread safety tests for iCalendar parsing and processing.
 *
 * These tests verify that the core parsing and expansion operations
 * are safe for concurrent use from multiple threads.
 *
 * Important for:
 * - Android apps with background sync
 * - Server applications handling multiple requests
 * - Libraries used in multi-threaded environments
 */
@DisplayName("Thread Safety Tests")
class ThreadSafetyTest {

    @Nested
    @DisplayName("ICalParser Thread Safety")
    inner class ParserThreadSafetyTests {

        @Test
        fun `concurrent parsing of different events is safe`() {
            val parser = ICalParser()
            val threadCount = 10
            val iterationsPerThread = 100
            val executor = Executors.newFixedThreadPool(threadCount)
            val errors = ConcurrentLinkedQueue<Throwable>()
            val successCount = AtomicInteger(0)
            val latch = CountDownLatch(threadCount)

            repeat(threadCount) { threadNum ->
                executor.submit {
                    try {
                        repeat(iterationsPerThread) { iteration ->
                            val ical = createUniqueEvent(threadNum, iteration)
                            val result = parser.parseAllEvents(ical)

                            assertTrue(result is ParseResult.Success,
                                "Thread $threadNum iteration $iteration failed to parse")

                            val events = result.getOrNull()!!
                            assertEquals(1, events.size)
                            assertEquals("thread-$threadNum-event-$iteration", events[0].uid)

                            successCount.incrementAndGet()
                        }
                    } catch (e: Throwable) {
                        errors.add(e)
                    } finally {
                        latch.countDown()
                    }
                }
            }

            assertTrue(latch.await(60, TimeUnit.SECONDS), "Test timed out")
            executor.shutdown()

            assertTrue(errors.isEmpty(), "Errors occurred: ${errors.map { it.message }}")
            assertEquals(threadCount * iterationsPerThread, successCount.get())
        }

        @Test
        fun `concurrent parsing of same event template is safe`() {
            val parser = ICalParser()
            val threadCount = 20
            val executor = Executors.newFixedThreadPool(threadCount)
            val errors = ConcurrentLinkedQueue<Throwable>()
            val latch = CountDownLatch(threadCount)

            // All threads parse the same event
            val sharedIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Thread Safety//EN
                BEGIN:VEVENT
                UID:shared-event-uid
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Shared Event for Thread Safety Test
                DESCRIPTION:This event is parsed concurrently by many threads
                RRULE:FREQ=DAILY;COUNT=10
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            repeat(threadCount) { threadNum ->
                executor.submit {
                    try {
                        repeat(50) {
                            val result = parser.parseAllEvents(sharedIcal)
                            assertTrue(result is ParseResult.Success)
                            val events = result.getOrNull()!!
                            assertEquals(1, events.size)
                            assertEquals("shared-event-uid", events[0].uid)
                            assertEquals(Frequency.DAILY, events[0].rrule?.freq)
                        }
                    } catch (e: Throwable) {
                        errors.add(e)
                    } finally {
                        latch.countDown()
                    }
                }
            }

            assertTrue(latch.await(60, TimeUnit.SECONDS), "Test timed out")
            executor.shutdown()

            assertTrue(errors.isEmpty(), "Errors occurred: ${errors.map { it.message }}")
        }

        @Test
        fun `multiple parser instances are independent`() {
            val threadCount = 5
            val executor = Executors.newFixedThreadPool(threadCount)
            val errors = ConcurrentLinkedQueue<Throwable>()
            val latch = CountDownLatch(threadCount)

            repeat(threadCount) { threadNum ->
                executor.submit {
                    try {
                        // Each thread creates its own parser instance
                        val parser = ICalParser()

                        repeat(100) { iteration ->
                            val ical = createUniqueEvent(threadNum, iteration)
                            val result = parser.parseAllEvents(ical)
                            assertTrue(result is ParseResult.Success)
                        }
                    } catch (e: Throwable) {
                        errors.add(e)
                    } finally {
                        latch.countDown()
                    }
                }
            }

            assertTrue(latch.await(60, TimeUnit.SECONDS), "Test timed out")
            executor.shutdown()

            assertTrue(errors.isEmpty(), "Errors occurred: ${errors.map { it.message }}")
        }

        @RepeatedTest(5)
        fun `static configuration is thread-safe`() {
            // Test the double-checked locking in ICalParser.ensureConfigured()
            val threadCount = 50
            val executor = Executors.newFixedThreadPool(threadCount)
            val errors = ConcurrentLinkedQueue<Throwable>()
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(threadCount)

            repeat(threadCount) {
                executor.submit {
                    try {
                        startLatch.await() // All threads start at the same time
                        ICalParser.ensureConfigured()
                        // Create parser and use it
                        val parser = ICalParser()
                        val result = parser.parseAllEvents(SIMPLE_EVENT)
                        assertTrue(result is ParseResult.Success)
                    } catch (e: Throwable) {
                        errors.add(e)
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }

            startLatch.countDown() // Release all threads simultaneously
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Test timed out")
            executor.shutdown()

            assertTrue(errors.isEmpty(), "Configuration race condition: ${errors.map { it.message }}")
        }
    }

    @Nested
    @DisplayName("RRuleExpander Thread Safety")
    inner class ExpanderThreadSafetyTests {

        @Test
        fun `concurrent expansion of different events is safe`() {
            val expander = RRuleExpander()
            val threadCount = 10
            val executor = Executors.newFixedThreadPool(threadCount)
            val errors = ConcurrentLinkedQueue<Throwable>()
            val latch = CountDownLatch(threadCount)
            val zone = ZoneId.of("America/New_York")

            repeat(threadCount) { threadNum ->
                executor.submit {
                    try {
                        repeat(50) { iteration ->
                            val event = createRecurringEvent(
                                uid = "thread-$threadNum-recurring-$iteration",
                                startYear = 2024,
                                startMonth = 1,
                                startDay = (iteration % 28) + 1,
                                zone = zone
                            )

                            val start = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, zone).toInstant()
                            val end = ZonedDateTime.of(2024, 12, 31, 0, 0, 0, 0, zone).toInstant()

                            val occurrences = expander.expand(event, start, end)

                            assertTrue(occurrences.isNotEmpty(),
                                "Thread $threadNum should have occurrences")
                            assertTrue(occurrences.size <= 10,
                                "Should have at most 10 occurrences (COUNT=10)")
                        }
                    } catch (e: Throwable) {
                        errors.add(e)
                    } finally {
                        latch.countDown()
                    }
                }
            }

            assertTrue(latch.await(60, TimeUnit.SECONDS), "Test timed out")
            executor.shutdown()

            assertTrue(errors.isEmpty(), "Errors occurred: ${errors.map { it.message }}")
        }

        @Test
        fun `concurrent expansion with shared expander instance is safe`() {
            // Single shared expander used by all threads
            val sharedExpander = RRuleExpander()
            val threadCount = 20
            val executor = Executors.newFixedThreadPool(threadCount)
            val errors = ConcurrentLinkedQueue<Throwable>()
            val latch = CountDownLatch(threadCount)
            val zone = ZoneId.of("UTC")

            // Create a shared recurring event
            val sharedEvent = createRecurringEvent(
                uid = "shared-recurring",
                startYear = 2024,
                startMonth = 6,
                startDay = 15,
                zone = zone
            )

            val start = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 12, 31, 0, 0, 0, 0, zone).toInstant()

            repeat(threadCount) {
                executor.submit {
                    try {
                        repeat(100) {
                            val occurrences = sharedExpander.expand(sharedEvent, start, end)
                            assertEquals(10, occurrences.size, "Should have exactly 10 occurrences")

                            // Verify first occurrence
                            val first = occurrences.first()
                            val firstZdt = first.dtStart.toZonedDateTime()
                            assertEquals(2024, firstZdt.year)
                            assertEquals(6, firstZdt.monthValue)
                            assertEquals(15, firstZdt.dayOfMonth)
                        }
                    } catch (e: Throwable) {
                        errors.add(e)
                    } finally {
                        latch.countDown()
                    }
                }
            }

            assertTrue(latch.await(60, TimeUnit.SECONDS), "Test timed out")
            executor.shutdown()

            assertTrue(errors.isEmpty(), "Errors occurred: ${errors.map { it.message }}")
        }

        @Test
        fun `DST expansion is thread-safe`() {
            val expander = RRuleExpander()
            val threadCount = 10
            val executor = Executors.newFixedThreadPool(threadCount)
            val errors = ConcurrentLinkedQueue<Throwable>()
            val latch = CountDownLatch(threadCount)
            val zone = ZoneId.of("America/New_York")

            // Event that spans DST transition
            val dstEvent = createRecurringEvent(
                uid = "dst-event",
                startYear = 2024,
                startMonth = 3,
                startDay = 1,
                zone = zone
            )

            val start = ZonedDateTime.of(2024, 3, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 3, 31, 0, 0, 0, 0, zone).toInstant()

            repeat(threadCount) {
                executor.submit {
                    try {
                        repeat(50) {
                            val occurrences = expander.expand(dstEvent, start, end)
                            assertTrue(occurrences.isNotEmpty())

                            // All occurrences should be at 10:00 local time
                            occurrences.forEach { occ ->
                                val zdt = occ.dtStart.toZonedDateTime()
                                assertEquals(10, zdt.hour,
                                    "Hour should be 10 regardless of DST")
                            }
                        }
                    } catch (e: Throwable) {
                        errors.add(e)
                    } finally {
                        latch.countDown()
                    }
                }
            }

            assertTrue(latch.await(60, TimeUnit.SECONDS), "Test timed out")
            executor.shutdown()

            assertTrue(errors.isEmpty(), "DST handling errors: ${errors.map { it.message }}")
        }
    }

    @Nested
    @DisplayName("ICalGenerator Thread Safety")
    inner class GeneratorThreadSafetyTests {

        @Test
        fun `concurrent generation is safe`() {
            val parser = ICalParser()
            val threadCount = 10
            val executor = Executors.newFixedThreadPool(threadCount)
            val errors = ConcurrentLinkedQueue<Throwable>()
            val latch = CountDownLatch(threadCount)

            repeat(threadCount) { threadNum ->
                executor.submit {
                    try {
                        repeat(50) { iteration ->
                            val ical = createUniqueEvent(threadNum, iteration)
                            val parseResult = parser.parseAllEvents(ical)
                            assertTrue(parseResult is ParseResult.Success)

                            val event = parseResult.getOrNull()!![0]
                            val generator = org.onekash.icaldav.parser.ICalGenerator()
                            val generated = generator.generate(event, method = null)

                            assertTrue(generated.contains("BEGIN:VCALENDAR"))
                            assertTrue(generated.contains("UID:thread-$threadNum-event-$iteration"))
                            assertTrue(generated.contains("END:VCALENDAR"))
                        }
                    } catch (e: Throwable) {
                        errors.add(e)
                    } finally {
                        latch.countDown()
                    }
                }
            }

            assertTrue(latch.await(60, TimeUnit.SECONDS), "Test timed out")
            executor.shutdown()

            assertTrue(errors.isEmpty(), "Errors occurred: ${errors.map { it.message }}")
        }
    }

    @Nested
    @DisplayName("Parse-Generate-Parse Roundtrip Thread Safety")
    inner class RoundtripThreadSafetyTests {

        @Test
        fun `concurrent roundtrip operations are safe`() {
            val threadCount = 10
            val executor = Executors.newFixedThreadPool(threadCount)
            val errors = ConcurrentLinkedQueue<Throwable>()
            val latch = CountDownLatch(threadCount)

            repeat(threadCount) { threadNum ->
                executor.submit {
                    try {
                        val parser = ICalParser()
                        val generator = org.onekash.icaldav.parser.ICalGenerator()

                        repeat(30) { iteration ->
                            val originalIcal = createComplexEvent(threadNum, iteration)

                            // Parse
                            val parseResult1 = parser.parseAllEvents(originalIcal)
                            assertTrue(parseResult1 is ParseResult.Success)
                            val event1 = parseResult1.getOrNull()!![0]

                            // Generate
                            val generatedIcal = generator.generate(event1, method = null)

                            // Parse again
                            val parseResult2 = parser.parseAllEvents(generatedIcal)
                            assertTrue(parseResult2 is ParseResult.Success)
                            val event2 = parseResult2.getOrNull()!![0]

                            // Verify roundtrip preserved data
                            assertEquals(event1.uid, event2.uid)
                            assertEquals(event1.summary, event2.summary)
                            assertEquals(event1.isAllDay, event2.isAllDay)
                        }
                    } catch (e: Throwable) {
                        errors.add(e)
                    } finally {
                        latch.countDown()
                    }
                }
            }

            assertTrue(latch.await(120, TimeUnit.SECONDS), "Test timed out")
            executor.shutdown()

            assertTrue(errors.isEmpty(), "Roundtrip errors: ${errors.map { it.message }}")
        }
    }

    // Helper functions

    private fun createUniqueEvent(threadNum: Int, iteration: Int): String {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Thread Safety//EN
            BEGIN:VEVENT
            UID:thread-$threadNum-event-$iteration
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T${(iteration % 24).toString().padStart(2, '0')}0000Z
            DTEND:20231215T${((iteration % 24) + 1).toString().padStart(2, '0')}0000Z
            SUMMARY:Thread $threadNum Event $iteration
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
    }

    private fun createComplexEvent(threadNum: Int, iteration: Int): String {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Thread Safety//EN
            BEGIN:VEVENT
            UID:complex-$threadNum-$iteration
            DTSTAMP:20231215T100000Z
            DTSTART;TZID=America/New_York:20231215T140000
            DTEND;TZID=America/New_York:20231215T150000
            SUMMARY:Complex Event $threadNum-$iteration
            DESCRIPTION:Description with special chars\, and newlines\n
            LOCATION:Room ${iteration % 10}
            RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=10
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
    }

    private fun createRecurringEvent(
        uid: String,
        startYear: Int,
        startMonth: Int,
        startDay: Int,
        zone: ZoneId
    ): ICalEvent {
        val dtStart = ICalDateTime(
            timestamp = ZonedDateTime.of(startYear, startMonth, startDay, 10, 0, 0, 0, zone)
                .toInstant().toEpochMilli(),
            timezone = zone,
            isUtc = false,
            isDate = false
        )

        val dtEnd = ICalDateTime(
            timestamp = ZonedDateTime.of(startYear, startMonth, startDay, 11, 0, 0, 0, zone)
                .toInstant().toEpochMilli(),
            timezone = zone,
            isUtc = false,
            isDate = false
        )

        return ICalEvent(
            uid = uid,
            importId = uid,
            summary = "Recurring Test Event",
            description = null,
            location = null,
            dtStart = dtStart,
            dtEnd = dtEnd,
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = RRule(
                freq = Frequency.DAILY,
                count = 10
            ),
            exdates = emptyList(),
            recurrenceId = null,
            alarms = emptyList(),
            categories = emptyList(),
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = null,
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )
    }

    companion object {
        private val SIMPLE_EVENT = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:simple-test
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Simple Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
    }
}
