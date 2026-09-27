package org.onekash.vcard

import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.VCardVersion
import ezvcard.parameter.AddressType
import ezvcard.parameter.EmailType
import ezvcard.parameter.ImageType
import ezvcard.parameter.RelatedType
import ezvcard.parameter.TelephoneType
import ezvcard.property.Address
import ezvcard.property.Anniversary
import ezvcard.property.Birthday
import ezvcard.property.Categories
import ezvcard.property.FormattedName
import ezvcard.property.Impp
import ezvcard.property.Kind
import ezvcard.property.Nickname
import ezvcard.property.Note
import ezvcard.property.Organization
import ezvcard.property.Related
import ezvcard.property.Role
import ezvcard.property.Telephone
import ezvcard.property.Title
import ezvcard.property.Uid
import ezvcard.property.Url
import ezvcard.property.VCardProperty
import org.onekash.vcard.model.Contact
import org.onekash.vcard.model.Photo
import ezvcard.property.Email as EzEmail
import ezvcard.property.Photo as EzPhoto
import ezvcard.property.StructuredName as EzStructuredName

/**
 * Serializes the neutral [Contact] model back into vCard text, the inverse of [VCardParser]. As
 * with the parser, no ez-vcard type appears on any public signature.
 *
 * ## Patch mode
 *
 * The model is lossy: it carries only the fields the app maps, not every property, parameter or
 * grouping a server body can hold, so regenerating a body from the model alone would silently
 * drop the rest. When [Contact.rawVCard] holds one parseable card, the writer re-parses it,
 * compares each facet with the passed contact, and rewrites only the facets whose value changed.
 * A rewritten facet replaces all of its properties (editing one phone re-emits every TEL line
 * and its label). Unmapped properties (X- properties, unknown parameters, `itemN` groups) and
 * unchanged facets stay the original ez-vcard property objects, re-serialized by ez-vcard.
 *
 * With a blank, unparseable or multi-card [Contact.rawVCard] it generates one card from the
 * mapped fields at the requested version instead.
 *
 * ## The diff baseline
 *
 * Patch mode diffs the passed contact against `VCardParser.parse(rawVCard)`, so the parser's
 * representation sits on both sides. Any other producer of the passed contact, such as the app's
 * device-row mapper, must yield facets equal to the parser's for unedited fields, or the diff
 * rewrites facets the user never touched.
 *
 * ## Version handling
 *
 * The caller picks the output version ([write]'s `version`, defaulting to [Contact.version]); it
 * is never hardcoded. Patching a 3.0 or 4.0 body at its stored version converts nothing.
 *
 * ## Dual-spelling facets
 *
 * Four fields have two spellings on the wire, a native property and an Apple raw-property idiom
 * the parser routes by hand: the anniversary (`itemN.X-ABDATE`), relations (`X-ABRELATEDNAMES`),
 * IM handles (`X-SOCIALPROFILE`), and the group marker (`X-ADDRESSBOOKSERVER-KIND`). An edit to
 * one clears both spellings and emits the single form right for the target version, so the body
 * never carries both. ANNIVERSARY, RELATED and KIND are 4.0-only properties ez-vcard silently
 * drops from a 3.0 body, so a 3.0 card must carry them as the raw idiom (`itemN.X-ABDATE` with
 * an `Anniversary` label, `X-ABRELATEDNAMES`, `X-ADDRESSBOOKSERVER-KIND`) or the value is lost;
 * at 4.0 they are native properties. IMPP is valid at both versions, so IM handles are
 * emitted as IMPP. An unchanged dual-spelling field isn't touched and keeps its original
 * spelling.
 */
class VCardWriter {

    /**
     * Serializes [contact] to vCard text at [version], defaulting to the contact's own. "4.0"
     * writes 4.0; any other value, a parsed "2.1" included, writes 3.0.
     */
    fun write(contact: Contact, version: String = contact.version): String {
        val target = if (version.trim() == "4.0") VCardVersion.V4_0 else VCardVersion.V3_0
        val base = parseSingleBase(contact.rawVCard)
        val card = if (base != null) patch(base, contact, target) else generate(contact, target)
        return Ezvcard.write(card).version(target).prodId(false).go()
    }

