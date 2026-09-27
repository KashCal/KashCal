package org.onekash.vcard.model

import java.time.LocalDate

/**
 * One vCard as a framework-free value, the handoff between this pure-JVM format module and the
 * app layers that map it to and from the Android Contacts Provider.
 *
 * It carries no ez-vcard, Android or networking types, so callers never need the vCard library
 * on their classpath. vCard 3.0 (RFC 2426) and 4.0 (RFC 6350) share this one shape.
 */
data class Contact(
    /**
     * The `VERSION:` value of the parsed body, never a version the caller requested. ez-vcard
     * assumes "2.1" when the body has none.
     */
    val version: String,

    /** `UID` property, or empty when the body carries none (RFC 6350 §6.7.6, `*1`). */
    val uid: String,

    /**
     * `KIND` value, lower-cased (RFC 6350 §6.1.4: "individual", "group", "org", "location"), or
     * null when the body declares none. Taken from the 4.0 `KIND` property, else the 3.0 Apple
     * `X-ADDRESSBOOKSERVER-KIND`, so a caller can drop a "group" distribution-list vCard, which
     * would otherwise show as an empty contact, without re-parsing the body. The parser only
     * records the value; the drop policy lives with the caller.
     */
    val kind: String? = null,

    /** Structured `N` name. Always present (empty components when the body omits it). */
    val structuredName: StructuredName,

    /**
     * `FN` formatted name. When the body's `FN` is missing or blank, the parser derives it from
     * [structuredName], so it is blank only when the body has neither.
     */
    val displayName: String,

    val nickname: String? = null,
    val emails: List<Email> = emptyList(),
    val phones: List<Phone> = emptyList(),
    val addresses: List<PostalAddress> = emptyList(),

    /** `ORG` components (company, then organizational units). */
    val organization: List<String> = emptyList(),
    val title: String? = null,

    /** `ROLE` job function/description (RFC 6350 §6.6.2); distinct from [title]. */
    val role: String? = null,

    val urls: List<WebAddress> = emptyList(),
    val notes: List<String> = emptyList(),
    val imHandles: List<ImHandle> = emptyList(),
    val relations: List<Relation> = emptyList(),
    val categories: List<String> = emptyList(),

    val photo: Photo? = null,

    val birthday: ContactDate? = null,
    val anniversary: ContactDate? = null,

    /**
     * The vCard text this contact was parsed from: the body verbatim for a single-card body, a
     * re-serialization of each card for a multi-card one. Blank on a contact rebuilt from device
     * rows unless the caller attaches a body. [org.onekash.vcard.VCardWriter] patches this body
     * when it holds one parseable card.
     */
    val rawVCard: String,
)

/**
 * Structured `N` components (RFC 6350 §6.2.2).
 *
 * Each `N` component can hold several comma-separated values. The extra values of the
 * additional-name, prefix and suffix components (a second middle name, "Dr. Prof.") are
 * space-joined into the single [middle], [prefix] and [suffix] strings the Android provider
 * stores, not dropped.
 *
 * The `X-PHONETIC-*` reading aids (Apple/Android convention) fill the phonetic components so CJK
 * name sorting and search work on device.
 */
data class StructuredName(
    val family: String? = null,
    val given: String? = null,
    val middle: String? = null,
    val prefix: String? = null,
    val suffix: String? = null,
    val phoneticGiven: String? = null,
    val phoneticMiddle: String? = null,
    val phoneticFamily: String? = null,
) {
    /** Space-joins the non-blank prefix, given, middle, family and suffix, in that order. */
    fun toDisplayName(): String =
        listOfNotNull(prefix, given, middle, family, suffix)
            .filter { it.isNotBlank() }
            .joinToString(" ")
}

/** An `EMAIL` value with its types, a normalized preferred flag, and any custom label. */
data class Email(
    val address: String,
    /** Lower-cased `TYPE` tokens (e.g. "home", "work"), excluding the preference marker. */
    val types: List<String> = emptyList(),
    /** True for a 3.0 `TYPE=PREF` and for any 4.0 `PREF` parameter alike. */
    val preferred: Boolean = false,
    /** Custom label from a grouped `itemN.X-ABLabel` (e.g. "School"), else null. */
    val label: String? = null,
)

/** A `TEL` value, with the `tel:` scheme stripped from 4.0 URI form. */
data class Phone(
    val number: String,
    val types: List<String> = emptyList(),
    val preferred: Boolean = false,
    /** Custom label from a grouped `itemN.X-ABLabel`, else null. */
    val label: String? = null,
)

/** A `URL` value with its optional custom label from a grouped `itemN.X-ABLabel`. */
data class WebAddress(
    val url: String,
    val label: String? = null,
)

/** 7-component `ADR` (RFC 6350 §6.3.1), de-escaped. */
data class PostalAddress(
    val poBox: String? = null,
    val extendedAddress: String? = null,
    val street: String? = null,
    val locality: String? = null,
    val region: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    val types: List<String> = emptyList(),
    /** Custom label from a grouped `itemN.X-ABLabel`, else null. */
    val label: String? = null,
)

/** An instant-messaging / social handle, from `IMPP` or a routed `X-SOCIALPROFILE`. */
data class ImHandle(
    /** Service/protocol (e.g. "xmpp", "twitter"), lower-cased when known. */
    val protocol: String?,
    /** The handle or URI value. */
    val handle: String,
)

/** A relation, from 4.0 `RELATED` or a routed 3.0 `X-ABRELATEDNAMES`. */
data class Relation(
    val name: String,
    /** Relationship label/type (e.g. "spouse"), lower-cased when known. */
    val type: String? = null,
)

/**
 * A contact photo, either a remote [url] or inline [data] bytes. [contentType] is null when the
 * source declares no type, as with a photo read from a device row.
 */
data class Photo(
    val url: String? = null,
    val data: ByteArray? = null,
    /** MIME subtype/extension as reported by the body (e.g. "jpeg", "png"). */
    val contentType: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Photo) return false
        return url == other.url &&
            contentType == other.contentType &&
            (data?.contentEquals(other.data ?: ByteArray(0)) ?: (other.data == null))
    }

    override fun hashCode(): Int {
        var result = url?.hashCode() ?: 0
        result = 31 * result + (data?.contentHashCode() ?: 0)
        result = 31 * result + (contentType?.hashCode() ?: 0)
        return result
    }
}

/**
 * A `BDAY` or `ANNIVERSARY` value: [date] when it is a full calendar date, and [text] for a
 * partial date or free text. An Apple `X-ABDATE` anniversary keeps its raw string in [text]
 * alongside a parsed [date].
 */
data class ContactDate(
    val date: LocalDate? = null,
    val text: String? = null,
)
