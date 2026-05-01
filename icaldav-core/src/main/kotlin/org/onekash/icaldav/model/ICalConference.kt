package org.onekash.icaldav.model

/**
 * CONFERENCE property per RFC 7986 Section 5.11.
 * Provides information for accessing a conference (video call, audio call, etc.).
 *
 * The CONFERENCE property allows calendar applications to store video conferencing
 * URLs and details directly in events, supporting services like Zoom, Google Meet,
 * Microsoft Teams, etc.
 *
 * Example iCalendar format:
 * ```
 * CONFERENCE;VALUE=URI;FEATURE=VIDEO,AUDIO;LABEL=Join Zoom Meeting:https://zoom.us/j/123456789
 * CONFERENCE;VALUE=URI;FEATURE=PHONE;LABEL=Dial-in:tel:+1-555-123-4567
 * ```
 *
 * @see <a href="https://tools.ietf.org/html/rfc7986#section-5.11">RFC 7986 Section 5.11</a>
 */
data class ICalConference(
    /** Conference URI (e.g., https://zoom.us/j/123456, tel:+1-555-123-4567) */
    val uri: String,

    /** Feature types supported by this conference entry */
    val features: Set<ConferenceFeature> = setOf(ConferenceFeature.VIDEO),

    /** Human-readable label for display (e.g., "Join Zoom Meeting") */
    val label: String? = null,

    /** Language tag for the label (e.g., "en", "de") */
    val language: String? = null
) {
    /**
     * Convert to iCalendar property string format.
     *
     * @return CONFERENCE property line (without line folding)
     */
    fun toICalString(): String {
        val params = mutableListOf<String>()
        params.add("VALUE=URI")

        if (features.isNotEmpty()) {
            params.add("FEATURE=${features.joinToString(",") { it.name }}")
        }
        label?.let { params.add("LABEL=${escapeParamValue(it)}") }
        language?.let { params.add("LANGUAGE=$it") }

        return "CONFERENCE;${params.joinToString(";")}:$uri"
    }

    private fun escapeParamValue(value: String): String {
        // Quote if contains special characters
        return if (value.contains(":") || value.contains(";") || value.contains(",")) {
            "\"$value\""
        } else {
            value
        }
    }

    /**
     * Check if this conference supports video calls.
     */
    fun hasVideo(): Boolean = features.contains(ConferenceFeature.VIDEO)

    /**
     * Check if this conference supports audio calls.
     */
    fun hasAudio(): Boolean = features.contains(ConferenceFeature.AUDIO) ||
            features.contains(ConferenceFeature.PHONE)

    /**
     * Check if this is a phone dial-in entry.
     */
    fun isPhoneDialIn(): Boolean = uri.startsWith("tel:") ||
            features.contains(ConferenceFeature.PHONE)

    companion object {
        /**
         * Parse CONFERENCE property parameters from ical4j.
         *
         * @param uri The conference URI value
         * @param featureValue The FEATURE parameter value (comma-separated)
         * @param labelValue The LABEL parameter value
         * @param languageValue The LANGUAGE parameter value
         * @return Parsed ICalConference
         */
        fun fromParameters(
            uri: String,
            featureValue: String? = null,
            labelValue: String? = null,
            languageValue: String? = null
        ): ICalConference {
            return ICalConference(
                uri = uri,
                features = ConferenceFeature.parseFeatures(featureValue),
                label = labelValue,
                language = languageValue
            )
        }

        /**
         * Create a simple video conference entry.
         *
         * @param uri The video conference URL
         * @param label Optional display label
         * @return ICalConference configured for video
         */
        fun video(uri: String, label: String? = null): ICalConference {
            return ICalConference(
                uri = uri,
                features = setOf(ConferenceFeature.VIDEO, ConferenceFeature.AUDIO),
                label = label
            )
        }

        /**
         * Create a phone dial-in entry.
         *
         * @param phoneNumber The phone number (with or without tel: prefix)
         * @param label Optional display label
         * @return ICalConference configured for phone
         */
        fun phone(phoneNumber: String, label: String? = null): ICalConference {
            val uri = if (phoneNumber.startsWith("tel:")) phoneNumber else "tel:$phoneNumber"
            return ICalConference(
                uri = uri,
                features = setOf(ConferenceFeature.PHONE, ConferenceFeature.AUDIO),
                label = label
            )
        }
    }
}

/**
 * Conference feature types per RFC 7986.
 *
 * These indicate what capabilities a conference entry provides:
 * - AUDIO: Audio-only conference
 * - CHAT: Text chat capability
 * - FEED: Blog or RSS feed
 * - MODERATOR: Moderator access URL
 * - PHONE: Phone dial-in
 * - SCREEN: Screen sharing capability
 * - VIDEO: Video conference
 */
enum class ConferenceFeature {
    /** Audio-only conference capability */
    AUDIO,

    /** Text chat capability */
    CHAT,

    /** Blog or RSS feed */
    FEED,

    /** Moderator access (different from regular attendee) */
    MODERATOR,

    /** Phone dial-in capability */
    PHONE,

    /** Screen sharing capability */
    SCREEN,

    /** Video conference capability */
    VIDEO;

    companion object {
        /**
         * Parse single feature from string, case-insensitive.
         *
         * @param value The string value to parse
         * @return Matching ConferenceFeature or null if not found
         */
        fun fromString(value: String?): ConferenceFeature? {
            if (value.isNullOrBlank()) return null
            return entries.find { it.name.equals(value.trim(), ignoreCase = true) }
        }

        /**
         * Parse comma-separated feature string into a Set.
         *
         * @param value Comma-separated features (e.g., "VIDEO,AUDIO,SCREEN")
         * @return Set of parsed ConferenceFeature values
         */
        fun parseFeatures(value: String?): Set<ConferenceFeature> {
            if (value.isNullOrBlank()) return emptySet()
            return value.split(",")
                .mapNotNull { fromString(it.trim()) }
                .toSet()
        }
    }
}
