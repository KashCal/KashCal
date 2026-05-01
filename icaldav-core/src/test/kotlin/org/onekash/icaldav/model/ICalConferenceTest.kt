package org.onekash.icaldav.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Tests for ICalConference and ConferenceFeature (RFC 7986).
 */
class ICalConferenceTest {

    @Nested
    inner class ConferenceFeatureTests {

        @Test
        fun `fromString parses all feature types`() {
            assertEquals(ConferenceFeature.AUDIO, ConferenceFeature.fromString("AUDIO"))
            assertEquals(ConferenceFeature.CHAT, ConferenceFeature.fromString("CHAT"))
            assertEquals(ConferenceFeature.FEED, ConferenceFeature.fromString("FEED"))
            assertEquals(ConferenceFeature.MODERATOR, ConferenceFeature.fromString("MODERATOR"))
            assertEquals(ConferenceFeature.PHONE, ConferenceFeature.fromString("PHONE"))
            assertEquals(ConferenceFeature.SCREEN, ConferenceFeature.fromString("SCREEN"))
            assertEquals(ConferenceFeature.VIDEO, ConferenceFeature.fromString("VIDEO"))
        }

        @Test
        fun `fromString is case insensitive`() {
            assertEquals(ConferenceFeature.VIDEO, ConferenceFeature.fromString("video"))
            assertEquals(ConferenceFeature.VIDEO, ConferenceFeature.fromString("Video"))
            assertEquals(ConferenceFeature.AUDIO, ConferenceFeature.fromString("audio"))
        }

        @Test
        fun `fromString returns null for unknown value`() {
            assertNull(ConferenceFeature.fromString("UNKNOWN"))
            assertNull(ConferenceFeature.fromString("invalid"))
        }

        @Test
        fun `fromString returns null for null or blank`() {
            assertNull(ConferenceFeature.fromString(null))
            assertNull(ConferenceFeature.fromString(""))
            assertNull(ConferenceFeature.fromString("  "))
        }

        @Test
        fun `parseFeatures parses single feature`() {
            val features = ConferenceFeature.parseFeatures("VIDEO")
            assertEquals(setOf(ConferenceFeature.VIDEO), features)
        }

        @Test
        fun `parseFeatures parses multiple features`() {
            val features = ConferenceFeature.parseFeatures("VIDEO,AUDIO,SCREEN")
            assertEquals(
                setOf(ConferenceFeature.VIDEO, ConferenceFeature.AUDIO, ConferenceFeature.SCREEN),
                features
            )
        }

        @Test
        fun `parseFeatures handles spaces`() {
            val features = ConferenceFeature.parseFeatures("VIDEO, AUDIO, SCREEN")
            assertEquals(
                setOf(ConferenceFeature.VIDEO, ConferenceFeature.AUDIO, ConferenceFeature.SCREEN),
                features
            )
        }

        @Test
        fun `parseFeatures ignores unknown features`() {
            val features = ConferenceFeature.parseFeatures("VIDEO,UNKNOWN,AUDIO")
            assertEquals(setOf(ConferenceFeature.VIDEO, ConferenceFeature.AUDIO), features)
        }

        @Test
        fun `parseFeatures returns empty set for null or blank`() {
            assertEquals(emptySet(), ConferenceFeature.parseFeatures(null))
            assertEquals(emptySet(), ConferenceFeature.parseFeatures(""))
            assertEquals(emptySet(), ConferenceFeature.parseFeatures("  "))
        }
    }

    @Nested
    inner class ICalConferenceConstructorTests {

        @Test
        fun `create conference with uri only`() {
            val conf = ICalConference(uri = "https://zoom.us/j/123456789")

            assertEquals("https://zoom.us/j/123456789", conf.uri)
            assertEquals(setOf(ConferenceFeature.VIDEO), conf.features)
            assertNull(conf.label)
            assertNull(conf.language)
        }

        @Test
        fun `create conference with all parameters`() {
            val conf = ICalConference(
                uri = "https://zoom.us/j/123456789",
                features = setOf(ConferenceFeature.VIDEO, ConferenceFeature.AUDIO),
                label = "Join Zoom Meeting",
                language = "en"
            )

            assertEquals("https://zoom.us/j/123456789", conf.uri)
            assertEquals(setOf(ConferenceFeature.VIDEO, ConferenceFeature.AUDIO), conf.features)
            assertEquals("Join Zoom Meeting", conf.label)
            assertEquals("en", conf.language)
        }
    }

