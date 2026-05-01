package org.onekash.icaldav.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests for ICalLink, ICalRelation, LinkRelationType, and RelationType (RFC 9253).
 */
class ICalRelationTest {

    @Nested
    inner class LinkRelationTypeTests {

        @Test
        fun `fromString parses common types correctly`() {
            assertEquals(LinkRelationType.ALTERNATE, LinkRelationType.fromString("ALTERNATE"))
            assertEquals(LinkRelationType.ALTERNATE, LinkRelationType.fromString("alternate"))
            assertEquals(LinkRelationType.DESCRIBEDBY, LinkRelationType.fromString("DESCRIBEDBY"))
            assertEquals(LinkRelationType.DESCRIBEDBY, LinkRelationType.fromString("describedby"))
            assertEquals(LinkRelationType.RELATED, LinkRelationType.fromString("RELATED"))
            assertEquals(LinkRelationType.NEXT, LinkRelationType.fromString("NEXT"))
            assertEquals(LinkRelationType.PREV, LinkRelationType.fromString("PREV"))
            assertEquals(LinkRelationType.SELF, LinkRelationType.fromString("SELF"))
        }

        @Test
        fun `fromString returns RELATED for null or blank`() {
            assertEquals(LinkRelationType.RELATED, LinkRelationType.fromString(null))
            assertEquals(LinkRelationType.RELATED, LinkRelationType.fromString(""))
            assertEquals(LinkRelationType.RELATED, LinkRelationType.fromString("   "))
        }

        @Test
        fun `fromString returns CUSTOM for unknown values`() {
            assertEquals(LinkRelationType.CUSTOM, LinkRelationType.fromString("x-custom"))
            assertEquals(LinkRelationType.CUSTOM, LinkRelationType.fromString("unknown"))
        }

        @Test
        fun `toICalString returns lowercase values`() {
            assertEquals("alternate", LinkRelationType.ALTERNATE.toICalString())
            assertEquals("describedby", LinkRelationType.DESCRIBEDBY.toICalString())
            assertEquals("related", LinkRelationType.RELATED.toICalString())
            assertEquals("next", LinkRelationType.NEXT.toICalString())
            assertEquals("prev", LinkRelationType.PREV.toICalString())
        }
    }

    @Nested
    inner class RelationTypeTests {

        @Test
        fun `fromString parses RFC 5545 types correctly`() {
            assertEquals(RelationType.PARENT, RelationType.fromString("PARENT"))
            assertEquals(RelationType.PARENT, RelationType.fromString("parent"))
            assertEquals(RelationType.CHILD, RelationType.fromString("CHILD"))
            assertEquals(RelationType.SIBLING, RelationType.fromString("SIBLING"))
        }

        @Test
        fun `fromString parses RFC 9253 types correctly`() {
            assertEquals(RelationType.FINISHTOSTART, RelationType.fromString("FINISHTOSTART"))
            assertEquals(RelationType.FINISHTOFINISH, RelationType.fromString("FINISHTOFINISH"))
            assertEquals(RelationType.STARTTOFINISH, RelationType.fromString("STARTTOFINISH"))
            assertEquals(RelationType.STARTTOSTART, RelationType.fromString("STARTTOSTART"))
            assertEquals(RelationType.FIRST, RelationType.fromString("FIRST"))
            assertEquals(RelationType.NEXT, RelationType.fromString("NEXT"))
            assertEquals(RelationType.DEPENDS_ON, RelationType.fromString("DEPENDS-ON"))
            assertEquals(RelationType.REFID, RelationType.fromString("REFID"))
            assertEquals(RelationType.CONCEPT, RelationType.fromString("CONCEPT"))
            assertEquals(RelationType.REQUIRES, RelationType.fromString("REQUIRES"))
            assertEquals(RelationType.REPLACES, RelationType.fromString("REPLACES"))
        }

        @Test
        fun `fromString normalizes hyphens to underscores`() {
            assertEquals(RelationType.DEPENDS_ON, RelationType.fromString("DEPENDS_ON"))
            assertEquals(RelationType.DEPENDS_ON, RelationType.fromString("depends-on"))
        }

        @Test
        fun `fromString returns PARENT for null or blank`() {
            assertEquals(RelationType.PARENT, RelationType.fromString(null))
            assertEquals(RelationType.PARENT, RelationType.fromString(""))
        }

        @Test
        fun `fromString returns PARENT for unknown values`() {
            assertEquals(RelationType.PARENT, RelationType.fromString("UNKNOWN"))
        }

        @Test
        fun `toICalString returns correct values with hyphens`() {
            assertEquals("PARENT", RelationType.PARENT.toICalString())
            assertEquals("CHILD", RelationType.CHILD.toICalString())
            assertEquals("SIBLING", RelationType.SIBLING.toICalString())
            assertEquals("DEPENDS-ON", RelationType.DEPENDS_ON.toICalString())
            assertEquals("FINISHTOSTART", RelationType.FINISHTOSTART.toICalString())
        }
    }

