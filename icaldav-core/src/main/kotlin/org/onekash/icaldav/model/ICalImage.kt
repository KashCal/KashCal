package org.onekash.icaldav.model

/**
 * IMAGE property per RFC 7986 Section 5.10.
 * Associates an image (photo, icon, thumbnail) with a calendar component.
 *
 * The IMAGE property can be used to associate an image with a calendar or
 * calendar component. For events, this could be an event poster, speaker photo,
 * or venue image.
 *
 * Example iCalendar format:
 * ```
 * IMAGE;VALUE=URI;DISPLAY=BADGE;FMTTYPE=image/png:https://example.com/logo.png
 * IMAGE;VALUE=URI;DISPLAY=THUMBNAIL:https://example.com/event-thumb.jpg
 * ```
 *
 * @see <a href="https://tools.ietf.org/html/rfc7986#section-5.10">RFC 7986 Section 5.10</a>
 */
data class ICalImage(
    /** URI to the image resource */
    val uri: String,

    /** Display type hint for how the image should be rendered */
    val display: ImageDisplay = ImageDisplay.GRAPHIC,

    /** MIME type of the image (e.g., "image/png", "image/jpeg") */
    val mediaType: String? = null,

    /** Alternative text description for accessibility */
    val altText: String? = null
) {
    /**
     * Convert to iCalendar property string format.
     *
     * @return IMAGE property line (without line folding)
     */
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
        /**
         * Parse IMAGE property parameters from ical4j.
         *
         * @param uri The image URI value
         * @param displayValue The DISPLAY parameter value
         * @param fmttype The FMTTYPE parameter value
         * @param altrep The ALTREP parameter value
         * @return Parsed ICalImage
         */
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

/**
 * Display type hints for IMAGE property per RFC 7986.
 *
 * These hints suggest how calendar applications should render the image:
 * - BADGE: Small icon displayed inline with text
 * - GRAPHIC: Standard image display (default)
 * - FULLSIZE: Large, full-resolution image
 * - THUMBNAIL: Small preview image
 */
enum class ImageDisplay {
    /** Small icon displayed inline with text (like an avatar) */
    BADGE,

    /** Standard image display (default) */
    GRAPHIC,

    /** Large, full-resolution image for detail views */
    FULLSIZE,

    /** Small preview image for lists or grids */
    THUMBNAIL;

    companion object {
        /**
         * Parse display type from string, case-insensitive.
         *
         * @param value The string value to parse
         * @return Matching ImageDisplay or GRAPHIC as default
         */
        fun fromString(value: String?): ImageDisplay {
            if (value.isNullOrBlank()) return GRAPHIC
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: GRAPHIC
        }
    }
}
