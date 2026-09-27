package org.onekash.vcard

import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.property.RawProperty
import org.onekash.vcard.model.Contact
import org.onekash.vcard.model.ContactDate
import org.onekash.vcard.model.Email
import org.onekash.vcard.model.ImHandle
import org.onekash.vcard.model.Photo
import org.onekash.vcard.model.Phone
import org.onekash.vcard.model.PostalAddress
import org.onekash.vcard.model.Relation
import org.onekash.vcard.model.StructuredName
import org.onekash.vcard.model.WebAddress
import java.time.LocalDate

/**
 * Parses vCard bodies into the neutral [Contact] model.
 *
 * No ez-vcard type appears in any public signature, so callers never need the library on their
 * classpath. vCard 3.0 (RFC 2426) and 4.0 (RFC 6350) share one code path, and the version always
 * comes from the body's `VERSION:` line.
 *
 * ez-vcard leaves the 3.0 Apple `itemN.X-...` forms as raw extended properties, so this parser
 * routes them by hand: `X-ABDATE` and `X-ABRELATEDNAMES` by their `itemN` group's `X-ABLabel`
 * (anniversary, relation type), and `X-SOCIALPROFILE` into [Contact.imHandles] beside `IMPP`.
 */
class VCardParser {

    /**
     * Parses a vCard body given as text, one [Contact] per card. [requestedVersion] is accepted
     * for symmetry with callers that negotiated a version but deliberately ignored: the body's
     * own `VERSION:` line wins.
     */
    fun parse(body: String, @Suppress("UNUSED_PARAMETER") requestedVersion: String? = null): List<Contact> {
        val cards = Ezvcard.parse(body).all()
        // CardDAV serves one vCard per resource, so a single-card body is kept verbatim as
        // rawVCard (property order, folding and unmapped X- properties intact). Each card of a
        // multi-card body is re-serialized instead.
        return cards.map { toContact(it, rawOverride = if (cards.size == 1) body else null) }
    }

    /** Parses a vCard body given as raw bytes (decoded as UTF-8). */
    fun parse(bytes: ByteArray, requestedVersion: String? = null): List<Contact> =
        parse(bytes.decodeToString(), requestedVersion)

    private fun toContact(card: VCard, rawOverride: String?): Contact {
        // Custom labels (itemN.X-ABLabel) by group. The idiom is Apple's 3.0 one, but the
        // group also sits on native EMAIL, TEL, ADR and URL, so those keep their label
        // instead of collapsing to a generic type. ez-vcard leaves X-ABLabel as a raw property.
        val labelsByGroup = card.extendedProperties
            .filter { it.propertyName.equals("X-ABLabel", ignoreCase = true) && it.group != null }
            .associate { it.group to normalizeAppleLabel(it.value) }

        // The X-PHONETIC-* reading aids are separate properties, read whether or not the card
        // has an N.
        val n = card.structuredName
        val structuredName = StructuredName(
            family = n?.family.blankToNull(),
            given = n?.given.blankToNull(),
            // Join every value of these list components (a second middle name, "Dr. Prof."),
            // not only the first.
            middle = n?.additionalNames?.joinNonBlank(),
            prefix = n?.prefixes?.joinNonBlank(),
            suffix = n?.suffixes?.joinNonBlank(),
            phoneticGiven = card.phonetic("X-PHONETIC-FIRST-NAME"),
            phoneticMiddle = card.phonetic("X-PHONETIC-MIDDLE-NAME"),
            phoneticFamily = card.phonetic("X-PHONETIC-LAST-NAME"),
        )

        val displayName = card.formattedName?.value.blankToNull() ?: structuredName.toDisplayName()

        val emails = card.emails.map { e ->
            val types = e.types.map { it.value.lowercase() }
            Email(
                address = e.value.orEmpty(),
                types = types.filter { it != "pref" },
                preferred = e.pref != null || types.contains("pref"),
                label = e.group?.let { labelsByGroup[it] },
            )
        }

        val phones = card.telephoneNumbers.map { t ->
            val number = phoneNumber(t).removePrefix("tel:")
            val types = t.types.map { it.value.lowercase() }
            Phone(
                number = number,
                types = types.filter { it != "pref" },
                preferred = t.pref != null || types.contains("pref"),
                label = t.group?.let { labelsByGroup[it] },
            )
        }

        val addresses = card.addresses.map { a ->
            PostalAddress(
                poBox = a.poBox.blankToNull(),
                extendedAddress = a.extendedAddressFull.blankToNull(),
                street = a.streetAddressFull.blankToNull(),
                locality = a.locality.blankToNull(),
                region = a.region.blankToNull(),
                postalCode = a.postalCode.blankToNull(),
                country = a.country.blankToNull(),
                types = a.types.map { it.value.lowercase() },
                label = a.group?.let { labelsByGroup[it] },
            )
        }

        val imHandles = card.impps.mapTo(ArrayList(card.impps.size)) { impp ->
            ImHandle(
                protocol = impp.protocol?.lowercase(),
                handle = impp.handle ?: impp.uri?.toString().orEmpty(),
            )
        }

        val relations = card.relations.mapTo(ArrayList(card.relations.size)) { r ->
            Relation(
                name = r.text ?: r.uri.orEmpty(),
                type = r.types.firstOrNull()?.value?.lowercase(),
            )
        }

        val photo = card.photos.firstOrNull()?.let { p ->
            Photo(
                url = p.url,
                data = p.data,
                contentType = p.contentType?.value,
            )
        }

        var anniversary = card.anniversary?.let { toContactDate(it) }
        val birthday = card.birthday?.let { toContactDate(it) }

        // Route the 3.0 Apple forms that ez-vcard leaves as raw properties (see the class doc).
        val raw = card.extendedProperties
        for (prop in raw) {
            when {
                prop.propertyName.equals("X-ABDATE", ignoreCase = true) -> {
                    val label = prop.group?.let { labelsByGroup[it] }
                    // A native ANNIVERSARY, when present, wins over the Apple raw form.
                    if (label.equals("Anniversary", ignoreCase = true) && anniversary == null) {
                        anniversary = dateFromText(prop.value)
                    }
                }
                prop.propertyName.equals("X-ABRELATEDNAMES", ignoreCase = true) -> {
                    val label = prop.group?.let { labelsByGroup[it] }
                    relations.add(Relation(name = prop.value.orEmpty(), type = label?.lowercase()))
                }
                prop.propertyName.equals("X-SOCIALPROFILE", ignoreCase = true) -> {
                    imHandles.add(
                        ImHandle(
                            protocol = socialType(prop)?.lowercase(),
                            handle = prop.value.orEmpty(),
                        ),
                    )
                }
            }
        }

        // Lower-cased so callers can compare against "group" whatever the source spelling or
        // server casing ([Contact.kind]).
        val kind = (card.kind?.value.blankToNull()
            ?: card.getExtendedProperty("X-ADDRESSBOOKSERVER-KIND")?.value.blankToNull())
            ?.lowercase()

        return Contact(
            version = card.version?.version ?: "3.0",
            uid = card.uid?.value.orEmpty(),
            kind = kind,
            structuredName = structuredName,
            displayName = displayName,
            nickname = card.nickname?.values?.firstOrNull().blankToNull(),
            emails = emails,
            phones = phones,
            addresses = addresses,
            organization = card.organization?.values.orEmpty(),
            title = card.titles.firstOrNull()?.value.blankToNull(),
            role = card.roles.firstOrNull()?.value.blankToNull(),
            urls = card.urls.mapNotNull { u ->
                u.value.blankToNull()?.let { WebAddress(url = it, label = u.group?.let { g -> labelsByGroup[g] }) }
            },
            notes = card.notes.mapNotNull { it.value.blankToNull() },
            imHandles = imHandles,
            relations = relations,
            categories = card.categories?.values.orEmpty(),
            photo = photo,
            birthday = birthday,
            anniversary = anniversary,
            rawVCard = rawOverride ?: card.rawText(),
        )
    }

