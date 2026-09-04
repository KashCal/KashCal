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
 * Serializes the neutral [Contact] model back into vCard text — the inverse of
 * [VCardParser]. ez-vcard is confined entirely behind this class exactly as it is
 * for the parser: no ez-vcard type appears on any public signature.
 *
 * ## Patch-preferring by design
 *
 * The neutral model is lossy — it carries only the fields the app maps, not every
 * property, parameter, or grouping a server body can hold. Regenerating a body
 * purely from the model would silently drop the rest. So when a [Contact] still
 * carries the verbatim [Contact.rawVCard] it was parsed from, this writer works in
 * **patch mode**: it re-parses that original body, compares it field-by-field
 * against the passed contact, and rewrites ONLY the facets whose value changed.
 * Unmapped properties (X-props, unknown parameters, `itemN` groupings) and every
 * unchanged facet are left byte-faithful — they remain the original ez-vcard
 * property objects, untouched. Editing one phone number therefore changes exactly
 * that one line and preserves everything else.
 *
 * When there is no prior body to patch — a blank, unparseable, or multi-card
 * [Contact.rawVCard] — it falls back to **generate mode**: a clean, valid single
 * card built from the mapped fields at the requested version.
 *
 * ## The diff baseline
 *
 * Patch mode diffs the passed contact against `VCardParser.parse(rawVCard)` — i.e.
 * the parser's neutral representation sits on BOTH sides. Any later producer of the
 * passed contact (a device-row reverse mapper) must yield a representation that is
 * facet-equal to the parser's for unedited fields, or the diff would regenerate
 * facets the user never touched.
 *
 * ## Version handling
 *
 * The output version is caller-controlled ([version], defaulting to the contact's
 * own [Contact.version]); it is never hardcoded. In patch mode the natural call
 * passes the body's stored version, which is a no-op conversion.
 *
 * ## Dual-spelling facets
 *
 * A handful of fields have two on-the-wire spellings — a native property and an Apple
 * raw-property idiom the parser hand-routes: the anniversary (`itemN.X-ABDATE`),
 * relations (`X-ABRELATEDNAMES`), social/IM handles (`X-SOCIALPROFILE`), and the
 * group marker (`X-ADDRESSBOOKSERVER-KIND`). An edit to one of these IS applied in
 * patch mode: the writer clears BOTH spellings and re-emits the single form correct
 * for the target version, so the body never carries both at once. Version matters:
 * ANNIVERSARY, RELATED, and KIND are 4.0-only properties ez-vcard silently drops from
 * a 3.0 body, so a 3.0 card must carry them as the raw idiom (`itemN.X-ABDATE` +
 * labeled `Anniversary`, `X-ABRELATEDNAMES`, `X-ADDRESSBOOKSERVER-KIND`) or the value
 * is lost; at 4.0 they emit as the native property. IMPP is valid at both versions, so
 * IM handles regenerate natively either way. When such a field is unchanged it is not
 * touched, so its original spelling is preserved byte-faithful.
 */
class VCardWriter {

    /**
     * Serialize [contact] to vCard text at [version] ("3.0" or "4.0"; any other
     * value is treated as 3.0, matching the parser's version fallback). Defaults to
     * the contact's own parsed version.
     */
    fun write(contact: Contact, version: String = contact.version): String {
        val target = if (version.trim() == "4.0") VCardVersion.V4_0 else VCardVersion.V3_0
        val base = parseSingleBase(contact.rawVCard)
        val card = if (base != null) patch(base, contact, target) else generate(contact, target)
        return Ezvcard.write(card).version(target).prodId(false).go()
    }

    /** The original body, but only when it is a single parseable card worth patching. */
    private fun parseSingleBase(rawVCard: String): VCard? {
        if (rawVCard.isBlank()) return null
        val cards = runCatching { Ezvcard.parse(rawVCard).all() }.getOrNull() ?: return null
        return cards.singleOrNull()
    }

