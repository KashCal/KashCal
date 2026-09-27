package org.onekash.icaldav.model

/**
 * Holds one CONFERENCE property (RFC 7986 §5.11): how to join a conferencing system, such as a
 * video call URL or a phone dial-in. An event can carry more than one.
 *
 * Example:
 * ```
 * CONFERENCE;VALUE=URI;FEATURE=VIDEO,AUDIO;LABEL=Join meeting:https://video.example.com/j/123456789
 * CONFERENCE;VALUE=URI;FEATURE=PHONE;LABEL=Dial-in:tel:+1-555-123-4567
 * ```
 *
 * @see <a href="https://tools.ietf.org/html/rfc7986#section-5.11">RFC 7986 Section 5.11</a>
 */
data class ICalConference(
    /** Conference URI, for example `https://video.example.com/j/123` or `tel:+1-555-123-4567`. */
    val uri: String,

    /**
     * FEATURE values. The parser gives an empty set when FEATURE is absent, not this default.
     */
    val features: Set<ConferenceFeature> = setOf(ConferenceFeature.VIDEO),

    /** LABEL: human-readable text that tells entries apart, for example "Moderator dial-in". */
    val label: String? = null,

    /** LANGUAGE tag of [label], for example "en" or "de". */
    val language: String? = null
) {
    /**
     * Returns the unfolded CONFERENCE content line; FEATURE is left out when [features] is empty.
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
        // Quotes a value containing ':', ';' or ','; other characters pass through as is.
        return if (value.contains(":") || value.contains(";") || value.contains(",")) {
            "\"$value\""
        } else {
            value
        }
    }

    fun hasVideo(): Boolean = features.contains(ConferenceFeature.VIDEO)

    /** Returns true for an AUDIO or PHONE entry. */
    fun hasAudio(): Boolean = features.contains(ConferenceFeature.AUDIO) ||
            features.contains(ConferenceFeature.PHONE)

    /** Returns true for a `tel:` URI or a PHONE entry. */
    fun isPhoneDialIn(): Boolean = uri.startsWith("tel:") ||
            features.contains(ConferenceFeature.PHONE)

    companion object {
        /**
         * Builds a conference from raw parameter values; [featureValue] is parsed with
         * [ConferenceFeature.parseFeatures].
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

        /** Creates a VIDEO and AUDIO entry for [uri]. */
        fun video(uri: String, label: String? = null): ICalConference {
            return ICalConference(
                uri = uri,
                features = setOf(ConferenceFeature.VIDEO, ConferenceFeature.AUDIO),
                label = label
            )
        }

        /**
         * Creates a PHONE and AUDIO entry, adding the `tel:` prefix to [phoneNumber] when it is
         * missing.
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
 * FEATURE values (RFC 7986 §6.3): what a conference entry provides. Experimental and other IANA
 * values aren't modelled; [fromString] returns null for them.
 */
enum class ConferenceFeature {
    /** Audio capability. */
    AUDIO,

    /** Chat or instant messaging. */
    CHAT,

    /** Blog or Atom feed. */
    FEED,

    /**
     * The entry is for the conference owner, for example a moderator code that differs from the
     * attendees' code.
     */
    MODERATOR,

    /** Phone conference. */
    PHONE,

    /** Screen sharing. */
    SCREEN,

    /** Video capability. */
    VIDEO;

    companion object {
        /** Maps [value] ignoring case and surrounding space; null for a blank or unknown value. */
        fun fromString(value: String?): ConferenceFeature? {
            if (value.isNullOrBlank()) return null
            return entries.find { it.name.equals(value.trim(), ignoreCase = true) }
        }

        /** Parses a comma-separated list such as "VIDEO,AUDIO,SCREEN", dropping unknown values. */
        fun parseFeatures(value: String?): Set<ConferenceFeature> {
            if (value.isNullOrBlank()) return emptySet()
            return value.split(",")
                .mapNotNull { fromString(it.trim()) }
                .toSet()
        }
    }
}