    /** Returns the original body's card when it is one parseable card, else null. */
    private fun parseSingleBase(rawVCard: String): VCard? {
        if (rawVCard.isBlank()) return null
        val cards = runCatching { Ezvcard.parse(rawVCard).all() }.getOrNull() ?: return null
        return cards.singleOrNull()
    }

    /** Rewrites only the facets whose model value differs from the parsed original. */
    private fun patch(base: VCard, contact: Contact, target: VCardVersion): VCard {
        val original = VCardParser().parse(contact.rawVCard).single()
        val groups = collectGroups(base)

        if (contact.displayName != original.displayName) applyFormattedName(base, contact)
        if (contact.structuredName != original.structuredName) applyStructuredName(base, contact)
        if (contact.nickname != original.nickname) applyNickname(base, contact)
        if (contact.organization != original.organization) applyOrganization(base, contact)
        if (contact.title != original.title) applyTitle(base, contact)
        if (contact.role != original.role) applyRole(base, contact)
        if (contact.emails != original.emails) applyEmails(base, contact, groups, target)
        if (contact.phones != original.phones) applyPhones(base, contact, groups, target)
        if (contact.addresses != original.addresses) applyAddresses(base, contact, groups)
        if (contact.urls != original.urls) applyUrls(base, contact, groups)
        if (contact.notes != original.notes) applyNotes(base, contact)
        if (contact.categories != original.categories) applyCategories(base, contact)
        if (contact.uid != original.uid) applyUid(base, contact)
        if (photoContentChanged(contact.photo, original.photo)) applyPhoto(base, contact)
        if (contact.birthday != original.birthday) applyBirthday(base, contact)
        // The four dual-spelling facets (see the class doc).
        if (contact.anniversary != original.anniversary) applyAnniversary(base, contact, target, groups)
        if (contact.relations != original.relations) applyRelations(base, contact, target, groups)
        if (contact.imHandles != original.imHandles) applyImHandles(base, contact)
        if (contact.kind != original.kind) applyKind(base, contact, target)
        return base
    }

    /** Builds a new card from every populated mapped field. */
    private fun generate(contact: Contact, target: VCardVersion): VCard {
        val card = VCard()
        val groups = HashSet<String>()
        applyUid(card, contact)
        applyKind(card, contact, target)
        applyFormattedName(card, contact)
        applyStructuredName(card, contact)
        applyNickname(card, contact)
        applyOrganization(card, contact)
        applyTitle(card, contact)
        applyRole(card, contact)
        applyEmails(card, contact, groups, target)
        applyPhones(card, contact, groups, target)
        applyAddresses(card, contact, groups)
        applyUrls(card, contact, groups)
        applyNotes(card, contact)
        applyCategories(card, contact)
        applyPhoto(card, contact)
        applyBirthday(card, contact)
        applyAnniversary(card, contact, target, groups)
        applyRelations(card, contact, target, groups)
        applyImHandles(card, contact)
        return card
    }

    // --- Facet writers: each clears its own properties then re-adds them from the model, so
    //     generate (on an empty card) and patch share them.

    private fun applyFormattedName(card: VCard, contact: Contact) {
        card.removeProperties(FormattedName::class.java)
        val fn = formattedNameFor(contact)
        if (fn.isNotBlank()) card.setFormattedName(fn)
    }

    /**
     * Returns the FN value to write, blank only for a contact with no identifying field (which
     * then emits no FN).
     *
     * FN is mandatory (RFC 6350 §6.2.1, RFC 2426 §3.1.1) and strict servers reject a card
     * without it, but a device contact can carry only a phone or email and no name row, leaving
     * [Contact.displayName] and [Contact.structuredName] blank. Without a display name this
     * falls back to the structured name, then organization, nickname, first email and first
     * phone, the order a contacts UI uses to label a nameless entry.
     */
    private fun formattedNameFor(contact: Contact): String {
        contact.displayName.trim().takeIf { it.isNotBlank() }?.let { return it }
        // The same display form the parser derives displayName from, so the two can't drift.
        contact.structuredName.toDisplayName().takeIf { it.isNotBlank() }?.let { return it }
        contact.organization.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            .takeIf { it.isNotBlank() }?.let { return it }
        contact.nickname?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        contact.emails.firstOrNull { it.address.isNotBlank() }?.let { return it.address.trim() }
        contact.phones.firstOrNull { it.number.isNotBlank() }?.let { return it.number.trim() }
        return ""
    }

