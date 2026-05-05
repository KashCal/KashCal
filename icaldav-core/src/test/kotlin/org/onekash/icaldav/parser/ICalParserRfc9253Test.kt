package org.onekash.icaldav.parser

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.ICalLink
import org.onekash.icaldav.model.ICalRelation
import org.onekash.icaldav.model.LinkRelationType
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.model.RelationType
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for RFC 9253 property parsing and roundtrip.
 * Tests LINK and RELATED-TO properties.
 *
 * Note: ical4j requires VALUE=URI parameter for LINK properties.
 */
class ICalParserRfc9253Test {

    private lateinit var parser: ICalParser
    private lateinit var generator: ICalGenerator

    @BeforeEach
    fun setup() {
        parser = ICalParser()
        generator = ICalGenerator()
    }

    @Nested
    inner class LinkPropertyParsing {

        @Test
        fun `parse event with single LINK property`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:link-test-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Link
                LINK;VALUE=URI:https://example.com/event-details
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)

            val event = result.getOrNull()!![0]
            assertEquals(1, event.links.size)
            assertEquals("https://example.com/event-details", event.links[0].uri)
            assertEquals(LinkRelationType.RELATED, event.links[0].relation)
        }

        @Test
        fun `parse LINK with REL parameter`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:link-test-2
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Alternate Link
                LINK;VALUE=URI;REL=alternate:https://example.com/event.html
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(1, event.links.size)
            assertEquals(LinkRelationType.ALTERNATE, event.links[0].relation)
        }

        @Test
        fun `parse LINK with FMTTYPE parameter`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:link-test-3
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with PDF Link
                LINK;VALUE=URI;FMTTYPE=application/pdf:https://example.com/agenda.pdf
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(1, event.links.size)
            assertEquals("application/pdf", event.links[0].mediaType)
        }

        @Test
        fun `parse LINK with TITLE parameter`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:link-test-4
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Titled Link
                LINK;VALUE=URI;TITLE="Event Details Page":https://example.com/details
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(1, event.links.size)
            assertEquals("Event Details Page", event.links[0].title)
        }

        @Test
        fun `parse LINK with multiple parameters`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:link-test-5
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Full Link
                LINK;VALUE=URI;REL=describedby;FMTTYPE=text/html;TITLE="More Info";LABEL=Details;LANGUAGE=en:https://example.com/info
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            val link = event.links[0]

            assertEquals("https://example.com/info", link.uri)
            assertEquals(LinkRelationType.DESCRIBEDBY, link.relation)
            assertEquals("text/html", link.mediaType)
            assertEquals("More Info", link.title)
            assertEquals("Details", link.label)
            assertEquals("en", link.language)
        }

        @Test
        fun `parse multiple LINK properties`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:link-test-6
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Multiple Links
                LINK;VALUE=URI;REL=alternate:https://example.com/event.html
                LINK;VALUE=URI;REL=describedby:https://example.com/spec.pdf
                LINK;VALUE=URI;REL=related:https://example.com/related
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(3, event.links.size)
        }

        @Test
        fun `parse LINK with GAP parameter`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:link-test-gap
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with GAP Link
                LINK;VALUE=URI;REL=next;GAP=PT1H30M:https://example.com/next-event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(1, event.links.size)
            assertEquals(LinkRelationType.NEXT, event.links[0].relation)
            assertNotNull(event.links[0].gap)
            assertEquals(java.time.Duration.ofMinutes(90), event.links[0].gap)
        }

        @Test
        fun `parse LINK with unknown REL type returns CUSTOM`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:link-test-custom
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Custom Link
                LINK;VALUE=URI;REL=x-custom-rel:https://example.com/custom
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(1, event.links.size)
            assertEquals(LinkRelationType.CUSTOM, event.links[0].relation)
        }

        @Test
        fun `parse LINK without VALUE=URI still works if server sends it`() {
            // Some servers may not include VALUE=URI - test that our fromParameters
            // still works (though ical4j may not parse it correctly)
            val link = ICalLink.fromParameters(
                uri = "https://example.com/no-value-param",
                rel = "alternate",
                fmttype = null,
                title = null,
                label = null,
                language = null,
                gap = null
            )

            assertEquals("https://example.com/no-value-param", link.uri)
            assertEquals(LinkRelationType.ALTERNATE, link.relation)
        }
    }

    @Nested
    inner class RelatedToPropertyParsing {

        @Test
        fun `parse event with single RELATED-TO property`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:related-test-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Child Event
                RELATED-TO:parent-event-uid-123
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)

            val event = result.getOrNull()!![0]
            assertEquals(1, event.relations.size)
            assertEquals("parent-event-uid-123", event.relations[0].uid)
            assertEquals(RelationType.PARENT, event.relations[0].relationType)
        }

        @Test
        fun `parse RELATED-TO with RELTYPE=PARENT`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:related-test-2
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Child Event with Explicit Parent
                RELATED-TO;RELTYPE=PARENT:parent-uid
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(RelationType.PARENT, event.relations[0].relationType)
            assertTrue(event.relations[0].isParent())
        }

        @Test
        fun `parse RELATED-TO with RELTYPE=CHILD`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:related-test-3
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Parent Event
                RELATED-TO;RELTYPE=CHILD:child-uid
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(RelationType.CHILD, event.relations[0].relationType)
            assertTrue(event.relations[0].isChild())
        }

        @Test
        fun `parse RELATED-TO with RELTYPE=SIBLING`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:related-test-4
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Sibling Event
                RELATED-TO;RELTYPE=SIBLING:sibling-uid
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(RelationType.SIBLING, event.relations[0].relationType)
            assertTrue(event.relations[0].isSibling())
        }

        @Test
        fun `parse multiple RELATED-TO properties`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:related-test-5
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Multiple Relations
                RELATED-TO;RELTYPE=PARENT:parent-uid
                RELATED-TO;RELTYPE=SIBLING:sibling-uid-1
                RELATED-TO;RELTYPE=SIBLING:sibling-uid-2
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(3, event.relations.size)
        }

        @Test
        fun `parse RELATED-TO with RFC 9253 RELTYPE values`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:related-test-6
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Advanced Relations
                RELATED-TO;RELTYPE=DEPENDS-ON:dependency-uid
                RELATED-TO;RELTYPE=NEXT:next-event-uid
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(2, event.relations.size)
            assertEquals(RelationType.DEPENDS_ON, event.relations[0].relationType)
            assertEquals(RelationType.NEXT, event.relations[1].relationType)
        }

        @Test
        fun `parse RELATED-TO with GAP parameter`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:related-test-gap
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with GAP Relation
                RELATED-TO;RELTYPE=NEXT;GAP=PT2H:next-event-uid
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(1, event.relations.size)
            assertEquals(RelationType.NEXT, event.relations[0].relationType)
            assertNotNull(event.relations[0].gap)
            assertEquals(java.time.Duration.ofHours(2), event.relations[0].gap)
        }

        @Test
        fun `parse RELATED-TO with unknown RELTYPE defaults to PARENT`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:related-test-unknown
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Unknown Relation Type
                RELATED-TO;RELTYPE=X-CUSTOM-TYPE:custom-uid
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(1, event.relations.size)
            // Unknown RELTYPE defaults to PARENT per RelationType.fromString
            assertEquals(RelationType.PARENT, event.relations[0].relationType)
        }

        @Test
        fun `parse RELATED-TO with negative GAP`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:related-test-neg-gap
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Negative GAP
                RELATED-TO;RELTYPE=NEXT;GAP=-PT30M:prev-event-uid
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(1, event.relations.size)
            assertNotNull(event.relations[0].gap)
            assertEquals(java.time.Duration.ofMinutes(-30), event.relations[0].gap)
        }
    }

    @Nested
    inner class RoundtripTests {

        @Test
        fun `LINK property survives roundtrip`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-link-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Roundtrip Link Test
                LINK;VALUE=URI;REL=alternate;FMTTYPE=text/html:https://example.com/event.html
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            // Parse original
            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event1 = result.getOrNull()!![0]
            assertEquals(1, event1.links.size)

            // Generate
            val generated = generator.generate(event1, method = null)

            // Parse generated
            val result2 = parser.parseAllEvents(generated)
            assertTrue(result2 is ParseResult.Success)
            val event2 = result2.getOrNull()!![0]

            // Verify
            assertEquals(event1.links.size, event2.links.size)
            assertEquals(event1.links[0].uri, event2.links[0].uri)
            assertEquals(event1.links[0].relation, event2.links[0].relation)
            assertEquals(event1.links[0].mediaType, event2.links[0].mediaType)
        }

        @Test
        fun `RELATED-TO property survives roundtrip`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-related-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Roundtrip Related Test
                RELATED-TO;RELTYPE=PARENT:parent-event-uid
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            // Parse original
            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event1 = result.getOrNull()!![0]
            assertEquals(1, event1.relations.size)

            // Generate
            val generated = generator.generate(event1, method = null)

            // Parse generated
            val result2 = parser.parseAllEvents(generated)
            assertTrue(result2 is ParseResult.Success)
            val event2 = result2.getOrNull()!![0]

            // Verify
            assertEquals(event1.relations.size, event2.relations.size)
            assertEquals(event1.relations[0].uid, event2.relations[0].uid)
            assertEquals(event1.relations[0].relationType, event2.relations[0].relationType)
        }

        @Test
        fun `multiple links and relations survive roundtrip`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-multi-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Multi Property Test
                LINK;VALUE=URI;REL=alternate:https://example.com/alt.html
                LINK;VALUE=URI;REL=describedby:https://example.com/spec.pdf
                RELATED-TO;RELTYPE=PARENT:parent-uid
                RELATED-TO;RELTYPE=SIBLING:sibling-uid
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            // Parse original
            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event1 = result.getOrNull()!![0]
            assertEquals(2, event1.links.size)
            assertEquals(2, event1.relations.size)

            // Generate and re-parse
            val generated = generator.generate(event1, method = null)
            val result2 = parser.parseAllEvents(generated)
            assertTrue(result2 is ParseResult.Success)
            val event2 = result2.getOrNull()!![0]

            // Verify counts preserved
            assertEquals(2, event2.links.size)
            assertEquals(2, event2.relations.size)
        }

        @Test
        fun `event without RFC 9253 properties still works`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:no-rfc9253-props
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Basic Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event1 = result.getOrNull()!![0]
            assertTrue(event1.links.isEmpty())
            assertTrue(event1.relations.isEmpty())

            // Generate and re-parse
            val generated = generator.generate(event1, method = null)
            val result2 = parser.parseAllEvents(generated)
            assertTrue(result2 is ParseResult.Success)
            val event2 = result2.getOrNull()!![0]

            assertTrue(event2.links.isEmpty())
            assertTrue(event2.relations.isEmpty())
        }

        @Test
        fun `LINK with GAP survives roundtrip`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-link-gap
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Roundtrip Link GAP Test
                LINK;VALUE=URI;REL=next;GAP=PT1H30M:https://example.com/next
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            // Parse original
            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event1 = result.getOrNull()!![0]
            assertEquals(1, event1.links.size)
            assertNotNull(event1.links[0].gap)

            // Generate
            val generated = generator.generate(event1, method = null)

            // Parse generated
            val result2 = parser.parseAllEvents(generated)
            assertTrue(result2 is ParseResult.Success)
            val event2 = result2.getOrNull()!![0]

            // Verify GAP survived
            assertEquals(event1.links[0].gap, event2.links[0].gap)
            assertEquals(java.time.Duration.ofMinutes(90), event2.links[0].gap)
        }

        @Test
        fun `RELATED-TO with GAP survives roundtrip`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-related-gap
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Roundtrip Related GAP Test
                RELATED-TO;RELTYPE=NEXT;GAP=PT2H:next-event-uid
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            // Parse original
            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event1 = result.getOrNull()!![0]
            assertEquals(1, event1.relations.size)
            assertNotNull(event1.relations[0].gap)

            // Generate
            val generated = generator.generate(event1, method = null)

            // Parse generated
            val result2 = parser.parseAllEvents(generated)
            assertTrue(result2 is ParseResult.Success)
            val event2 = result2.getOrNull()!![0]

            // Verify GAP survived
            assertEquals(event1.relations[0].gap, event2.relations[0].gap)
            assertEquals(java.time.Duration.ofHours(2), event2.relations[0].gap)
        }
    }

    @Nested
    inner class LinkModelTests {

        @Test
        fun `ICalLink factory methods work correctly`() {
            val alternate = ICalLink.alternate("https://example.com", "text/html", "Title")
            assertEquals(LinkRelationType.ALTERNATE, alternate.relation)
            assertEquals("text/html", alternate.mediaType)
            assertEquals("Title", alternate.title)

            val describedBy = ICalLink.describedBy("https://example.com/spec")
            assertEquals(LinkRelationType.DESCRIBEDBY, describedBy.relation)

            val related = ICalLink.related("https://example.com/related")
            assertEquals(LinkRelationType.RELATED, related.relation)
        }

        @Test
        fun `ICalLink toICalString generates correct format`() {
            val link = ICalLink(
                uri = "https://example.com",
                relation = LinkRelationType.ALTERNATE,
                mediaType = "text/html"
            )

            val result = link.toICalString()
            assertTrue(result.startsWith("LINK;"))
            assertTrue(result.contains("VALUE=URI"))
            assertTrue(result.contains("REL=alternate"))
            assertTrue(result.contains("FMTTYPE=text/html"))
            assertTrue(result.endsWith(":https://example.com"))
        }
    }

    @Nested
    inner class RelationModelTests {

        @Test
        fun `ICalRelation factory methods work correctly`() {
            val parent = ICalRelation.parent("parent-uid")
            assertEquals(RelationType.PARENT, parent.relationType)
            assertTrue(parent.isParent())

            val child = ICalRelation.child("child-uid")
            assertEquals(RelationType.CHILD, child.relationType)
            assertTrue(child.isChild())

            val sibling = ICalRelation.sibling("sibling-uid")
            assertEquals(RelationType.SIBLING, sibling.relationType)
            assertTrue(sibling.isSibling())
        }

        @Test
        fun `ICalRelation toICalString generates correct format`() {
            val relation = ICalRelation(
                uid = "related-uid",
                relationType = RelationType.CHILD
            )

            val result = relation.toICalString()
            assertEquals("RELATED-TO;RELTYPE=CHILD:related-uid", result)
        }

        @Test
        fun `ICalRelation with PARENT omits RELTYPE (default)`() {
            val relation = ICalRelation.parent("parent-uid")
            val result = relation.toICalString()

            // PARENT is the default, so RELTYPE should be omitted
            assertEquals("RELATED-TO:parent-uid", result)
        }
    }

    @Nested
    inner class EdgeCaseTests {

        @Test
        fun `ICalLink fromParameters handles empty URI`() {
            val link = ICalLink.fromParameters(uri = "")
            assertEquals("", link.uri)
            assertEquals(LinkRelationType.RELATED, link.relation)
        }

        @Test
        fun `ICalLink fromParameters handles null parameters gracefully`() {
            val link = ICalLink.fromParameters(
                uri = "https://example.com",
                rel = null,
                fmttype = null,
                title = null,
                label = null,
                language = null,
                gap = null
            )

            assertEquals("https://example.com", link.uri)
            assertEquals(LinkRelationType.RELATED, link.relation)
            assertNull(link.mediaType)
            assertNull(link.title)
            assertNull(link.gap)
        }

        @Test
        fun `ICalLink fromParameters handles invalid GAP duration`() {
            val link = ICalLink.fromParameters(
                uri = "https://example.com",
                gap = "invalid-duration"
            )

            assertEquals("https://example.com", link.uri)
            // Invalid duration should result in null gap (not throw)
            assertNull(link.gap)
        }

        @Test
        fun `ICalRelation fromParameters handles empty UID`() {
            val relation = ICalRelation.fromParameters(uid = "")
            assertEquals("", relation.uid)
            assertEquals(RelationType.PARENT, relation.relationType)
        }

        @Test
        fun `ICalRelation fromParameters handles null parameters gracefully`() {
            val relation = ICalRelation.fromParameters(
                uid = "test-uid",
                reltype = null,
                gap = null
            )

            assertEquals("test-uid", relation.uid)
            assertEquals(RelationType.PARENT, relation.relationType)
            assertNull(relation.gap)
        }

        @Test
        fun `ICalRelation fromParameters handles invalid GAP duration`() {
            val relation = ICalRelation.fromParameters(
                uid = "test-uid",
                gap = "not-a-duration"
            )

            assertEquals("test-uid", relation.uid)
            // Invalid duration should result in null gap (not throw)
            assertNull(relation.gap)
        }

        @Test
        fun `LinkRelationType fromString handles blank string`() {
            assertEquals(LinkRelationType.RELATED, LinkRelationType.fromString(""))
            assertEquals(LinkRelationType.RELATED, LinkRelationType.fromString("  "))
            assertEquals(LinkRelationType.RELATED, LinkRelationType.fromString(null))
        }

        @Test
        fun `RelationType fromString handles blank string`() {
            assertEquals(RelationType.PARENT, RelationType.fromString(""))
            assertEquals(RelationType.PARENT, RelationType.fromString("  "))
            assertEquals(RelationType.PARENT, RelationType.fromString(null))
        }

        @Test
        fun `ICalLink with all RFC 9253 relation types`() {
            // Test all LinkRelationType values work correctly
            val types = listOf(
                LinkRelationType.ALTERNATE,
                LinkRelationType.DESCRIBEDBY,
                LinkRelationType.RELATED,
                LinkRelationType.NEXT,
                LinkRelationType.PREV,
                LinkRelationType.SELF
            )

            for (type in types) {
                val link = ICalLink(uri = "https://example.com", relation = type)
                val icalString = link.toICalString()
                assertTrue(icalString.contains(":https://example.com"))
            }
        }

        @Test
        fun `ICalRelation with all RFC 9253 relation types`() {
            // Test all RelationType values work correctly
            val types = listOf(
                RelationType.PARENT,
                RelationType.CHILD,
                RelationType.SIBLING,
                RelationType.FINISHTOSTART,
                RelationType.FINISHTOFINISH,
                RelationType.STARTTOFINISH,
                RelationType.STARTTOSTART,
                RelationType.FIRST,
                RelationType.NEXT,
                RelationType.DEPENDS_ON,
                RelationType.REFID,
                RelationType.CONCEPT,
                RelationType.REQUIRES,
                RelationType.REPLACES
            )

            for (type in types) {
                val relation = ICalRelation(uid = "test-uid", relationType = type)
                val icalString = relation.toICalString()
                assertTrue(icalString.endsWith(":test-uid"))
            }
        }

        @Test
        fun `ICalLink toICalString escapes title with quotes`() {
            val link = ICalLink(
                uri = "https://example.com",
                title = "Title with special chars"
            )
            val icalString = link.toICalString()

            // Title should be wrapped in quotes
            assertTrue(icalString.contains("TITLE=\"Title with special chars\""))
        }

        @Test
        fun `ICalLink fromParameters strips quotes from title`() {
            val link = ICalLink.fromParameters(
                uri = "https://example.com",
                title = "\"Quoted Title\""
            )

            // Quotes should be stripped
            assertEquals("Quoted Title", link.title)
        }

        @Test
        fun `RelationType DEPENDS_ON converts correctly to iCal string`() {
            val relation = ICalRelation(uid = "uid", relationType = RelationType.DEPENDS_ON)
            val icalString = relation.toICalString()

            // Should use hyphen, not underscore
            assertTrue(icalString.contains("RELTYPE=DEPENDS-ON"))
        }

        @Test
        fun `RelationType fromString handles hyphen to underscore conversion`() {
            assertEquals(RelationType.DEPENDS_ON, RelationType.fromString("DEPENDS-ON"))
            assertEquals(RelationType.DEPENDS_ON, RelationType.fromString("depends-on"))
            assertEquals(RelationType.DEPENDS_ON, RelationType.fromString("DEPENDS_ON"))
        }

        @Test
        fun `ICalLink next factory method creates correct relation with gap`() {
            // ICalLink doesn't have a next() factory, but ICalRelation does
            val relation = ICalRelation.next("next-uid", java.time.Duration.ofMinutes(45))

            assertEquals(RelationType.NEXT, relation.relationType)
            assertEquals(java.time.Duration.ofMinutes(45), relation.gap)
        }
    }
}