    @Nested
    inner class ConferenceHelperMethods {

        @Test
        fun `hasVideo returns true for VIDEO feature`() {
            val conf = ICalConference(
                uri = "https://zoom.us/j/123",
                features = setOf(ConferenceFeature.VIDEO)
            )
            assertTrue(conf.hasVideo())
        }

        @Test
        fun `hasVideo returns false without VIDEO feature`() {
            val conf = ICalConference(
                uri = "tel:+1-555-123-4567",
                features = setOf(ConferenceFeature.PHONE)
            )
            assertFalse(conf.hasVideo())
        }

        @Test
        fun `hasAudio returns true for AUDIO feature`() {
            val conf = ICalConference(
                uri = "https://meet.example.com",
                features = setOf(ConferenceFeature.AUDIO)
            )
            assertTrue(conf.hasAudio())
        }

        @Test
        fun `hasAudio returns true for PHONE feature`() {
            val conf = ICalConference(
                uri = "tel:+1-555-123-4567",
                features = setOf(ConferenceFeature.PHONE)
            )
            assertTrue(conf.hasAudio())
        }

        @Test
        fun `isPhoneDialIn detects tel URI`() {
            val conf = ICalConference(
                uri = "tel:+1-555-123-4567",
                features = emptySet()
            )
            assertTrue(conf.isPhoneDialIn())
        }

        @Test
        fun `isPhoneDialIn detects PHONE feature`() {
            val conf = ICalConference(
                uri = "https://example.com/dial-in",
                features = setOf(ConferenceFeature.PHONE)
            )
            assertTrue(conf.isPhoneDialIn())
        }
    }

    @Nested
    inner class ToICalStringTests {

        @Test
        fun `toICalString with minimal parameters`() {
            val conf = ICalConference(
                uri = "https://zoom.us/j/123",
                features = emptySet()
            )
            val ical = conf.toICalString()

            assertEquals("CONFERENCE;VALUE=URI:https://zoom.us/j/123", ical)
        }

        @Test
        fun `toICalString includes features`() {
            val conf = ICalConference(
                uri = "https://zoom.us/j/123",
                features = setOf(ConferenceFeature.VIDEO, ConferenceFeature.AUDIO)
            )
            val ical = conf.toICalString()

            assert(ical.contains("VALUE=URI"))
            assert(ical.contains("FEATURE="))
            assert(ical.contains("VIDEO") || ical.contains("AUDIO"))
            assert(ical.endsWith(":https://zoom.us/j/123"))
        }

        @Test
        fun `toICalString includes label`() {
            val conf = ICalConference(
                uri = "https://zoom.us/j/123",
                features = setOf(ConferenceFeature.VIDEO),
                label = "Join Meeting"
            )
            val ical = conf.toICalString()

            assert(ical.contains("LABEL=Join Meeting"))
        }

        @Test
        fun `toICalString quotes label with special characters`() {
            val conf = ICalConference(
                uri = "https://zoom.us/j/123",
                features = setOf(ConferenceFeature.VIDEO),
                label = "Join: Meeting"
            )
            val ical = conf.toICalString()

            assert(ical.contains("LABEL=\"Join: Meeting\""))
        }
    }

    @Nested
    inner class FactoryMethodTests {

        @Test
        fun `video factory creates VIDEO and AUDIO conference`() {
            val conf = ICalConference.video(
                uri = "https://zoom.us/j/123",
                label = "Join Zoom"
            )

            assertEquals("https://zoom.us/j/123", conf.uri)
            assertTrue(conf.features.contains(ConferenceFeature.VIDEO))
            assertTrue(conf.features.contains(ConferenceFeature.AUDIO))
            assertEquals("Join Zoom", conf.label)
        }

        @Test
        fun `phone factory creates PHONE and AUDIO conference`() {
            val conf = ICalConference.phone(
                phoneNumber = "+1-555-123-4567",
                label = "Dial-in"
            )

            assertEquals("tel:+1-555-123-4567", conf.uri)
            assertTrue(conf.features.contains(ConferenceFeature.PHONE))
            assertTrue(conf.features.contains(ConferenceFeature.AUDIO))
            assertEquals("Dial-in", conf.label)
        }

        @Test
        fun `phone factory handles tel prefix`() {
            val conf = ICalConference.phone(phoneNumber = "tel:+1-555-123-4567")
            assertEquals("tel:+1-555-123-4567", conf.uri)
        }
    }

    @Nested
    inner class FromParametersTests {

        @Test
        fun `fromParameters with minimal values`() {
            val conf = ICalConference.fromParameters(uri = "https://zoom.us/j/123")

            assertEquals("https://zoom.us/j/123", conf.uri)
            assertEquals(emptySet(), conf.features)
            assertNull(conf.label)
            assertNull(conf.language)
        }

        @Test
        fun `fromParameters parses features`() {
            val conf = ICalConference.fromParameters(
                uri = "https://zoom.us/j/123",
                featureValue = "VIDEO,AUDIO,SCREEN"
            )

            assertEquals(
                setOf(ConferenceFeature.VIDEO, ConferenceFeature.AUDIO, ConferenceFeature.SCREEN),
                conf.features
            )
        }

        @Test
        fun `fromParameters with all values`() {
            val conf = ICalConference.fromParameters(
                uri = "https://zoom.us/j/123",
                featureValue = "VIDEO,AUDIO",
                labelValue = "Join Meeting",
                languageValue = "en"
            )

            assertEquals("https://zoom.us/j/123", conf.uri)
            assertEquals(setOf(ConferenceFeature.VIDEO, ConferenceFeature.AUDIO), conf.features)
            assertEquals("Join Meeting", conf.label)
            assertEquals("en", conf.language)
        }
    }
}
