package org.onekash.icaldav.model

/**
 * Holds one IMAGE property (RFC 7986 §5.10), an image for a calendar or component, for example
 * an event poster or a venue photo. [toICalString] always writes VALUE=URI; the parser takes the
 * value as [uri] whatever its VALUE type.
 *
 * Example:
 * ```
 * IMAGE;VALUE=URI;DISPLAY=BADGE;FMTTYPE=image/png:https://example.com/logo.png
 * IMAGE;VALUE=URI;DISPLAY=THUMBNAIL:https://example.com/event-thumb.jpg
 * ```
 *
 * @see <a href="https://tools.ietf.org/html/rfc7986#section-5.10">RFC 7986 Section 5.10</a>
 */
data class ICalImage(
    /** Image URI. */
    val uri: String,

    /**
     * DISPLAY parameter. Absent parses as GRAPHIC here, although RFC 7986 §6.1 makes BADGE the
     * default, and [toICalString] omits GRAPHIC.
     */
    val display: ImageDisplay = ImageDisplay.GRAPHIC,

    /** FMTTYPE parameter, for example "image/png". */
    val mediaType: String? = null,

    /**
     * ALTREP parameter: per RFC 7986 §5.10 a URI that a click on the image can launch, not
     * alt text. Written quoted, with any `"` replaced by `'`.
     */
    val altText: String? = null
) {
    /** Returns the IMAGE content line, unfolded. */
    fun toICalString(): String {
        val params = mutableListOf<String>()
        params.add("VALUE=URI")

        if (display != ImageDisplay.GRAPHIC) {
            params.add("DISPLAY=${display.name}")
        }
        mediaType?.let { params.add("FMTTYPE=$it") }
        altText?.let { params.add("ALTREP=\"${escapeParamValue(it)}\"") }

        return "IMAGE;${params.joinToString(";")}:$uri"
    }

    private fun escapeParamValue(value: String): String {
        return value.replace("\"", "'")
    }

    companion object {
        /** Builds an [ICalImage] from the raw DISPLAY, FMTTYPE and ALTREP parameter values. */
        fun fromParameters(
            uri: String,
            displayValue: String? = null,
            fmttype: String? = null,
            altrep: String? = null
        ): ICalImage {
            return ICalImage(
                uri = uri,
                display = ImageDisplay.fromString(displayValue),
                mediaType = fmttype,
                altText = altrep
            )
        }
    }
}

/** DISPLAY parameter values for IMAGE (RFC 7986 §6.1). */
enum class ImageDisplay {
    /** An image inline with the event's title. */
    BADGE,

    /** A full image replacement for the event itself; this library's default. */
    GRAPHIC,

    /** An image that enhances the event. */
    FULLSIZE,

    /** A smaller FULLSIZE variant for when space is constrained. */
    THUMBNAIL;

    companion object {
        /**
         * Matches [value] case-insensitively; null, blank, unknown and multi-value lists such as
         * "BADGE,THUMBNAIL" give GRAPHIC.
         */
        fun fromString(value: String?): ImageDisplay {
            if (value.isNullOrBlank()) return GRAPHIC
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: GRAPHIC
        }
    }
}