    private fun applyStructuredName(card: VCard, contact: Contact) {
        card.removeProperties(EzStructuredName::class.java)
        card.removeExtendedProperty("X-PHONETIC-FIRST-NAME")
        card.removeExtendedProperty("X-PHONETIC-MIDDLE-NAME")
        card.removeExtendedProperty("X-PHONETIC-LAST-NAME")

        val sn = contact.structuredName
        val hasName = listOf(sn.family, sn.given, sn.middle, sn.prefix, sn.suffix)
            .any { !it.isNullOrBlank() }
        if (hasName) {
            val n = EzStructuredName()
            sn.family?.let { n.family = it }
            sn.given?.let { n.given = it }
            sn.middle?.let { n.additionalNames.add(it) }
            sn.prefix?.let { n.prefixes.add(it) }
            sn.suffix?.let { n.suffixes.add(it) }
            card.setStructuredName(n)
        } else {
            // N is optional at 4.0 (RFC 6350 §6.2.2) but required at 3.0 (RFC 2426 §3.1.2),
            // and some servers (iCloud among them) 403 a card without N while accepting one
            // whose five components are all empty (`N:;;;;`). Emit that empty form: it
            // asserts no false name (FN stays the only label) and re-parses to an empty
            // structured name, so the round trip is unchanged.
            card.setStructuredName(EzStructuredName())
        }
        sn.phoneticGiven?.let { card.addExtendedProperty("X-PHONETIC-FIRST-NAME", it) }
        sn.phoneticMiddle?.let { card.addExtendedProperty("X-PHONETIC-MIDDLE-NAME", it) }
        sn.phoneticFamily?.let { card.addExtendedProperty("X-PHONETIC-LAST-NAME", it) }
    }

    private fun applyNickname(card: VCard, contact: Contact) {
        card.removeProperties(Nickname::class.java)
        contact.nickname?.let { card.setNickname(it) }
    }

    private fun applyOrganization(card: VCard, contact: Contact) {
        card.removeProperties(Organization::class.java)
        if (contact.organization.isNotEmpty()) {
            card.setOrganization(*contact.organization.toTypedArray())
        }
    }

    private fun applyTitle(card: VCard, contact: Contact) {
        card.removeProperties(Title::class.java)
        contact.title?.let { card.addTitle(it) }
    }

    private fun applyRole(card: VCard, contact: Contact) {
        card.removeProperties(Role::class.java)
        contact.role?.let { card.addRole(it) }
    }

    private fun applyEmails(
        card: VCard,
        contact: Contact,
        groups: MutableSet<String>,
        target: VCardVersion,
    ) {
        removeLabelsFor(card, card.removeProperties(EzEmail::class.java))
        contact.emails.forEach { e ->
            val prop = EzEmail(e.address)
            e.types.forEach { prop.types.add(EmailType.get(it)) }
            if (e.preferred) {
                if (target == VCardVersion.V4_0) prop.pref = 1 else prop.types.add(EmailType.PREF)
            }
            attachLabel(card, prop, e.label, groups)
            card.addProperty(prop)
        }
    }

    private fun applyPhones(
        card: VCard,
        contact: Contact,
        groups: MutableSet<String>,
        target: VCardVersion,
    ) {
        removeLabelsFor(card, card.removeProperties(Telephone::class.java))
        contact.phones.forEach { p ->
            val prop = Telephone(p.number)
            p.types.forEach { prop.types.add(TelephoneType.get(it)) }
            if (p.preferred) {
                if (target == VCardVersion.V4_0) prop.pref = 1 else prop.types.add(TelephoneType.PREF)
            }
            attachLabel(card, prop, p.label, groups)
            card.addProperty(prop)
        }
    }

