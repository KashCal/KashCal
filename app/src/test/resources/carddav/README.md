# CardDAV test fixtures and captured server behavior

Synthetic vCard fixtures for the contact-sync parser, mapper and pull tests, plus the
normalization behavior observed on real servers. All data here is synthetic: names,
emails (`@example.test`) and phone numbers are made up, and no real account data is
stored in this tree.

The fixtures pin the vCard parse (`VCardParser` in `vcard-core`) and the mapping onto
ContactsContract rows (`VCardContactMapper`). The server notes at the bottom record the
wire quirks the CardDAV path must survive.

## Fixtures (`fixtures/`)

| File | Purpose |
|------|---------|
| `kashcal_full_v3.vcf` | Fully populated vCard 3.0: multi-typed EMAIL/TEL, Apple `itemN.X-ABLabel` grouping, ADR, ORG, TITLE, NICKNAME, NOTE, URL, `CATEGORIES`, an `X-`-prefixed custom property, and the 3.0-syntax rich fields: `BDAY`, an anniversary as `itemN.X-ABDATE` with `X-ABLabel="Anniversary"`, a related name (`X-ABRELATEDNAMES`), an `IMPP` and an `X-SOCIALPROFILE` handle, and a URI `PHOTO`. The standard properties must land on the right ContactsContract mimetypes and the rest must be kept or ignored without data loss. |
| `kashcal_full_v4.vcf` | vCard 4.0 equivalent with native rich fields: `ANNIVERSARY`, `IMPP`, `RELATED;TYPE=spouse`, `PREF=1`, `TEL;VALUE=uri:tel:`, `KIND:individual`, `urn:uuid:` UID, `CATEGORIES`. Exercises the 4.0 parse path. |
| `kashcal_no_uid_v3.vcf` | No `UID` property (RFC 6350 §6.7.6 cardinality `*1`: UID is optional). The pull must tolerate this: identity is the href (`SOURCE_ID`), `SYNC1` stays blank, and a blank UID never matches another blank one. |
| `kashcal_folding_and_escapes_v3.vcf` | A `NOTE` folded across the 75-octet boundary, plus escaped `;`, `,` and `&` in `ORG` and `ADR`. Forces line unfolding and value de-escaping. |
| `kashcal_photo_inline_v3.vcf` | Inline base64 `PHOTO` (`ENCODING=b`, a 1×1 transparent PNG), the non-URI photo shape, folded across the 75-octet boundary. Complements the URI `PHOTO` in the full fixture. |
| `kashcal_empty_fn_v3.vcf` | No `FN`: the display name is derived from `N`. |
| `kashcal_field_fidelity_v3.vcf` | Multi-value `N` components, a two-part `ORG`, `TITLE`, `ROLE`, the `X-PHONETIC-*` reading aids, and custom `X-ABLabel` labels on EMAIL, TEL, ADR and URL. |
| `kashcal_field_fidelity_v4.vcf` | The same fields plus `BDAY` and an `X-ABDATE` anniversary. Despite its name the body is `VERSION:3.0`. `MultiServerCardDavFieldFidelityTest` round-trips it through each server. |
| `kashcal_partial_bday_v4.vcf` | Year-less `BDAY:--0415` and `ANNIVERSARY:--0620` (RFC 6350 §4.3.1 truncated dates). |
| `kashcal_seed_0001.vcf` | Minimal seed contact (FN, one email, one phone) that `MultiServerCardDavReadTest` and `MultiServerCardDavZohoProbeTest` PUT and read back. |
| `kashcal_seed_photo_url_0002.vcf` | Seed contact with a URI `PHOTO`, for the server photo probes. |
| `kashcal_seed_photo_inline_0003.vcf` | Seed contact with an inline base64 `PHOTO`, for the server photo probes and `MultiServerContactPhotoFetchTest`. |

### Field coverage and Android Contacts Provider mimetypes

Together the fixtures exercise every property `VCardContactMapper` maps to a
`ContactsContract.CommonDataKinds` Data row. The mapping was checked by parsing these
fixtures with ez-vcard 0.12.2 (the version `vcard-core` pins), which splits the
properties into two groups:

