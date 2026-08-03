package org.onekash.vcard.model

import java.time.LocalDate

/**
 * Neutral, framework-free representation of a single vCard.
 *
 * This is the handoff type between the format layer (vCard bytes/text, parsed
 * here in the pure-JVM module) and the later Android-coupled layers that map it
 * onto the system Contacts Provider. It carries **no** ez-vcard, Android, or
 * networking types by design: callers never need the vCard library on their
 * classpath.
 *
 * Both vCard 3.0 (RFC 2426) and 4.0 (RFC 6350) collapse into this one shape.
 * [version] reflects the `VERSION:` line of the parsed body, not any version the
 * caller may have requested.
 */
data class Contact(
    /** The `VERSION:` value from the parsed body ("3.0" / "4.0"). */
    val version: String,

    /** `UID` property, or empty when the body carries none (RFC 6350 §6.7.6, `*1`). */
    val uid: String,

    /** Structured `N` name. Always present (empty components when the body omits it). */
    val structuredName: StructuredName,

    /**
     * `FN` formatted/display name. Never blank on the model: when the body has no
     * `FN`, this is derived from [structuredName].
     */
    val displayName: String,

    val nickname: String? = null,
    val emails: List<Email> = emptyList(),
    val phones: List<Phone> = emptyList(),
    val addresses: List<PostalAddress> = emptyList(),

    /** `ORG` components (company, then organizational units). */
    val organization: List<String> = emptyList(),
    val title: String? = null,

    val urls: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val imHandles: List<ImHandle> = emptyList(),
    val relations: List<Relation> = emptyList(),
    val categories: List<String> = emptyList(),

    val photo: Photo? = null,

    val birthday: ContactDate? = null,
    val anniversary: ContactDate? = null,

    /** The verbatim vCard text this contact was parsed from (round-trip fidelity). */
    val rawVCard: String,
)

/** Structured `N` components (RFC 6350 §6.2.2). */
data class StructuredName(
    val family: String? = null,
    val given: String? = null,
    val middle: String? = null,
    val prefix: String? = null,
    val suffix: String? = null,
) {
    /** Space-joined display form built from the populated components, in reading order. */
    fun toDisplayName(): String =
        listOfNotNull(prefix, given, middle, family, suffix)
            .filter { it.isNotBlank() }
            .joinToString(" ")
}

/** An `EMAIL` value with its types and a normalized preferred flag. */
data class Email(
    val address: String,
    /** Lower-cased `TYPE` tokens (e.g. "home", "work"), excluding the preference marker. */
    val types: List<String> = emptyList(),
    /** True for 3.0 `TYPE=PREF` and 4.0 `PREF=1` alike. */
    val preferred: Boolean = false,
)

/** A `TEL` value, with the `tel:` scheme stripped from 4.0 URI form. */
data class Phone(
    val number: String,
    val types: List<String> = emptyList(),
    val preferred: Boolean = false,
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
 * A contact photo, in exactly one of two shapes: a remote [url], or inline
 * [data] bytes with their [contentType].
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
 * A `BDAY`/`ANNIVERSARY` value. A full calendar [date] when the body carried one;
 * otherwise the un-parsed [text] (partial dates, free-text values) is retained.
 */
data class ContactDate(
    val date: LocalDate? = null,
    val text: String? = null,
)