    /** Rewrite only the facets whose model value differs from the parsed original. */
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
        // The four dual-spelling facets (see the class KDoc for why): apply each only
        // when it changed, so an unchanged facet keeps its original spelling byte-faithful.
        if (contact.anniversary != original.anniversary) applyAnniversary(base, contact, target, groups)
        if (contact.relations != original.relations) applyRelations(base, contact, target, groups)
        if (contact.imHandles != original.imHandles) applyImHandles(base, contact)
        if (contact.kind != original.kind) applyKind(base, contact, target)
        return base
    }

    /** Build a fresh, valid card from every populated mapped field. */
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

    // --- Facet writers: each clears its own properties then re-adds from the model,
    //     so they are reused unchanged by both generate (on an empty card) and patch.

    private fun applyFormattedName(card: VCard, contact: Contact) {
        card.removeProperties(FormattedName::class.java)
        val fn = formattedNameFor(contact)
        if (fn.isNotBlank()) card.setFormattedName(fn)
    }

    /**
     * The FN value to write. FN is mandatory in a vCard (RFC 6350 §6.2.1, RFC 2426
     * §3.1.1) and strict servers reject a card that omits it — but a device contact
     * can carry only a phone or email and no name row at all, which leaves the mapped
     * [Contact.displayName] and [Contact.structuredName] blank. So when there is no
     * display name, fall back through the best available human identifier — the
     * structured name, then organization, nickname, first email, first phone — the
     * same order a contacts UI uses to label a nameless entry. Returns blank only for
     * a contact with no identifying field at all (which then legitimately emits no FN).
     */
    private fun formattedNameFor(contact: Contact): String {
        contact.displayName.trim().takeIf { it.isNotBlank() }?.let { return it }
        // Reuse the same structured-name → display form the parser derives displayName
        // from (VCardParser), so FN synthesis can never drift from that ordering.
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
            // N is optional (RFC 6350 §6.2.2), so a nameless contact carries no name
            // components — but some servers (iCloud among them) reject a card that omits
            // the N property outright with a 403, while accepting a structurally-present
            // N whose five components are all empty (`N:;;;;`). Emit that empty form: it
            // asserts no false name (the phone-derived FN remains the only label) yet
            // satisfies the property-present requirement, and re-parses back to an empty
            // structured name so the round-trip is unchanged.
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
     * The image type to stamp on a regenerated PHOTO. The neutral model's declared
     * [Photo.contentType] wins; when it is absent (a photo sourced from a device row,
     * which carries no MIME subtype), sniff the type from the inline bytes' magic
     * number so a PNG/GIF/WebP/HEIF is not blindly relabeled as JPEG. Falls back to
     * JPEG only when there is neither a declared type nor recognizable bytes.
     */
    private fun imageType(p: Photo): ImageType {
        p.contentType?.let { return ImageType.get(it, null, it) }
        p.data?.let { detectImageType(it)?.let { type -> return type } }
        return ImageType.JPEG
    }

    /**
     * Map the neutral [ImageFormat] sniff to an ez-vcard image type; null when unknown
     * (the caller then defaults to JPEG). ez-vcard 0.12.2 has no predefined WebP/HEIC
     * constant, so those are constructed with an explicit value/media-type/extension so
     * a 3.0 body carries TYPE=webp/heic and a 4.0 body carries data:image/webp|heic.
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
     * Whether the photo's regenerable CONTENT changed — its bytes or URL — ignoring
     * [Photo.contentType]. A device Contacts Photo row has no MIME column, so a photo
     * read back from a device round trip loses only its contentType; treating that as a
     * change would rewrite (and relabel) the PHOTO on every sync without ever converging.
     * A genuine bytes/URL edit, or adding/removing the photo, still regenerates.
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
        // Clear both spellings so an edit can never leave the old value in the other form.
        card.removeProperties(Anniversary::class.java)
        removeAnniversaryRawIdiom(card)
        val a = contact.anniversary ?: return
        if (target == VCardVersion.V4_0) {
            when {
                a.date != null -> card.setAnniversary(a.date)
                a.text != null -> card.setAnniversary(Anniversary(a.text))
            }
        } else {
            // At 3.0, carry the anniversary as Apple's itemN.X-ABDATE with an
            // "Anniversary" labeled group — the idiom the parser routes back into this field.
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
        // otherwise a producer yielding a differently-cased type would diff unequal every
        // sync and re-emit forever without converging.
        if (target == VCardVersion.V4_0) {
            contact.relations.forEach { r ->
                val prop = Related()
                prop.text = r.name
                r.type?.let { prop.types.add(RelatedType.get(it.lowercase())) }
                card.addProperty(prop)
            }
        } else {
            // At 3.0, carry each relation as Apple's raw itemN.X-ABRELATEDNAMES,
            // with the relation type in a labeled group.
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
        // IMPP is valid at both 3.0 (RFC 4770) and 4.0, so IM handles regenerate to the
        // native property at either version; clear the Apple raw X-SOCIALPROFILE spelling
        // too, so an edit routed in from that idiom does not leave the stale handle behind.
        card.removeProperties(Impp::class.java)
        removeAppleRawProps(card, "X-SOCIALPROFILE")
        contact.imHandles.forEach { im ->
            // Lower-case the protocol to match the parser's read-side normalization (it
            // lower-cases IMPP and X-SOCIALPROFILE service on read), so a mixed-case
            // protocol can't diff-unequal and re-emit every sync — same convergence guard
            // as relation type and KIND above.
            val protocol = im.protocol?.lowercase()
            val uri = if (protocol.isNullOrBlank()) im.handle else "$protocol:${im.handle}"
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
        // The parser lower-cases KIND on read (RFC 6350 §6.1.4 values are lower-case
        // canonical), so emit it lower-cased too, else a differently-cased value would
        // diff unequal and re-emit every sync without converging.
        val k = contact.kind?.lowercase() ?: return
        if (target == VCardVersion.V4_0) {
            card.setKind(Kind(k))
        } else {
            card.addExtendedProperty("X-ADDRESSBOOKSERVER-KIND", k)
        }
    }

    // --- Custom-label (itemN.X-ABLabel) grouping helpers.

    /** All `itemN`-style groups already present on the card, so re-emit never collides. */
    private fun collectGroups(card: VCard): MutableSet<String> =
        card.properties.mapNotNullTo(HashSet()) { it.group }

    /** Attach an Apple-style custom label to [prop] via a fresh, unused group. */
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
     * Remove every extended property named [propertyName] (an Apple raw idiom such as
     * `X-SOCIALPROFILE`) and any `X-ABLabel` left orphaned in its `itemN` group, so
     * re-applying the mapped property can't leave a contradictory second spelling.
     */
    private fun removeAppleRawProps(card: VCard, propertyName: String) {
        val removed = card.extendedProperties.filter { it.propertyName.equals(propertyName, ignoreCase = true) }
        if (removed.isEmpty()) return
        removed.forEach { card.removeProperty(it) }
        removeLabelsFor(card, removed)
    }

    /**
     * Remove only the anniversary raw idiom — an `X-ABDATE` whose `itemN` group is
     * labeled `Anniversary` — plus its label. A differently-labeled `X-ABDATE` (Apple's
     * generic custom-date form) is an unmapped property and must survive the edit, so the
     * removal is scoped by label rather than by property name.
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

    /** Remove every `X-ABRELATEDNAMES` (all are relations) plus any orphaned label. */
    private fun removeRelationRawIdiom(card: VCard) = removeAppleRawProps(card, "X-ABRELATEDNAMES")

    /** `itemN` group → unwrapped `X-ABLabel` text, mirroring the parser's resolution. */
    private fun labelsByGroup(card: VCard): Map<String?, String?> =
        card.extendedProperties
            .filter { it.propertyName.equals("X-ABLabel", ignoreCase = true) && it.group != null }
            .associate { it.group to unwrapAppleLabel(it.value) }

    /** Apple wraps custom labels as `_$!<Anniversary>!$_`; unwrap to the inner text. */
    private fun unwrapAppleLabel(raw: String?): String? {
        val v = raw?.trim() ?: return null
        return v.removePrefix("_\$!<").removeSuffix(">!\$_").trim().takeIf { it.isNotBlank() }
    }

    /** Wrap [text] in Apple's custom-label syntax `_$!<text>!$_` — the inverse of [unwrapAppleLabel]. */
    private fun wrapAppleLabel(text: String): String = "_\$!<$text>!\$_"

    /** Drop `X-ABLabel` raw props orphaned by removing their grouped host properties. */
    private fun removeLabelsFor(card: VCard, removed: List<VCardProperty>) {
        val orphaned = removed.mapNotNullTo(HashSet()) { it.group }
        if (orphaned.isEmpty()) return
        card.getExtendedProperties("X-ABLabel")
            .filter { it.group in orphaned }
            .forEach { card.removeProperty(it) }
    }
}