    private fun applyAddresses(card: VCard, contact: Contact, groups: MutableSet<String>) {
        removeLabelsFor(card, card.removeProperties(Address::class.java))
        contact.addresses.forEach { a ->
            val prop = Address()
            a.poBox?.let { prop.poBox = it }
            a.extendedAddress?.let { prop.extendedAddress = it }
            a.street?.let { prop.streetAddress = it }
            a.locality?.let { prop.locality = it }
            a.region?.let { prop.region = it }
            a.postalCode?.let { prop.postalCode = it }
            a.country?.let { prop.country = it }
            a.types.forEach { prop.types.add(AddressType.get(it)) }
            attachLabel(card, prop, a.label, groups)
            card.addProperty(prop)
        }
    }

    private fun applyUrls(card: VCard, contact: Contact, groups: MutableSet<String>) {
        removeLabelsFor(card, card.removeProperties(Url::class.java))
        contact.urls.forEach { u ->
            val prop = Url(u.url)
            attachLabel(card, prop, u.label, groups)
            card.addProperty(prop)
        }
    }

    private fun applyNotes(card: VCard, contact: Contact) {
        card.removeProperties(Note::class.java)
        contact.notes.forEach { card.addNote(it) }
    }

    private fun applyCategories(card: VCard, contact: Contact) {
        card.removeProperties(Categories::class.java)
        if (contact.categories.isNotEmpty()) {
            card.setCategories(*contact.categories.toTypedArray())
        }
    }

    private fun applyUid(card: VCard, contact: Contact) {
        card.removeProperties(Uid::class.java)
        if (contact.uid.isNotBlank()) card.setUid(Uid(contact.uid))
    }

    private fun applyPhoto(card: VCard, contact: Contact) {
        card.removeProperties(EzPhoto::class.java)
        val p = contact.photo ?: return
        val type = imageType(p)
        val photo = when {
            p.data != null -> EzPhoto(p.data, type)
            p.url != null -> EzPhoto(p.url, type)
            else -> null
        }
        photo?.let { card.addPhoto(it) }
    }

    /**
     * Returns the image type for a regenerated PHOTO. A declared [Photo.contentType] wins. Without
     * one (a photo from a device row has no MIME subtype) the type is sniffed from the inline
     * bytes so a PNG, GIF, WebP or HEIF isn't relabeled as JPEG. Falls back to JPEG only when there
     * is neither a declared type nor recognizable bytes.
     */
    private fun imageType(p: Photo): ImageType {
        p.contentType?.let { return ImageType.get(it, null, it) }
        p.data?.let { detectImageType(it)?.let { type -> return type } }
        return ImageType.JPEG
    }

    /**
     * Maps the [ImageFormat] sniff to an ez-vcard image type; null when unknown (the caller then
     * uses JPEG). ez-vcard 0.12.2 has no predefined WebP or HEIC constant, so those get an
     * explicit value, media type and extension: a 3.0 body carries TYPE=webp or heic, a 4.0
     * body `data:image/webp` or `data:image/heic`.
     */
    private fun detectImageType(bytes: ByteArray): ImageType? = when (ImageFormat.sniff(bytes)) {
        ImageFormat.JPEG -> ImageType.JPEG
        ImageFormat.PNG -> ImageType.PNG
        ImageFormat.GIF -> ImageType.GIF
        ImageFormat.WEBP -> ImageType.get("webp", "image/webp", "webp")
        ImageFormat.HEIF -> ImageType.get("heic", "image/heic", "heic")
        ImageFormat.UNKNOWN -> null
    }

    /**
     * Returns whether the photo's bytes or URL changed, ignoring [Photo.contentType]. A device
     * Contacts Photo row has no MIME column, so a photo read back from the device loses only its
     * contentType; counting that as a change would rewrite and relabel the PHOTO on every sync
     * without converging. A bytes or URL edit, or adding or removing the photo, still counts.
     */
    private fun photoContentChanged(new: Photo?, old: Photo?): Boolean =
        new?.copy(contentType = null) != old?.copy(contentType = null)