*Typed by ez-vcard*: `N`/`FN`→`StructuredName`, `NICKNAME`→`Nickname`,
`EMAIL`→`Email`, `TEL`→`Phone`, `ADR`→`StructuredPostal`, `ORG`/`TITLE`→`Organization`,
`URL`→`Website`, `NOTE`→`Note`, `CATEGORIES`→`GroupMembership`, `PHOTO`→`Photo`
(URI and inline base64 both surface), `BDAY`→`Event TYPE_BIRTHDAY`, and the 4.0
native `ANNIVERSARY`→`Event TYPE_ANNIVERSARY`, `IMPP`→`Im`, `RELATED`→`Relation`.

*Left as `RawProperty` by ez-vcard*, so `VCardParser` routes them by hand: the 3.0 Apple
forms `itemN.X-ABDATE` with `X-ABLabel="Anniversary"`→`Event TYPE_ANNIVERSARY`,
`X-ABRELATEDNAMES`→`Relation`, and `X-SOCIALPROFILE`→`Im`. Any other unmapped `X-`
property stays on the raw vCard for the round trip.

The BDAY/ANNIVERSARY→`Event TYPE_*` mapping is a hard contract with the contact event
calendars: `BaseContactEventRepository`, behind `ContactBirthdayRepository` and
`ContactAnniversaryRepository`, queries the provider by `Event.TYPE`, so a synced date
stored under any other mimetype or type is invisible to them. The 3.0 anniversary is the
sharp edge: ez-vcard leaves `X-ABDATE` as a raw property, so a parser that handled only
the typed `Anniversary` would silently drop every 3.0 anniversary.

## Captured server normalization (observed via read-only and synthetic PUT/GET probes)

These are the quirks the CardDAV path must survive. Captured by PUTting
`kashcal_full_v3.vcf` and `kashcal_full_v4.vcf` to each server and fetching them back;
the synthetic fixtures were deleted from each server afterward.

### Two-step discovery is mandatory
`addressbook-home-set` returns 404 at the account root URL on Baikal, SOGo, Cyrus, and
Nextcloud; you must PROPFIND the principal URL (from `current-user-principal`) to get the
home-set. iCloud also hands the home-set back on a partition host
(`pNN-contacts.icloud.com`), distinct from the `contacts.icloud.com` entry point. So
discovery runs: `/.well-known/carddav` or the root → `current-user-principal` → PROPFIND
principal → `addressbook-home-set` → PROPFIND home (Depth:1) → address-book collections.
`ContactPullStrategy` first tries the home-set step on the stored principal when it has the
CardDAV base URL's scheme, host and port.

### `supported-address-data` advertises more than the server stores
Advertised versions per server:
- iCloud: serves existing cards as `VERSION:3.0` (PRODID `-//Apple Inc.//iOS 18.6.2//EN`).
- Baikal: `text/vcard` 3.0 and 4.0, `application/vcard+json` 4.0.
- Cyrus: `text/vcard` 3.0, `text/directory` 3.0, `text/vcard` 4.0.

### A server may store a different version than you PUT
PUTting an identical vCard 4.0 body:

| Server | Stored/served as | `KIND` (4.0-only) | `PREF=1` | `tel:` URI |
|--------|------------------|-------------------|----------|------------|
| iCloud | 4.0 (unchanged) | kept | kept | kept |
| Baikal | downgraded to 3.0 | dropped | rewritten to `PREF` param | kept |
| Cyrus | downgraded to 3.0 | dropped | mangled (`PREF;PREF=1`) | stripped to bare number |

So the parse reads `VERSION:` from the returned body on every pull and never assumes the
version it requested (or the version `supported-address-data` advertised) is the version
it receives. `address_books.vcard_version` stores the negotiated version, which the app
requests `address-data` at and writes pushed cards in; each object's parse follows its own
body.

### Faithful round-trip on the sync path
On an immediate PUT→GET of the 3.0 fixture, iCloud, Baikal, and Cyrus all preserved
property order, the `item1.X-ABLabel` grouping, and the `X-CUSTOM-PROP` custom property,
and did not inject `REV`/`PRODID` on read-back. The ORGANIZER and attendee rewriting
seen on the CalDAV scheduling path does not appear on the CardDAV object PUT path here.
Server-side rewriting, where it happens, is reconciliation over time, not a PUT-path
transform.