    @Nested
    inner class ICalLinkConstructorTests {

        @Test
        fun `create link with uri only`() {
            val link = ICalLink(uri = "https://example.com/event")

            assertEquals("https://example.com/event", link.uri)
            assertEquals(LinkRelationType.RELATED, link.relation)
            assertNull(link.mediaType)
            assertNull(link.title)
            assertNull(link.label)
            assertNull(link.language)
            assertNull(link.gap)
        }

        @Test
        fun `create link with all parameters`() {
            val link = ICalLink(
                uri = "https://example.com/event.html",
                relation = LinkRelationType.ALTERNATE,
                mediaType = "text/html",
                title = "Event Details",
                label = "View Details",
                language = "en-US",
                gap = Duration.ofHours(1)
            )

            assertEquals("https://example.com/event.html", link.uri)
            assertEquals(LinkRelationType.ALTERNATE, link.relation)
            assertEquals("text/html", link.mediaType)
            assertEquals("Event Details", link.title)
            assertEquals("View Details", link.label)
            assertEquals("en-US", link.language)
            assertEquals(Duration.ofHours(1), link.gap)
        }
    }

    @Nested
    inner class ICalLinkToICalStringTests {

        @Test
        fun `toICalString with uri only uses default relation`() {
            val link = ICalLink(uri = "https://example.com/event")
            val ical = link.toICalString()

            // VALUE=URI is required for proper parsing by ical4j
            assertEquals("LINK;VALUE=URI:https://example.com/event", ical)
        }

        @Test
        fun `toICalString includes REL when not RELATED`() {
            val link = ICalLink(
                uri = "https://example.com/event",
                relation = LinkRelationType.ALTERNATE
            )
            val ical = link.toICalString()

            assertEquals("LINK;VALUE=URI;REL=alternate:https://example.com/event", ical)
        }

        @Test
        fun `toICalString includes FMTTYPE when present`() {
            val link = ICalLink(
                uri = "https://example.com/event.html",
                mediaType = "text/html"
            )
            val ical = link.toICalString()

            assertEquals("LINK;VALUE=URI;FMTTYPE=text/html:https://example.com/event.html", ical)
        }

        @Test
        fun `toICalString includes all parameters`() {
            val link = ICalLink(
                uri = "https://example.com/event",
                relation = LinkRelationType.DESCRIBEDBY,
                mediaType = "application/pdf",
                title = "Event Spec",
                label = "PDF",
                language = "en"
            )
            val ical = link.toICalString()

            assertTrue(ical.startsWith("LINK;"))
            assertTrue(ical.contains("REL=describedby"))
            assertTrue(ical.contains("FMTTYPE=application/pdf"))
            assertTrue(ical.contains("TITLE=\"Event Spec\""))
            assertTrue(ical.contains("LABEL=PDF"))
            assertTrue(ical.contains("LANGUAGE=en"))
            assertTrue(ical.endsWith(":https://example.com/event"))
        }
    }

    @Nested
    inner class ICalLinkFactoryMethodsTests {

        @Test
        fun `alternate creates link with ALTERNATE relation`() {
            val link = ICalLink.alternate(
                uri = "https://example.com/event.html",
                mediaType = "text/html",
                title = "HTML Version"
            )

            assertEquals(LinkRelationType.ALTERNATE, link.relation)
            assertEquals("text/html", link.mediaType)
            assertEquals("HTML Version", link.title)
        }

        @Test
        fun `describedBy creates link with DESCRIBEDBY relation`() {
            val link = ICalLink.describedBy(
                uri = "https://example.com/spec.pdf",
                title = "Specification"
            )

            assertEquals(LinkRelationType.DESCRIBEDBY, link.relation)
            assertEquals("Specification", link.title)
        }

        @Test
        fun `related creates link with RELATED relation`() {
            val link = ICalLink.related(
                uri = "https://example.com/related-event"
            )

            assertEquals(LinkRelationType.RELATED, link.relation)
        }

        @Test
        fun `fromParameters parses all values`() {
            val link = ICalLink.fromParameters(
                uri = "https://example.com/event",
                rel = "alternate",
                fmttype = "text/html",
                title = "\"Event Title\"",
                label = "Label",
                language = "en",
                gap = "PT1H"
            )

            assertEquals("https://example.com/event", link.uri)
            assertEquals(LinkRelationType.ALTERNATE, link.relation)
            assertEquals("text/html", link.mediaType)
            assertEquals("Event Title", link.title)
            assertEquals("Label", link.label)
            assertEquals("en", link.language)
            assertEquals(Duration.ofHours(1), link.gap)
        }
    }

    @Nested
    inner class ICalRelationConstructorTests {

        @Test
        fun `create relation with uid only`() {
            val relation = ICalRelation(uid = "parent-uid")

            assertEquals("parent-uid", relation.uid)
            assertEquals(RelationType.PARENT, relation.relationType)
            assertNull(relation.gap)
        }

        @Test
        fun `create relation with all parameters`() {
            val relation = ICalRelation(
                uid = "next-event-uid",
                relationType = RelationType.NEXT,
                gap = Duration.ofMinutes(30)
            )

            assertEquals("next-event-uid", relation.uid)
            assertEquals(RelationType.NEXT, relation.relationType)
            assertEquals(Duration.ofMinutes(30), relation.gap)
        }
    }