    private fun applyBirthday(card: VCard, contact: Contact) {
        card.removeProperties(Birthday::class.java)
        val b = contact.birthday ?: return
        when {
            b.date != null -> card.setBirthday(b.date)
            b.text != null -> card.setBirthday(Birthday(b.text))
        }
    }

    private fun applyAnniversary(
        card: VCard,
        contact: Contact,
        target: VCardVersion,
        groups: MutableSet<String>,
    ) {
        // Clear both spellings so an edit can't leave the old value in the other form.
        card.removeProperties(Anniversary::class.java)
        removeAnniversaryRawIdiom(card)
        val a = contact.anniversary ?: return
        if (target == VCardVersion.V4_0) {
            when {
                a.date != null -> card.setAnniversary(a.date)
                a.text != null -> card.setAnniversary(Anniversary(a.text))
            }
        } else {
            // At 3.0, carry the anniversary as Apple's itemN.X-ABDATE in an "Anniversary"
            // labeled group, the idiom the parser routes back into this field.
            val value = a.date?.toString() ?: a.text ?: return
            val group = allocateGroup(groups)
            card.addExtendedProperty("X-ABDATE", value).group = group
            card.addExtendedProperty("X-ABLabel", wrapAppleLabel("Anniversary")).group = group
        }
    }

    private fun applyRelations(
        card: VCard,
        contact: Contact,
        target: VCardVersion,
        groups: MutableSet<String>,
    ) {
        card.removeProperties(Related::class.java)
        removeRelationRawIdiom(card)
        // The parser lower-cases the relation type on read, so emit it lower-cased too;
        // otherwise a differently-cased type would diff unequal and re-emit on every sync.
        if (target == VCardVersion.V4_0) {
            contact.relations.forEach { r ->
                val prop = Related()
                prop.text = r.name
                r.type?.let { prop.types.add(RelatedType.get(it.lowercase())) }
                card.addProperty(prop)
            }
        } else {
            // At 3.0, carry each relation as Apple's raw itemN.X-ABRELATEDNAMES, with the
            // relation type as the group's label.
            contact.relations.forEach { r ->
                val group = allocateGroup(groups)
                card.addExtendedProperty("X-ABRELATEDNAMES", r.name).group = group
                r.type?.takeIf { it.isNotBlank() }?.let {
                    card.addExtendedProperty("X-ABLabel", wrapAppleLabel(it.lowercase())).group = group
                }
            }
        }
    }

    private fun applyImHandles(card: VCard, contact: Contact) {
        // IMPP is valid at both 3.0 (RFC 4770) and 4.0, so IM handles are emitted as IMPP at
        // either version. Clear the Apple raw X-SOCIALPROFILE spelling too, so an edit to a
        // handle read from it doesn't leave the stale handle behind.
        card.removeProperties(Impp::class.java)
        removeAppleRawProps(card, "X-SOCIALPROFILE")
        contact.imHandles.forEach { im ->
            // The parser lower-cases the IMPP protocol and X-SOCIALPROFILE service on read, so
            // emit the protocol lower-cased too, or a mixed-case one would diff unequal and
            // re-emit on every sync (as for the relation type and KIND).
            val protocol = im.protocol?.lowercase()
            val uri = if (protocol.isNullOrBlank()) im.handle else "$protocol:${im.handle}"
            // A handle neither Impp constructor accepts is dropped.
            val prop = runCatching { Impp(uri) }.getOrNull()
                ?: runCatching { Impp(protocol ?: "", im.handle) }.getOrNull()
            prop?.let { card.addProperty(it) }
        }
    }

    private fun applyKind(card: VCard, contact: Contact, target: VCardVersion) {
        // Clear both spellings, then emit the one the version supports: at 3.0 the group
        // marker is carried as Apple's X-ADDRESSBOOKSERVER-KIND.
        card.removeProperties(Kind::class.java)
        card.removeExtendedProperty("X-ADDRESSBOOKSERVER-KIND")
        // The parser lower-cases KIND on read (RFC 6350 §6.1.4 lists its values in lower
        // case), so emit it lower-cased too, else a differently-cased value would diff unequal
        // and re-emit on every sync.
        val k = contact.kind?.lowercase() ?: return
        if (target == VCardVersion.V4_0) {
            card.setKind(Kind(k))
        } else {
            card.addExtendedProperty("X-ADDRESSBOOKSERVER-KIND", k)
        }
    }

