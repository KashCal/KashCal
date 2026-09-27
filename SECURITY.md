# Security Policy

## Reporting a Vulnerability

Please use [GitHub Security Advisories](https://github.com/KashCal/KashCal/security/advisories/new) to report vulnerabilities privately.

Don't open a public issue or disclose the vulnerability before it has been addressed.

### What to Include

- Description of the vulnerability
- Steps to reproduce
- Affected component (credential storage, sync, intent handling, and so on)
- Potential impact
- Suggested fix (if any)

### What to Expect

1. **Acknowledgment** within a few days
2. **Assessment** of severity
3. **Fix** for confirmed vulnerabilities
4. **Coordinated disclosure** once resolved. Reporters are credited in the release notes unless they prefer anonymity.

## Scope

This security policy covers the KashCal Android application and this repository.

KashCal handles sensitive data including:

- **Sync credentials**: iCloud app-specific passwords and CalDAV and CardDAV server passwords, encrypted under an Android Keystore key (AES-256-GCM)
- **Calendar data**: event titles, descriptions, locations
- **Contact data**: birthdays and anniversaries read from device contacts (when turned on), and contacts synced over CardDAV
- **Network traffic**: CalDAV and CardDAV sync over HTTPS, or plain http for an account set up with an http:// address

Out of scope:
- Third-party services (iCloud, CalDAV servers): report to the respective projects
- Issues in dependencies: report to the respective projects

## Supported Versions

Security fixes are applied to the latest release only.

## Security Best Practices for Users

- Keep KashCal updated to the latest version
- Use strong, unique app-specific passwords for sync accounts
- Review calendar and contact permissions granted to the app
