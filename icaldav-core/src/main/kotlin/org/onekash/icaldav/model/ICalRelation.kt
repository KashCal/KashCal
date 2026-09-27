package org.onekash.icaldav.model

/**
 * RFC 9253 relationships: the LINK property ([ICalLink]) and RELATED-TO with its new RELTYPE
 * values and GAP ([ICalRelation]).
 *
 * @see <a href="https://tools.ietf.org/html/rfc9253">RFC 9253 - iCalendar Relationships</a>
 */

/**
 * Holds one LINK property (RFC 9253 §8.2), a reference to external information about the
 * component.
 *
 * The parser and [toICalString] carry the relation in a REL parameter, where RFC 9253 §6.1
 * names it LINKREL and requires it on every LINK; RELATED is written with no REL at all. TITLE
 * and GAP are not RFC 9253 LINK parameters.
 *
 * Example, as written here:
 * ```
 * LINK;VALUE=URI;REL=alternate;FMTTYPE=text/html:https://example.com/event-details
 * LINK;VALUE=URI;REL=describedby:https://example.com/event-spec.pdf
 * ```
 */
data class ICalLink(
    /** Linked resource URI. */
    val uri: String,

    /** Relation, for example "alternate" or "describedby"; RELATED when absent. */
    val relation: LinkRelationType = LinkRelationType.RELATED,

    /** FMTTYPE parameter: the linked resource's media type. */
    val mediaType: String? = null,

    /** TITLE parameter, written quoted. */
    val title: String? = null,

    /** LABEL parameter (RFC 7986), the link's display title. */
    val label: String? = null,

    /** LANGUAGE parameter (BCP 47): the linked resource's language. */
    val language: String? = null,

    /** GAP parameter, which RFC 9253 §6.2 defines on RELATED-TO only. */
    val gap: java.time.Duration? = null
) {
    /**
     * Returns the LINK content line, unfolded. VALUE=URI is always written: RFC 9253 requires
     * VALUE, and ical4j needs it to parse the line.
     */
    fun toICalString(): String {
        val params = mutableListOf<String>()

        // Required by RFC 9253 and by ical4j's parser
        params.add("VALUE=URI")

        // RELATED is this model's default, so it is written without REL
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
        /** Creates an ALTERNATE link. */
        fun alternate(uri: String, mediaType: String? = null, title: String? = null): ICalLink {
            return ICalLink(
                uri = uri,
                relation = LinkRelationType.ALTERNATE,
                mediaType = mediaType,
                title = title
            )
        }

        /** Creates a DESCRIBEDBY link. */
        fun describedBy(uri: String, title: String? = null): ICalLink {
            return ICalLink(
                uri = uri,
                relation = LinkRelationType.DESCRIBEDBY,
                title = title
            )
        }

        /** Creates a RELATED link. */
        fun related(uri: String, title: String? = null): ICalLink {
            return ICalLink(
                uri = uri,
                relation = LinkRelationType.RELATED,
                title = title
            )
        }

        /**
         * Builds an [ICalLink] from raw parameter values. Quotes around [title] are dropped, and
         * a [gap] that `java.time.Duration.parse` rejects becomes null.
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
 * Link relations this library recognizes, from the IANA Link Relations registry:
 * https://www.iana.org/assignments/link-relations/link-relations.xhtml
 *
 * [fromString] maps null or blank to RELATED and anything unrecognized to CUSTOM, which drops
 * the original value.
 */
enum class LinkRelationType {
    /** A substitute for this context. */
    ALTERNATE,

    /** A resource that can cancel an invitation. */
    CANCEL,

    /** A resource with information about the link's context. */
    DESCRIBEDBY,

    /** A resource with copyright information. */
    COPYRIGHT,

    /** A hub for real-time updates. */
    HUB,

    /** An icon for the link's context. */
    ICON,

    /** The next resource in a sequence. */
    NEXT,

    /** The previous resource in a sequence. */
    PREV,

    /** A related resource. */
    RELATED,

    /** Where replies go. */
    REPLIES,

    /** The canonical URI for this resource. */
    SELF,

    /** Parsed from "described-by"; written as "describedby", like [DESCRIBEDBY]. */
    DESCRIBED_BY,

    /** Any unrecognized relation; written as "custom". */
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
 * Holds one RELATED-TO property with the RFC 9253 §9.1 extensions: new RELTYPE values and the
 * GAP parameter (§6.2).
 *
 * Example:
 * ```
 * RELATED-TO;RELTYPE=PARENT:parent-event-uid
 * RELATED-TO;RELTYPE=CHILD:child-event-uid
 * RELATED-TO;RELTYPE=SIBLING:related-event-uid
 * ```
 */
data class ICalRelation(
    /** The property value, usually the related component's UID. */
    val uid: String,

    /** RELTYPE parameter; PARENT when absent. */
    val relationType: RelationType = RelationType.PARENT,

    /**
     * GAP parameter: lag (positive) or lead (negative) time to the related component, written
     * in `java.time.Duration.toString` form.
     */
    val gap: java.time.Duration? = null
) {
    /** Returns the RELATED-TO content line, unfolded. */
    fun toICalString(): String {
        val params = mutableListOf<String>()

        // RFC 5545 §3.2.15: PARENT is the default, so it is written without RELTYPE
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

    /** Returns whether [relationType] is PARENT. */
    fun isParent(): Boolean = relationType == RelationType.PARENT

    /** Returns whether [relationType] is CHILD. */
    fun isChild(): Boolean = relationType == RelationType.CHILD

    /** Returns whether [relationType] is SIBLING. */
    fun isSibling(): Boolean = relationType == RelationType.SIBLING

    companion object {
        /** Creates a PARENT relation to [uid]. */
        fun parent(uid: String): ICalRelation {
            return ICalRelation(uid = uid, relationType = RelationType.PARENT)
        }

        /** Creates a CHILD relation to [uid]. */
        fun child(uid: String): ICalRelation {
            return ICalRelation(uid = uid, relationType = RelationType.CHILD)
        }

        /** Creates a SIBLING relation to [uid]. */
        fun sibling(uid: String): ICalRelation {
            return ICalRelation(uid = uid, relationType = RelationType.SIBLING)
        }

        /** Creates a NEXT relation to [uid], with an optional [gap]. */
        fun next(uid: String, gap: java.time.Duration? = null): ICalRelation {
            return ICalRelation(uid = uid, relationType = RelationType.NEXT, gap = gap)
        }

        /**
         * Builds an [ICalRelation] from raw RELTYPE and GAP values; a [gap] that
         * `java.time.Duration.parse` rejects becomes null.
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
 * RELTYPE values. RFC 5545 defines PARENT, CHILD and SIBLING; RFC 9253 §4 and §5 add
 * FINISHTOSTART, FINISHTOFINISH, STARTTOFINISH, STARTTOSTART, FIRST, NEXT, DEPENDS-ON, REFID
 * and CONCEPT. REQUIRES and REPLACES are in neither RFC. [fromString] maps null, blank or
 * unknown to PARENT, as RFC 5545 §3.2.15 has clients treat unrecognized values.
 */
enum class RelationType {
    /** This component is a subordinate of the referenced component. */
    PARENT,

    /** This component is a superior of the referenced component. */
    CHILD,

    /** This component is a peer of the referenced component. */
    SIBLING,

    // RFC 9253 additions

    /** Finish-to-start dependency. */
    FINISHTOSTART,

    /** Finish-to-finish dependency. */
    FINISHTOFINISH,

    /** Start-to-finish dependency. */
    STARTTOFINISH,

    /** Start-to-start dependency. */
    STARTTOSTART,

    /** The referenced component is the first in this component's series. */
    FIRST,

    /** The referenced component is the next in this component's series. */
    NEXT,

    /** This component depends on the referenced component. */
    DEPENDS_ON,

    /** Refers to components whose REFID property matches the value. */
    REFID,

    /** Refers to components whose CONCEPT property matches the value. */
    CONCEPT,

    /** Requires the referenced component. */
    REQUIRES,

    /** Replaces the referenced component. */
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
