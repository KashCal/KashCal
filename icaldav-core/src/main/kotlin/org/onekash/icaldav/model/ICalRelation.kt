package org.onekash.icaldav.model

/**
 * RFC 9253 iCalendar Relationships support.
 *
 * Provides LINK property and enhanced RELATED-TO for expressing
 * relationships between calendar components and external resources.
 *
 * @see <a href="https://tools.ietf.org/html/rfc9253">RFC 9253 - iCalendar Relationships</a>
 */

/**
 * LINK property per RFC 9253 Section 6.1.
 * Links calendar components to external resources.
 *
 * Example iCalendar format:
 * ```
 * LINK;REL=alternate;FMTTYPE=text/html:https://example.com/event-details
 * LINK;REL=describedby:https://example.com/event-spec.pdf
 * ```
 *
 * @param uri The URI of the linked resource
 * @param relation The link relation type (IANA-registered or URI)
 * @param mediaType MIME type of the linked resource (FMTTYPE parameter)
 * @param title Human-readable title for the link
 * @param label Display label for the link
 * @param language Language of the linked resource
 */
data class ICalLink(
    /** URI of the linked resource */
    val uri: String,

    /** Link relation type (e.g., "alternate", "describedby", "related") */
    val relation: LinkRelationType = LinkRelationType.RELATED,

    /** MIME type of the linked resource (FMTTYPE parameter) */
    val mediaType: String? = null,

    /** Human-readable title */
    val title: String? = null,

    /** Display label */
    val label: String? = null,

    /** Language of the linked resource (BCP 47) */
    val language: String? = null,

    /** Gap between events when REL=next (RFC 9253) */
    val gap: java.time.Duration? = null
) {
    /**
     * Generate iCalendar LINK property string.
     *
     * Always includes VALUE=URI as required by ical4j for proper parsing.
     */
    fun toICalString(): String {
        val params = mutableListOf<String>()

        // VALUE=URI is required for proper parsing by ical4j
        params.add("VALUE=URI")

        // Add REL parameter if not RELATED (default)
        if (relation != LinkRelationType.RELATED) {
            params.add("REL=${relation.toICalString()}")
        }

        mediaType?.let { params.add("FMTTYPE=$it") }
        title?.let { params.add("TITLE=\"$it\"") }
        label?.let { params.add("LABEL=$it") }
        language?.let { params.add("LANGUAGE=$it") }
        gap?.let {
            val isoGap = it.toString() // ISO-8601 duration format
            params.add("GAP=$isoGap")
        }

        val paramStr = ";" + params.joinToString(";")

        return "LINK$paramStr:$uri"
    }

    companion object {
        /**
         * Create a link to an alternate representation.
         */
        fun alternate(uri: String, mediaType: String? = null, title: String? = null): ICalLink {
            return ICalLink(
                uri = uri,
                relation = LinkRelationType.ALTERNATE,
                mediaType = mediaType,
                title = title
            )
        }

        /**
         * Create a link to a description/documentation.
         */
        fun describedBy(uri: String, title: String? = null): ICalLink {
            return ICalLink(
                uri = uri,
                relation = LinkRelationType.DESCRIBEDBY,
                title = title
            )
        }

        /**
         * Create a link to a related resource.
         */
        fun related(uri: String, title: String? = null): ICalLink {
            return ICalLink(
                uri = uri,
                relation = LinkRelationType.RELATED,
                title = title
            )
        }

        /**
         * Parse from iCalendar parameters.
         */
        fun fromParameters(
            uri: String,
            rel: String? = null,
            fmttype: String? = null,
            title: String? = null,
            label: String? = null,
            language: String? = null,
            gap: String? = null
        ): ICalLink {
            return ICalLink(
                uri = uri,
                relation = LinkRelationType.fromString(rel),
                mediaType = fmttype,
                title = title?.trim('"'),
                label = label,
                language = language,
                gap = gap?.let { parseIsoDuration(it) }
            )
        }

        private fun parseIsoDuration(value: String): java.time.Duration? {
            return try {
                java.time.Duration.parse(value)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Link relation types per RFC 9253 and IANA registry.
 *
 * Common link relations from the IANA Link Relations registry:
 * https://www.iana.org/assignments/link-relations/link-relations.xhtml
 */
enum class LinkRelationType {
    /** Refers to a substitute for this context */
    ALTERNATE,

    /** Identifies a related resource that can be used to cancel an invitation */
    CANCEL,

    /** Refers to a resource providing information about the link's context */
    DESCRIBEDBY,

    /** Refers to a resource containing copyright info */
    COPYRIGHT,

    /** Refers to a hub for real-time updates */
    HUB,

    /** Refers to an icon representing the link's context */
    ICON,

    /** Refers to the next resource in a sequence */
    NEXT,

    /** Refers to the previous resource in a sequence */
    PREV,

    /** Identifies a related resource */
    RELATED,

    /** Identifies a resource where replies should be sent */
    REPLIES,

    /** Identifies the canonical URI for this resource */
    SELF,

    /** Identifies a resource describing this resource */
    DESCRIBED_BY,

    /** Custom/unknown relation type */
    CUSTOM;

    fun toICalString(): String {
        return when (this) {
            DESCRIBED_BY -> "describedby"
            else -> name.lowercase()
        }
    }

    companion object {
        fun fromString(value: String?): LinkRelationType {
            if (value.isNullOrBlank()) return RELATED
            val normalized = value.uppercase().replace("-", "_")
            return entries.find { it.name == normalized }
                ?: entries.find { it.name.equals(value, ignoreCase = true) }
                ?: CUSTOM
        }
    }
}

/**
 * Enhanced RELATED-TO property per RFC 9253 Section 6.2.
 * Expresses relationships between calendar components.
 *
 * Example iCalendar format:
 * ```
 * RELATED-TO;RELTYPE=PARENT:parent-event-uid
 * RELATED-TO;RELTYPE=CHILD:child-event-uid
 * RELATED-TO;RELTYPE=SIBLING:related-event-uid
 * ```
 *
 * @param uid UID of the related component
 * @param relationType Type of relationship
 * @param gap Time gap to the related component (RFC 9253)
 */
data class ICalRelation(
    /** UID of the related calendar component */
    val uid: String,

    /** Type of relationship */
    val relationType: RelationType = RelationType.PARENT,

    /** Time gap to the related component */
    val gap: java.time.Duration? = null
) {
    /**
     * Generate iCalendar RELATED-TO property string.
     */
    fun toICalString(): String {
        val params = mutableListOf<String>()

        // Add RELTYPE if not PARENT (default per RFC 5545)
        if (relationType != RelationType.PARENT) {
            params.add("RELTYPE=${relationType.toICalString()}")
        }

        gap?.let {
            params.add("GAP=${it}")
        }

        val paramStr = if (params.isNotEmpty()) {
            ";" + params.joinToString(";")
        } else ""

        return "RELATED-TO$paramStr:$uid"
    }

    /**
     * Check if this is a parent relationship.
     */
    fun isParent(): Boolean = relationType == RelationType.PARENT

    /**
     * Check if this is a child relationship.
     */
    fun isChild(): Boolean = relationType == RelationType.CHILD

    /**
     * Check if this is a sibling relationship.
     */
    fun isSibling(): Boolean = relationType == RelationType.SIBLING

    companion object {
        /**
         * Create a parent relationship.
         */
        fun parent(uid: String): ICalRelation {
            return ICalRelation(uid = uid, relationType = RelationType.PARENT)
        }

        /**
         * Create a child relationship.
         */
        fun child(uid: String): ICalRelation {
            return ICalRelation(uid = uid, relationType = RelationType.CHILD)
        }

        /**
         * Create a sibling relationship.
         */
        fun sibling(uid: String): ICalRelation {
            return ICalRelation(uid = uid, relationType = RelationType.SIBLING)
        }

        /**
         * Create a "next" relationship with optional gap.
         */
        fun next(uid: String, gap: java.time.Duration? = null): ICalRelation {
            return ICalRelation(uid = uid, relationType = RelationType.NEXT, gap = gap)
        }

        /**
         * Parse from iCalendar parameters.
         */
        fun fromParameters(
            uid: String,
            reltype: String? = null,
            gap: String? = null
        ): ICalRelation {
            return ICalRelation(
                uid = uid,
                relationType = RelationType.fromString(reltype),
                gap = gap?.let { parseIsoDuration(it) }
            )
        }

        private fun parseIsoDuration(value: String): java.time.Duration? {
            return try {
                java.time.Duration.parse(value)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Relation type per RFC 5545 and RFC 9253.
 *
 * RFC 5545 defines: PARENT, CHILD, SIBLING
 * RFC 9253 adds: FINISHTOSTART, FINISHTOFINISH, STARTTOFINISH, STARTTOSTART,
 *               FIRST, NEXT, DEPENDS-ON, REFID, CONCEPT, REQUIRES, REPLACES
 */
enum class RelationType {
    /** This component is a sub-component of the referenced component */
    PARENT,

    /** The referenced component is a sub-component of this component */
    CHILD,

    /** This component shares common parent with referenced component */
    SIBLING,

    // RFC 9253 additions

    /** Finish-to-Start dependency */
    FINISHTOSTART,

    /** Finish-to-Finish dependency */
    FINISHTOFINISH,

    /** Start-to-Finish dependency */
    STARTTOFINISH,

    /** Start-to-Start dependency */
    STARTTOSTART,

    /** First in a series */
    FIRST,

    /** Next in a series */
    NEXT,

    /** Depends on the referenced component */
    DEPENDS_ON,

    /** References another component by ID */
    REFID,

    /** Conceptually related */
    CONCEPT,

    /** Requires the referenced component */
    REQUIRES,

    /** Replaces the referenced component */
    REPLACES;

    fun toICalString(): String = name.replace("_", "-")

    companion object {
        fun fromString(value: String?): RelationType {
            if (value.isNullOrBlank()) return PARENT
            val normalized = value.uppercase().replace("-", "_")
            return entries.find { it.name == normalized } ?: PARENT
        }
    }
}
