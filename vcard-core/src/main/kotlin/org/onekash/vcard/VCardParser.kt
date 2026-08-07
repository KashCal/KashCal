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
 * ez-vcard is confined entirely behind this class: no ez-vcard type appears in
 * any public signature, so callers never need the library on their classpath.
 * Both vCard 3.0 (RFC 2426) and 4.0 (RFC 6350) flow through one code path, and
 * the parsed version is always taken from the body's `VERSION:` line — never from
 * a version the caller requested.
 *
 * The load-bearing correction the design records: ez-vcard leaves the 3.0 Apple
 * `itemN.X-…` forms as raw/extended properties, so this parser hand-routes them
 * by `itemN` group + `X-ABLabel` (anniversary, related name) and treats
 * `X-SOCIALPROFILE` as a social handle rather than a native IMPP.
 */
class VCardParser {

    /**
     * Parse a vCard body given as text. [requestedVersion] is accepted for API
     * symmetry with callers that negotiated a version, but is deliberately
     * ignored for the actual parse — the body's own `VERSION:` line wins.
     */
    fun parse(body: String, @Suppress("UNUSED_PARAMETER") requestedVersion: String? = null): List<Contact> {
        val cards = Ezvcard.parse(body).all()
        // CardDAV address objects are one vCard per resource, so for the common
        // single-card body we retain the original text verbatim (true round-trip
        // fidelity: property order, folding, and any unmapped X- properties are
        // preserved). Only a rare multi-card body falls back to re-serialization.
        return cards.map { toContact(it, rawOverride = if (cards.size == 1) body else null) }
    }

    /** Parse a vCard body given as raw bytes (decoded as UTF-8). */
    fun parse(bytes: ByteArray, requestedVersion: String? = null): List<Contact> =
        parse(bytes.decodeToString(), requestedVersion)

    private fun toContact(card: VCard, rawOverride: String?): Contact {
        // Custom labels attached to any grouped property (itemN.X-ABLabel) — Apple's
        // 3.0 idiom, but the group is retained on native EMAIL/TEL/ADR/URL too, so a
        // labeled email/phone/address/url can recover its label instead of collapsing
        // to a generic type. ez-vcard leaves X-ABLabel as a raw/extended property.
        val labelsByGroup = card.extendedProperties
            .filter { it.propertyName.equals("X-ABLabel", ignoreCase = true) && it.group != null }
            .associate { it.group to normalizeAppleLabel(it.value) }

        // Phonetic reading aids come from X-PHONETIC-* properties, independent of N, so
        // they're read once and attached whether or not the card carries a structured N.
        val n = card.structuredName
        val structuredName = StructuredName(
            family = n?.family.blankToNull(),
            given = n?.given.blankToNull(),
            // Each N component is a comma-separated list; join the extras (a second
            // middle name, "Dr. Prof.") rather than keeping only the first.
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

        // Hand-route the 3.0 Apple itemN.X-… forms that ez-vcard leaves as raw properties.
        // (labelsByGroup was resolved above to also label native EMAIL/TEL/ADR/URL.)
        val raw = card.extendedProperties
        for (prop in raw) {
            when {
                prop.propertyName.equals("X-ABDATE", ignoreCase = true) -> {
                    val label = prop.group?.let { labelsByGroup[it] }
                    // Native 4.0 ANNIVERSARY, when present, takes precedence over the Apple raw form.
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

        // KIND (RFC 6350 §6.1.4): native 4.0 property, else the 3.0 Apple
        // X-ADDRESSBOOKSERVER-KIND fallback. Lower-cased so callers can compare
        // against "group" regardless of the source syntax or server casing.
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
     * Native ez-vcard BDAY/ANNIVERSARY. A full calendar date resolves to
     * [ContactDate.date]; otherwise the value is retained as text. That text can
     * come from an explicit free-text value OR — crucially — from a
     * reduced-accuracy date such as `--0415` (RFC 6350 §4.3.1, an unknown-year
     * birthday), which ez-vcard exposes only via `partialDate`, populating
     * neither `date` nor `text`. Consulting `partialDate` keeps those from being
     * silently dropped.
     */
    private fun toContactDate(prop: ezvcard.property.DateOrTimeProperty): ContactDate? {
        val localDate = prop.date?.let { runCatching { LocalDate.from(it) }.getOrNull() }
        val text = prop.text.blankToNull()
            ?: prop.partialDate?.let { runCatching { it.toISO8601(true) }.getOrNull() }.blankToNull()
        if (localDate == null && text == null) return null
        return ContactDate(date = localDate, text = text)
    }

    /** Apple raw X-ABDATE value: an ISO or basic-ISO date string, retained as text if unparseable. */
    private fun dateFromText(value: String?): ContactDate? {
        val v = value?.trim().blankToNull() ?: return null
        val localDate = runCatching { LocalDate.parse(v) }.getOrNull()
            ?: runCatching { LocalDate.parse(v, java.time.format.DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
        return ContactDate(date = localDate, text = v)
    }

    /**
     * The dialable text of a TEL property, degrading rather than dropping the
     * contact on a malformed value. A 4.0 `TEL;VALUE=uri` whose value isn't a
     * spec-valid tel URI (e.g. a global number missing the leading "+") makes
     * ez-vcard reject it: the current reader falls back to the raw `text`, but a
     * URI that parses yet fails a lazy accessor (`uri.number` / `uri.toString()`)
     * would throw and, via the caller's per-body catch, discard the WHOLE contact.
     * Guard each source so a bad phone costs only that number, never the contact.
     */
    private fun phoneNumber(t: ezvcard.property.Telephone): String {
        t.text.blankToNull()?.let { return it }
        runCatching { t.uri?.number }.getOrNull().blankToNull()?.let { return it }
        return runCatching { t.uri?.toString() }.getOrNull().orEmpty()
    }

    private fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }

    /** Space-join the non-blank values of a multi-valued `N` component; null when empty. */
    private fun List<String>.joinNonBlank(): String? =
        filter { it.isNotBlank() }.joinToString(" ").blankToNull()

    /** First non-blank value of a phonetic X- property (e.g. X-PHONETIC-FIRST-NAME). */
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

    /** Serialize this single card back to its vCard text for round-trip retention. */
    private fun VCard.rawText(): String =
        Ezvcard.write(this).version(this.version).go()
}
