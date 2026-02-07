# Search

KashCal uses FTS4 full-text search for fast event lookup.

## Using Search

1. Tap search icon in toolbar
2. Type search query
3. Results appear instantly
4. Tap result to view event

## Search Features

### Text Matching
- Searches title, location, description
- Prefix matching ("meet" finds "meeting")
- Case-insensitive

### Date Filtering
- Any time (default)
- Today, Tomorrow
- This week, Next week
- This month, Next month
- Custom date range

### Options
- Include past events toggle

## Technical Details

- FTS4 virtual table (10-100x faster than LIKE)
- Room auto-syncs via triggers
- Max 100 results per query
- Results sorted by next occurrence
