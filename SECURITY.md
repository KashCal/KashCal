# Security Policy

## Reporting a Vulnerability

**Please use [GitHub Security Advisories](https://github.com/KashCal/KashCal/security/advisories/new)** to report vulnerabilities privately.

Do NOT open a public issue or disclose the vulnerability before it has been addressed.

### What to Include

- Description of the vulnerability
- Steps to reproduce
- Affected component (credential storage, sync, intent handling, etc.)
- Potential impact
- Suggested fix (if any)

### What to Expect

1. **Acknowledgment** within a few days
2. **Assessment** of severity
3. **Fix** for confirmed vulnerabilities
4. **Coordinated disclosure** once resolved — reporters credited in release notes unless they prefer anonymity

## Scope

This security policy covers the KashCal Android application and this repository.

KashCal handles sensitive data including:

- **Sync credentials** — iCloud app-specific passwords, CalDAV server passwords, stored via Android Keystore (AES-256-GCM)
- **Calendar data** — Event titles, descriptions, locations
- **Contact data** — Birthday information from device contacts (when enabled)
- **Network traffic** — CalDAV sync over HTTPS

Out of scope:
- Third-party services (iCloud, CalDAV servers) — report to the respective projects
- Issues in dependencies — report to the respective projects

## Supported Versions

Security fixes are applied to the latest release only.

## Security Best Practices for Users

- Keep KashCal updated to the latest version
- Use strong, unique app-specific passwords for sync accounts
- Review calendar and contact permissions granted to the app
