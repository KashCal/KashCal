# Adding Accounts

KashCal supports multiple CalDAV account types for calendar synchronization.

## iCloud

### Prerequisites

iCloud requires an **app-specific password** (not your Apple ID password).

### Generate App-Specific Password

1. Go to [appleid.apple.com](https://appleid.apple.com)
2. Sign in with your Apple ID
3. Navigate to **App-Specific Passwords**
4. Click **Generate Password**
5. Name it "KashCal" and click Create
6. Copy the 16-character password

### Add to KashCal

1. Open KashCal Settings
2. Tap **Add iCloud Account**
3. Enter your Apple ID email
4. Paste the app-specific password
5. Tap **Sign In**

KashCal will discover your calendars automatically.

## Nextcloud

### Add Account

1. Open KashCal Settings
2. Tap **Add CalDAV Account**
3. Enter server URL: `https://your-nextcloud.com/remote.php/dav`
4. Enter username and password
5. Tap **Connect**

### Server URL Format

```
https://your-nextcloud.com/remote.php/dav
```

## Self-Hosted CalDAV

KashCal works with any RFC 4791 compliant CalDAV server.

### Tested Servers

| Server | Status | Notes |
|--------|--------|-------|
| Radicale | Tested | Python-based, lightweight |
| Baikal | Tested | PHP-based |
| Stalwart | Tested | Rust-based, all-in-one |

### Generic Setup

1. Open KashCal Settings
2. Tap **Add CalDAV Account**
3. Enter your server URL (usually ends with `/dav` or `/caldav`)
4. Enter credentials
5. Toggle **Trust Self-Signed Certificates** if using self-signed SSL
6. Tap **Connect**

### Troubleshooting

**Discovery fails:**

- Check server URL format
- Verify `.well-known/caldav` redirect is configured
- Try direct calendar URL instead of discovery

**Authentication errors:**

- Double-check username/password
- Some servers require email as username
- Check server logs for details

## Multiple Accounts

KashCal supports multiple accounts of the same type:

- Multiple iCloud accounts (different Apple IDs)
- Multiple CalDAV servers
- Mix of iCloud + CalDAV + local

Each account's calendars appear in separate groups in the calendar picker.

## Account Management

### Disable Account

1. Settings → Accounts
2. Tap account name
3. Toggle **Enabled** off

Disabled accounts don't sync but data is preserved.

### Remove Account

1. Settings → Accounts
2. Tap account name
3. Tap **Sign Out**

**Warning:** Removing an account deletes all associated calendars and events from the device.

## Calendar Visibility

After adding an account:

1. Settings → Calendars
2. Toggle checkboxes to show/hide calendars
3. Set default calendar for new events
