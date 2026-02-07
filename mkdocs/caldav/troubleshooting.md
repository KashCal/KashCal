# Troubleshooting

## Common Issues

### Sync Not Working

1. Check internet connection
2. Verify account credentials
3. Settings → Sync History for error details
4. Try "Force Full Sync" in Settings

### Authentication Errors

**iCloud:**
- Ensure using app-specific password
- Generate new password at appleid.apple.com

**CalDAV:**
- Some servers require email as username
- Check server logs

### Events Not Appearing

1. Check calendar visibility (Settings → Calendars)
2. Ensure calendar is synced (pull to refresh)
3. Check date range (far future may need expansion)

### Conflicts

When same event is edited on multiple devices:

1. KashCal uses "server wins" by default
2. After 3 failed syncs, local changes are abandoned
3. User is notified via notification

## Debug Information

Settings → Sync History shows:
- Last sync timestamp
- Events pulled/pushed
- Parse errors
- Network failures

## Force Full Sync

Clears all sync tokens and re-downloads everything:

1. Settings → Force Full Sync
2. Wait for sync to complete
3. Check Sync History for results
