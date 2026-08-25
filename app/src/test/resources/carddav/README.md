# CardDAV test fixtures + captured server behavior

Synthetic vCard fixtures for the contact-sync mapper/pull tests, plus the
empirically-observed normalization behavior of real servers. **All data here is
synthetic** — names, emails (`@example.test`), and phone numbers are fabricated;
no real account data is stored in this tree.

These back the CardDAV contact-sync mapper and pull path: the fixtures below pin the
vCard→ContactsContract mapping, and the server-behavior notes at the bottom pin the
wire quirks that path must survive.

## Fixtures (`fixtures/`)

| File | Purpose |
|------|---------|
| `kashcal_full_v3.vcf` | Fully-populated vCard 3.0: multi-typed EMAIL/TEL, Apple `itemN.X-ABLabel` grouping, ADR, ORG, TITLE, NICKNAME, NOTE, URL, `CATEGORIES`, an `X-`-prefixed custom prop, and the **3.0-syntax rich fields**: `BDAY`, an **anniversary** as `itemN.X-ABDATE`+`X-ABLabel="Anniversary"`, a **related name** (`X-ABRELATEDNAMES`), an **IMPP/social handle**, and a **URI `PHOTO`**. The mapper must map the standard props to the right ContactsContract mimetypes and preserve/ignore the rest without data loss. |
| `kashcal_full_v4.vcf` | vCard 4.0 equivalent with **native rich fields**: `ANNIVERSARY`, `IMPP`, `RELATED;TYPE=spouse`, `PREF=1`, `TEL;VALUE=uri:tel:`, `KIND:individual`, `urn:uuid:` UID, `CATEGORIES`. Exercises the 4.0 parse path. |
| `kashcal_no_uid_v3.vcf` | No `UID` property (RFC 6350 §6.7.6 cardinality `*1` — UID is optional). The pull must tolerate this: identity falls back to href, `SYNC1` stays blank, and a blank must never match another blank in reconciliation. |
| `kashcal_folding_and_escapes_v3.vcf` | A `NOTE` folded across the 75-octet boundary + escaped `;`/`,`/`&` in `ORG`/`ADR`. Forces correct line-unfolding and value de-escaping. |
| `kashcal_photo_inline_v3.vcf` | Inline base64 `PHOTO` (`ENCODING=b`, a 1×1 transparent PNG) — the non-URI photo shape, folded across the 75-octet boundary. Complements the URI `PHOTO` in the full fixture. |

### Field coverage → Android Contacts Provider mimetypes

The fixtures collectively exercise every property the mapper maps to a
`ContactsContract.CommonDataKinds` Data row. The mapping was **verified by parsing
these fixtures with ez-vcard 0.12.2** (the version the plan adds), which splits the
properties into two groups:

*Auto-typed by ez-vcard* — `N`/`FN`→`StructuredName`, `NICKNAME`→`Nickname`,
`EMAIL`→`Email`, `TEL`→`Phone`, `ADR`→`StructuredPostal`, `ORG`/`TITLE`→`Organization`,
`URL`→`Website`, `NOTE`→`Note`, `CATEGORIES`→`GroupMembership`, `PHOTO`→`Photo`
(URI + inline base64 both surface), `BDAY`→`Event TYPE_BIRTHDAY`, and the **4.0
native** `ANNIVERSARY`→`Event TYPE_ANNIVERSARY`, `IMPP`→`Im`, `RELATED`→`Relation`.

*Left as `RawProperty` — the mapper must hand-route these* (verified: ez-vcard does
NOT auto-type them): the **3.0 Apple forms** `itemN.X-ABDATE`+`X-ABLabel="Anniversary"`
→`Event TYPE_ANNIVERSARY`, `X-ABRELATEDNAMES`→`Relation`, `X-SOCIALPROFILE`→`Im`/raw,
and any other unmapped `X-` property (retained on the raw vCard for round-trip).

**The BDAY/ANNIVERSARY → `Event TYPE_*` mapping is a hard contract with shipped
code:** `ContactBirthdayRepository` / `ContactAnniversaryRepository` already query
the provider by `Event.TYPE`, so a synced date stored under any other mimetype/type
is invisible to them. The 3.0 anniversary is the sharp edge — since ez-vcard leaves
`X-ABDATE` as a raw property, a mapper that only handles the typed `Anniversary`
would silently drop every 3.0 anniversary, so the mapper must handle the raw
`X-ABDATE` form as well as the typed `Anniversary` property.

## Captured server normalization (observed via read-only + synthetic PUT/GET probes)

These are the load-bearing quirks the mapper must survive. Captured by PUTting
`kashcal_full_v3.vcf` / `kashcal_full_v4.vcf` to each server and fetching back;
the synthetic fixtures were deleted from each server afterward.

### Two-step discovery is mandatory
`addressbook-home-set` returns **404 at the account root URL** on Baikal, SOGo,
Cyrus, and Nextcloud — you must PROPFIND the **principal** URL (from
`current-user-principal`) to get the home-set. iCloud additionally hands the
home-set back on a **partition host** (`pNN-contacts.icloud.com`), distinct from
the `contacts.icloud.com` entry point. So discovery is always: root →
`current-user-principal` → PROPFIND principal → `addressbook-home-set` → PROPFIND
home (Depth:1) → address-book collections.

### `supported-address-data` advertises more than the server actually stores
Advertised versions per server:
- **iCloud** — serves existing cards as `VERSION:3.0` (PRODID `-//Apple Inc.//iOS 18.6.2//EN`).
- **Baikal** — `text/vcard` 3.0 + 4.0, `application/vcard+json` 4.0.
- **Cyrus** — `text/vcard` 3.0, `text/directory` 3.0, `text/vcard` 4.0.

### **A server may store a DIFFERENT version than you PUT** (the critical one)
PUTting an identical vCard **4.0** body:

| Server | Stored/served as | `KIND` (4.0-only) | `PREF=1` | `tel:` URI |
|--------|------------------|-------------------|----------|------------|
| **iCloud** | 4.0 (unchanged) | kept | kept | kept |
| **Baikal** | **downgraded to 3.0** | dropped | rewritten to `PREF` param | kept |
| **Cyrus** | **downgraded to 3.0** | dropped | mangled (`PREF;PREF=1`) | stripped to bare number |

**Consequence for the design:** the mapper must read `VERSION:` from the
**returned body on every pull** and parse accordingly. It must never assume the
version it requested (or the version `supported-address-data` advertised) is the
version it receives. The persisted `address_books.vcard_version` records the
negotiated *request* preference; the per-object parse is driven by the body.

### Faithful round-trip on the sync path
On an immediate PUT→GET of the 3.0 fixture, **iCloud, Baikal, and Cyrus all
preserved** property order, the `item1.X-ABLabel` grouping, and the `X-CUSTOM-PROP`
custom property, and did **not** inject `REV`/`PRODID` on read-back. The Apple
ORGANIZER/attendee-style rewriting seen on the CalDAV scheduling path does **not**
appear on the CardDAV object PUT path here — server-side rewriting, where it
happens, is reconciliation over time, not a PUT-path transform.