    /**
     * Converts a native BDAY or ANNIVERSARY; null when it has no usable value. A full calendar
     * date becomes [ContactDate.date]; anything else is kept as text. That text is either a
     * free-text value or a reduced-accuracy date such as `--0415` (RFC 6350 §4.3.1, a birthday
     * without a year), which ez-vcard exposes only through `partialDate`, with neither `date`
     * nor `text` set. Without the `partialDate` read those would be silently dropped.
     */
    private fun toContactDate(prop: ezvcard.property.DateOrTimeProperty): ContactDate? {
        val localDate = prop.date?.let { runCatching { LocalDate.from(it) }.getOrNull() }
        val text = prop.text.blankToNull()
            ?: prop.partialDate?.let { runCatching { it.toISO8601(true) }.getOrNull() }.blankToNull()
        if (localDate == null && text == null) return null
        return ContactDate(date = localDate, text = text)
    }

    /**
     * Converts an Apple raw X-ABDATE value, an ISO or basic-ISO date. The trimmed string is
     * always kept as text; null only when blank.
     */
    private fun dateFromText(value: String?): ContactDate? {
        val v = value?.trim().blankToNull() ?: return null
        val localDate = runCatching { LocalDate.parse(v) }.getOrNull()
            ?: runCatching { LocalDate.parse(v, java.time.format.DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
        return ContactDate(date = localDate, text = v)
    }

    /**
     * Returns the dialable text of a TEL property, or "" when no source yields one.
     *
     * A 4.0 `TEL;VALUE=uri` whose value isn't a valid tel URI (for example a global number
     * without the leading "+") makes ez-vcard fall back to the raw `text`. A URI that parses but
     * fails a lazy accessor (`uri.number`, `uri.toString()`) would throw, and the caller's
     * per-body catch would discard the whole contact. Each source is guarded so a bad phone
     * costs only that number.
     */
    private fun phoneNumber(t: ezvcard.property.Telephone): String {
        t.text.blankToNull()?.let { return it }
        runCatching { t.uri?.number }.getOrNull().blankToNull()?.let { return it }
        return runCatching { t.uri?.toString() }.getOrNull().orEmpty()
    }

    private fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }

    /** Space-joins the non-blank values of a multi-valued `N` component; null when empty. */
    private fun List<String>.joinNonBlank(): String? =
        filter { it.isNotBlank() }.joinToString(" ").blankToNull()

    /** Value of the first [name] X- property (e.g. X-PHONETIC-FIRST-NAME), null when blank. */
    private fun VCard.phonetic(name: String): String? =
        getExtendedProperty(name)?.value.blankToNull()

    /** Apple wraps custom labels as `_$!<Anniversary>!$_`; unwrap to the inner text. */
    private fun normalizeAppleLabel(raw: String?): String? {
        val v = raw?.trim() ?: return null
        return v.removePrefix("_\$!<").removeSuffix(">!\$_").trim().takeIf { it.isNotBlank() }
    }

    /** The X-SOCIALPROFILE service, carried as the TYPE parameter (e.g. "twitter"). */
    private fun socialType(prop: RawProperty): String? =
        prop.parameters.type?.takeIf { it.isNotBlank() }

    /** Serializes this card to vCard text at its own version, for [Contact.rawVCard]. */
    private fun VCard.rawText(): String =
        Ezvcard.write(this).version(this.version).go()
}