    // --- Custom-label (itemN.X-ABLabel) grouping helpers.

    /** Returns every group already on the card, so a new `itemN` group never collides. */
    private fun collectGroups(card: VCard): MutableSet<String> =
        card.properties.mapNotNullTo(HashSet()) { it.group }

    /** Attaches an Apple-style custom label to [prop] through a new, unused group. */
    private fun attachLabel(
        card: VCard,
        prop: VCardProperty,
        label: String?,
        groups: MutableSet<String>,
    ) {
        if (label.isNullOrBlank()) return
        val group = allocateGroup(groups)
        prop.group = group
        card.addExtendedProperty("X-ABLabel", wrapAppleLabel(label)).group = group
    }

    private fun allocateGroup(groups: MutableSet<String>): String {
        var i = 1
        while (groups.contains("item$i")) i++
        val group = "item$i"
        groups.add(group)
        return group
    }

    /**
     * Removes every extended property named [propertyName] (an Apple raw idiom such as
     * `X-SOCIALPROFILE`) and the `X-ABLabel` in its `itemN` group, so re-applying the mapped
     * property can't leave a contradictory second spelling.
     */
    private fun removeAppleRawProps(card: VCard, propertyName: String) {
        val removed = card.extendedProperties.filter { it.propertyName.equals(propertyName, ignoreCase = true) }
        if (removed.isEmpty()) return
        removed.forEach { card.removeProperty(it) }
        removeLabelsFor(card, removed)
    }

    /**
     * Removes only the anniversary raw idiom, an `X-ABDATE` whose `itemN` group is labeled
     * `Anniversary`, plus its label. A differently labeled `X-ABDATE` (Apple's generic
     * custom-date form) is an unmapped property and must survive the edit, so the removal is
     * scoped by label, not by property name.
     */
    private fun removeAnniversaryRawIdiom(card: VCard) {
        val labels = labelsByGroup(card)
        val removed = card.extendedProperties.filter {
            it.propertyName.equals("X-ABDATE", ignoreCase = true) &&
                labels[it.group].equals("Anniversary", ignoreCase = true)
        }
        if (removed.isEmpty()) return
        removed.forEach { card.removeProperty(it) }
        removeLabelsFor(card, removed)
    }

    /** Removes every `X-ABRELATEDNAMES` (all are relations) plus its label. */
    private fun removeRelationRawIdiom(card: VCard) = removeAppleRawProps(card, "X-ABRELATEDNAMES")

    /** Maps each `itemN` group to its unwrapped `X-ABLabel` text, as the parser resolves it. */
    private fun labelsByGroup(card: VCard): Map<String?, String?> =
        card.extendedProperties
            .filter { it.propertyName.equals("X-ABLabel", ignoreCase = true) && it.group != null }
            .associate { it.group to unwrapAppleLabel(it.value) }

    /** Apple wraps custom labels as `_$!<Anniversary>!$_`; unwrap to the inner text. */
    private fun unwrapAppleLabel(raw: String?): String? {
        val v = raw?.trim() ?: return null
        return v.removePrefix("_\$!<").removeSuffix(">!\$_").trim().takeIf { it.isNotBlank() }
    }

    /** Wraps [text] in Apple's label syntax `_$!<text>!$_`, the inverse of [unwrapAppleLabel]. */
    private fun wrapAppleLabel(text: String): String = "_\$!<$text>!\$_"

    /** Drops the `X-ABLabel` properties in the groups of the [removed] properties. */
    private fun removeLabelsFor(card: VCard, removed: List<VCardProperty>) {
        val orphaned = removed.mapNotNullTo(HashSet()) { it.group }
        if (orphaned.isEmpty()) return
        card.getExtendedProperties("X-ABLabel")
            .filter { it.group in orphaned }
            .forEach { card.removeProperty(it) }
    }
}
