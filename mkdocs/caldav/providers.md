# Supported Providers

| Provider | Status | Notes |
|----------|--------|-------|
| iCloud | Tested | Requires app-specific password |
| Nextcloud | Tested | Full support |
| Radicale | Tested | Self-hosted, Python |
| Baikal | Tested | Self-hosted, PHP |
| Stalwart | Tested | Self-hosted, Rust |
| FastMail | Tested | Full support |
| Any CalDAV | Should work | RFC 4791 compliant |

## Provider Quirks

KashCal handles provider-specific behaviors via `CalDavQuirks` interface.

### iCloud Quirks
- Non-prefixed XML namespaces
- CDATA-wrapped calendar-data
- Regional redirect handling
- Skips inbox/outbox/tasks collections

### Standard CalDAV
Most servers work with default settings. Toggle "Trust Self-Signed Certificates" for self-signed SSL.
