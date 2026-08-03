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
        val structuredName = card.structuredName?.let {
            StructuredName(
                family = it.family.blankToNull(),
                given = it.given.blankToNull(),
                middle = it.additionalNames.firstOrNull().blankToNull(),
                prefix = it.prefixes.firstOrNull().blankToNull(),
                suffix = it.suffixes.firstOrNull().blankToNull(),
            )
        } ?: StructuredName()

        val displayName = card.formattedName?.value.blankToNull() ?: structuredName.toDisplayName()

        val emails = card.emails.map { e ->
            val types = e.types.map { it.value.lowercase() }
            Email(
                address = e.value.orEmpty(),
                types = types.filter { it != "pref" },
                preferred = e.pref != null || types.contains("pref"),
            )
        }

        val phones = card.telephoneNumbers.map { t ->
            val number = (t.text.blankToNull() ?: t.uri?.number ?: t.uri?.toString().orEmpty())
                .removePrefix("tel:")
            val types = t.types.map { it.value.lowercase() }
            Phone(
                number = number,
                types = types.filter { it != "pref" },
                preferred = t.pref != null || types.contains("pref"),
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
        val raw = card.extendedProperties
        val labelsByGroup = raw
            .filter { it.propertyName.equals("X-ABLabel", ignoreCase = true) && it.group != null }
            .associate { it.group to normalizeAppleLabel(it.value) }

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

        return Contact(
            version = card.version?.version ?: "3.0",
            uid = card.uid?.value.orEmpty(),
            structuredName = structuredName,
            displayName = displayName,
            nickname = card.nickname?.values?.firstOrNull().blankToNull(),
            emails = emails,
            phones = phones,
            addresses = addresses,
            organization = card.organization?.values.orEmpty(),
            title = card.titles.firstOrNull()?.value.blankToNull(),
            urls = card.urls.mapNotNull { it.value.blankToNull() },
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

    private fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }

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