    @Nested
    inner class ICalRelationToICalStringTests {

        @Test
        fun `toICalString with PARENT relation omits RELTYPE`() {
            val relation = ICalRelation(uid = "parent-uid")
            val ical = relation.toICalString()

            assertEquals("RELATED-TO:parent-uid", ical)
        }

        @Test
        fun `toICalString includes RELTYPE when not PARENT`() {
            val relation = ICalRelation(
                uid = "child-uid",
                relationType = RelationType.CHILD
            )
            val ical = relation.toICalString()

            assertEquals("RELATED-TO;RELTYPE=CHILD:child-uid", ical)
        }

        @Test
        fun `toICalString includes GAP when present`() {
            val relation = ICalRelation(
                uid = "next-uid",
                relationType = RelationType.NEXT,
                gap = Duration.ofHours(2)
            )
            val ical = relation.toICalString()

            assertTrue(ical.contains("RELTYPE=NEXT"))
            assertTrue(ical.contains("GAP=PT2H"))
            assertTrue(ical.endsWith(":next-uid"))
        }
    }

    @Nested
    inner class ICalRelationHelperMethodsTests {

        @Test
        fun `isParent returns true for PARENT relation`() {
            val relation = ICalRelation(uid = "uid", relationType = RelationType.PARENT)
            assertTrue(relation.isParent())
            assertFalse(relation.isChild())
            assertFalse(relation.isSibling())
        }

        @Test
        fun `isChild returns true for CHILD relation`() {
            val relation = ICalRelation(uid = "uid", relationType = RelationType.CHILD)
            assertFalse(relation.isParent())
            assertTrue(relation.isChild())
            assertFalse(relation.isSibling())
        }

        @Test
        fun `isSibling returns true for SIBLING relation`() {
            val relation = ICalRelation(uid = "uid", relationType = RelationType.SIBLING)
            assertFalse(relation.isParent())
            assertFalse(relation.isChild())
            assertTrue(relation.isSibling())
        }
    }

    @Nested
    inner class ICalRelationFactoryMethodsTests {

        @Test
        fun `parent creates PARENT relation`() {
            val relation = ICalRelation.parent("parent-uid")
            assertEquals("parent-uid", relation.uid)
            assertEquals(RelationType.PARENT, relation.relationType)
        }

        @Test
        fun `child creates CHILD relation`() {
            val relation = ICalRelation.child("child-uid")
            assertEquals("child-uid", relation.uid)
            assertEquals(RelationType.CHILD, relation.relationType)
        }

        @Test
        fun `sibling creates SIBLING relation`() {
            val relation = ICalRelation.sibling("sibling-uid")
            assertEquals("sibling-uid", relation.uid)
            assertEquals(RelationType.SIBLING, relation.relationType)
        }

        @Test
        fun `next creates NEXT relation with optional gap`() {
            val relation = ICalRelation.next("next-uid", Duration.ofHours(1))
            assertEquals("next-uid", relation.uid)
            assertEquals(RelationType.NEXT, relation.relationType)
            assertEquals(Duration.ofHours(1), relation.gap)
        }

        @Test
        fun `fromParameters parses all values`() {
            val relation = ICalRelation.fromParameters(
                uid = "related-uid",
                reltype = "DEPENDS-ON",
                gap = "PT30M"
            )

            assertEquals("related-uid", relation.uid)
            assertEquals(RelationType.DEPENDS_ON, relation.relationType)
            assertEquals(Duration.ofMinutes(30), relation.gap)
        }
    }

    @Nested
    inner class ICalEventRelationsTests {

        @Test
        fun `ICalEvent can have links and relations`() {
            val event = createTestEvent(
                links = listOf(
                    ICalLink.alternate("https://example.com/event.html", "text/html"),
                    ICalLink.describedBy("https://example.com/spec.pdf")
                ),
                relations = listOf(
                    ICalRelation.parent("parent-event"),
                    ICalRelation.child("child-event")
                )
            )

            assertEquals(2, event.links.size)
            assertEquals(2, event.relations.size)
            assertEquals(LinkRelationType.ALTERNATE, event.links[0].relation)
            assertEquals(RelationType.PARENT, event.relations[0].relationType)
        }

        private fun createTestEvent(
            links: List<ICalLink> = emptyList(),
            relations: List<ICalRelation> = emptyList()
        ): ICalEvent {
            return ICalEvent(
                uid = "test-uid",
                importId = "test-uid",
                summary = "Test Event",
                description = null,
                location = null,
                dtStart = ICalDateTime.fromTimestamp(1000000L, null, false),
                dtEnd = null,
                duration = null,
                isAllDay = false,
                status = EventStatus.CONFIRMED,
                sequence = 0,
                rrule = null,
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
                links = links,
                relations = relations,
                rawProperties = emptyMap()
            )
        }
    }
}
