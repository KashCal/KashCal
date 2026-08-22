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
 * ## Known limitation (preserve-only facets)
 *
 * A handful of fields have two on-the-wire spellings — a native 4.0 property and a
 * 3.0 raw-property idiom the parser hand-routes: the anniversary
 * (`itemN.X-ABDATE`), relations (`X-ABRELATEDNAMES`), social/IM handles
 * (`X-SOCIALPROFILE`), and the group marker (`X-ADDRESSBOOKSERVER-KIND`). In patch
 * mode these are **preserved verbatim but not regenerated**, so a rare isolated
 * edit to one of them is not applied to the written body. Every field a user edits
 * in practice — name, phone, email, address, organization, title, role, note,
 * nickname, url, categories, birthday, photo, uid — is regenerated normally.
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
        // Preserve-only (dual native/raw spelling): anniversary, relations, imHandles, kind.
        return base
    }

    /** Build a fresh, valid card from every populated mapped field. */
    private fun generate(contact: Contact, target: VCardVersion): VCard {
        val card = VCard()
        val groups = HashSet<String>()
        applyUid(card, contact)
        applyKind(card, contact)
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
        applyAnniversary(card, contact)
        applyRelations(card, contact)
        applyImHandles(card, contact)
        return card
    }

    // --- Facet writers: each clears its own properties then re-adds from the model,
    //     so they are reused unchanged by both generate (on an empty card) and patch.

    private fun applyFormattedName(card: VCard, contact: Contact) {
        card.removeProperties(FormattedName::class.java)
        if (contact.displayName.isNotBlank()) card.setFormattedName(contact.displayName)
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
     * number so a PNG/GIF is not blindly relabeled as JPEG. Falls back to JPEG only
     * when there is neither a declared type nor recognizable bytes.
     */
    private fun imageType(p: Photo): ImageType {
        p.contentType?.let { return ImageType.get(it, null, it) }
        p.data?.let { detectImageType(it)?.let { type -> return type } }
        return ImageType.JPEG
    }

    /** Recognize common raster formats from their leading magic bytes; null if unknown. */
    private fun detectImageType(bytes: ByteArray): ImageType? = when {
        bytes.startsWith(0x89, 0x50, 0x4E, 0x47) -> ImageType.PNG
        bytes.startsWith(0xFF, 0xD8, 0xFF) -> ImageType.JPEG
        bytes.startsWith(0x47, 0x49, 0x46) -> ImageType.GIF
        else -> null
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.withIndex().all { (i, b) -> this[i] == b.toByte() }

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

    private fun applyAnniversary(card: VCard, contact: Contact) {
        card.removeProperties(Anniversary::class.java)
        val a = contact.anniversary ?: return
        when {
            a.date != null -> card.setAnniversary(a.date)
            a.text != null -> card.setAnniversary(Anniversary(a.text))
        }
    }

    private fun applyRelations(card: VCard, contact: Contact) {
        card.removeProperties(Related::class.java)
        contact.relations.forEach { r ->
            val prop = Related()
            prop.text = r.name
            r.type?.let { prop.types.add(RelatedType.get(it)) }
            card.addProperty(prop)
        }
    }

    private fun applyImHandles(card: VCard, contact: Contact) {
        card.removeProperties(Impp::class.java)
        contact.imHandles.forEach { im ->
            val uri = if (im.protocol.isNullOrBlank()) im.handle else "${im.protocol}:${im.handle}"
            val prop = runCatching { Impp(uri) }.getOrNull()
                ?: runCatching { Impp(im.protocol ?: "", im.handle) }.getOrNull()
            prop?.let { card.addProperty(it) }
        }
    }

    private fun applyKind(card: VCard, contact: Contact) {
        card.removeProperties(Kind::class.java)
        contact.kind?.let { card.setKind(Kind(it)) }
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
        card.addExtendedProperty("X-ABLabel", "_\$!<$label>!\$_").group = group
    }

    private fun allocateGroup(groups: MutableSet<String>): String {
        var i = 1
        while (groups.contains("item$i")) i++
        val group = "item$i"
        groups.add(group)
        return group
    }

    /** Drop `X-ABLabel` raw props orphaned by removing their grouped host properties. */
    private fun removeLabelsFor(card: VCard, removed: List<VCardProperty>) {
        val orphaned = removed.mapNotNullTo(HashSet()) { it.group }
        if (orphaned.isEmpty()) return
        card.getExtendedProperties("X-ABLabel")
            .filter { it.group in orphaned }
            .forEach { card.removeProperty(it) }
    }
}
