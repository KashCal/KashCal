## [2026.08.09]

### Everything in this release

- Fixed a deleted event not disappearing from KashCal after another client removed it on the server; when the server echoes the event's address with the `@` in its filename written as `%40`, the deletion is now matched and applied instead of silently skipped, #333
- Changed pull-to-refresh to sync CardDAV contacts as well as calendar events, so a swipe-down picks up contacts added on the server right away instead of waiting for the next periodic sync
- Fixed contact sync skipping past a contact it could not read and never coming back to it; a contact whose card failed to parse no longer gets stranded, and the next sync retries it

## [2026.08.08]

### Everything in this release

- Fixed CardDAV contact sync bringing over almost no contacts and no photos once installed; every vCard was silently failing to parse on release builds, so an account that looked fine in testing landed a nearly empty address book on the phone, #10
- Fixed real calendars and address books being hidden when their path or account name merely contained a word like inbox, outbox, or tasks; only genuine scheduling and task collections are skipped now
- Added drilling into Day view by tapping a day-column header in Week or 3-Day view, with a back press returning to the exact week or span you came from, contributed by @Wqrld
- Changed the Quick View buttons for Edit, Delete, Duplicate, and Share to icons so they no longer wrap in longer languages, contributed by @Wqrld
- Shortened the all-day overflow badge in Week and 3-Day view to +N with a larger tap target, contributed by @Wqrld
- Enlarged the week-view day-header letter and date number to match the 3-day header
- Changed the delete-confirmation buttons to a single centered line and relabeled the editor's "Confirm Delete" to "Confirm" to match Quick View

## [2026.08.07]

Your calendar account has always known your contacts existed. Now it can bring them with it.

Turn on the new Contacts switch for a CardDAV account and the contacts living on your server land on your phone: names, numbers, emails, photos, the phonetic spellings and job titles and custom labels you filed them under. They show up in your phone's own address book, next to everyone else, and they keep themselves current every time the account syncs.

To switch it on, open the account from your settings and look under the calendar sync toggle for a Contacts row (it appears for accounts that carry contacts, like iCloud and Nextcloud). Flip it, grant the contacts permission when asked, and the first sync fills in the rest. Flip it back off and those contacts leave your phone as cleanly as they arrived.

One direction only, for now. This first phase is a mirror: it reads your server and writes to your phone, never the reverse, so nothing you do on the device reaches back and rewrites the contacts on your account. Two-way editing is coming in a later release; read-only is the safe half to ship first, and the half worth having on day one.

### Everything in this release

- Added read-only contact sync for CardDAV accounts: a Contacts toggle in each account's settings (shown for contact-carrying providers) mirrors that account's server contacts onto your phone as a one-way, read-only sync for now, and removes them when you turn it off
- Added contact detail to the mirror: names with phonetics and multiple values, phone numbers and emails with their custom labels, job title and role, categories, and contact photos fetched from the server
- Added an app permissions screen, reached from your account, listing every permission KashCal uses with a direct link to each one's system settings
- Fixed a pure-black or pure-white widget accent seed rendering as a muddy gray; it now paints a crisp black panel with white text, or white panel with black text, in both light and dark mode, while event colors and the dimming of past events stay intact
- Fixed exported `.ics` files and share-card filenames dropping non-ASCII characters from the event title; accented and non-Latin names are kept
- Fixed exported `.ics` files not escaping carriage returns and reminder text, so events with multi-line notes or alarms round-trip correctly per the calendar spec
- Localized all of the above into every supported language

## [2026.08.03]

Your widgets used to borrow the app's whole look whether you liked it or not. Now they have a say of their own.

Open your account settings and scroll to Make it yours: alongside the app's own theme and accent, there are now two rows for your widgets. Leave them on Follow app and nothing changes; each widget keeps wearing whatever the app wears, down to the light or dark face and the accent you picked. But pin the widget theme to Light or Dark and it holds that face on your home screen no matter what the app or the phone is doing, and give it an accent of its own and it carries that color while the app keeps its. A calendar on your wall can finally read differently from the one in your hand.

This one came from the community: the independent widget appearance landed as a contribution from langfeld (#315). The rest of the release is quieter work on the parts you don't see until they bite: a whole calendar all but vanishing after a sync that reported no error, oddly-numbered repeat rules that used to fail outright, and local-network calendar feeds asking permission the way Android 17 now expects.

### Everything in this release

- Added independent light/dark theming for home-screen widgets, in account settings under Make it yours, with a Theme row offering Follow app, Light, or Dark; Follow app tracks the app's own face, and a fixed choice holds regardless of the app or device setting, contributed by langfeld in #315
- Added an independent accent color for widgets, so a widget can carry its own color while the app keeps its; the accent's follow-the-app option now reads "Follow app"
- Changed the widget personalization rows to read simply "Theme" and "Accent"
- Fixed a SOGo account syncing without any error but showing almost none of your events, often just a single recurring one; the calendar query sent a far-future cutoff that servers with 32-bit time limits silently choke on, which dropped everything else, so the query is now open-ended and your events come back, #326
- Fixed monthly and yearly repeat rules with a two-digit or signed week number failing to expand instead of showing their events
- Fixed a single edited occurrence of a repeating event occasionally attaching to the wrong day when its date carried a time zone offset
- Added the local-network permission Android 17 requires for calendar feeds on your home network, requested through an inline banner on the subscription screen rather than a blocking dialog
- Localized all of the above into every supported language

## [2026.08.02]

### Everything in this release

- Fixed deleting a server-hosted event (one carrying an organizer, as Fastmail and iCloud events do) failing and freezing the event's link when the server had quietly changed the event since your last sync; the delete now refetches the current version and retries once instead of getting stuck, addressing the delete half of #311
- Changed the automatic title emoji to never decorate sensitive events: titles mentioning a funeral, memorial, hospice, surgery, biopsy, chemo, divorce, custody, a hearing, or a layoff are left plain
- Fixed the automatic emoji matching whole words only, so a word like "test" or "bar" sitting inside an unrelated title no longer picks up a stray emoji; "bar exam" now reads as books rather than beer
- Fixed a synced event that already carries its own emoji getting a second one stacked in front; a title that already contains an emoji is left as is, and ordinary non-Latin text is no longer mistaken for one
- Fixed text-style symbols like `™`, `✓`, and `➡` being treated as emoji, so a title using them still gets a matching emoji
- Fixed the all-day label in the day, 3-day, and week views overflowing its column; it now shrinks to fit
- Fixed a drag or tap in the week view acting on the wrong event after you reschedule one, so the gesture stays bound to the event you touched

## [2026.07.31]

You could already tag an event. Now the tag is yours to keep.

Until now a tag was a word you sprinkled on with a #hashtag and mostly forgot. You could see it, but you couldn't manage it: no way to recolor it, no way to fix a typo across every event at once, no way to retire one you'd stopped using. And it lived only in KashCal, so the same event pulled from the calendar already on your phone sat there bare.

This release gives tags a home. There's a manager now, reached from your account, where each tag has a color you pick, a name you can change, and a delete when you're done with it. Rename one and it doesn't just fix the label on your screen; it rewrites the tag across your events and pushes the new name up to your CalDAV account, so your other devices catch up instead of drifting. Removing a tag from the manager only clears the label from the list, never the events themselves, and the screen says so if you ask.

The reach grew too. Events from your device calendar can carry tags now, the same as any other: add and remove them in the editor, see them as chips on the card and in the quick view, with the same colors and suggestions as the rest.

A label you can't recolor, rename, or take with you isn't really yours. This one is.

### Everything in this release

- Added a tag manager, reached from your account, where each tag has a color you choose, a name you can change, and a delete; renaming a tag onto a name that already exists merges the two
- Changed a tag rename to propagate: renaming updates your KashCal and CalDAV events carrying the tag and re-uploads them to your CalDAV account, so the new name reaches your other devices instead of staying only on this one
- Added tags to device-calendar events: add and remove them in the editor, see read-only chips in the quick-view sheet and on cards, with tag colors and suggestions shared with your other events and a round-trip through the standard categories field
- Added a configurable all-day row count in the day, 3-day, and week views: a chevron expands the all-day strip to show every event instead of one-plus-a-count, and the choice persists across restarts and travels in a settings backup (#204)
- Fixed the new-event button opening the full form instead of Quick Add in the day, 3-day, and week views when Smart event add is on (#320)
- Fixed the monochrome themed launcher icon showing a blank shape; it now carries the calendar glyph, scaled to sit inside the adaptive-icon safe zone, so it matches the main icon on themed home screens (#317)
- Removed the Insights row from the account hub, since it is already reachable from the navigation drawer

## [2026.07.29]

Your widgets have been speaking in shorthand.

A list widget only gets so many pixels, so every event on it had learned to say everything on one line: a color, a start time, a title, and then it ran out of room. Which is fine until the thing you actually wanted to know was when it ends. A block that starts at nine could wrap up at nine-thirty or swallow the whole afternoon, and the widget wasn't telling. You opened the app to find out.

Now the row can stretch its legs. A new **Detailed widget rows** switch, sitting in Settings next to the widget event limit, gives each event two lines instead of one: the title on top with the whole width to itself, and underneath it the full span, nine-thirty to ten-thirty, so the shape of your day is legible from the home screen. All-day events say so. An event that started yesterday and is still going shows where it lands, with the date when the finish line is another day off. The time reads in whatever clock you set inside KashCal, twelve or twenty-four, not whatever the phone happens to prefer.

Leave the switch off and nothing changes: rows stay compact and single-line, the way they were, so a packed calendar still fits. It applies to the agenda, the week, and the upcoming list alike, and the detailed row sits right at a comfortable tap size.

Two lines, when one was never quite enough.

### Everything in this release

- Added a **Detailed widget rows** toggle in Settings that switches the agenda, week, and upcoming list widgets between a compact single-line row and a detailed two-line row showing each event's start and end time
- Changed the detailed row's time to follow the in-app 12/24-hour setting rather than only the device clock, so it matches the rest of the app
- Fixed the widgets not honoring genuine Material You colors in automatic mode; they now render on the device's real dynamic palette instead of a reseeded tinted scheme, matching the launcher and system UI
- Fixed the Quick Add dialog letting the keyboard and system bars overlap its card in landscape or at large font scales; the card now clears the insets and scrolls so Save stays reachable

## [2026.07.26]

Two fixes this release, both for things that talked over you.

Import a big .ics file and the old Import button gave no sign of life. So you tapped it again, the way anyone leans on an elevator button that won't admit it heard you. It heard you the first time. It heard you all three times, and it imported your two hundred events once per tap, until your calendar read like a stutter and every dentist appointment came in triplicate. Now only the first tap counts. The button turns into a spinner and says Importing while it works, and the rest of your taps hit a closed door. The Settings importer and the share-to-KashCal path both learned to say when they're busy.

The other fix was in the dark. A widget header, on a dark theme, glowed like a bright pastel bar someone forgot to turn off, the one thing in the room still wide awake. It sleeps now. The header settled into a dark tinted tone that matches the body, so the widget is one calm panel again, and the text stays readable against any accent you pick.

### Everything in this release

- Fixed importing an `.ics` file creating duplicate events when the Import button was tapped more than once during a slow import; the button now latches on the first tap and shows an `Importing…` spinner (#309)
- Changed the import flow so both the Settings importer and the share-to-KashCal path share the single-tap behavior
- Fixed a widget header rendering as a bright pastel bar on dark themes, so it now uses a dark tinted tone that matches the widget body while keeping header text legible

## [2026.07.25]

Every KashCal widget has to sell itself from behind glass.

Before you add one, you meet it in the system's widget picker, that showroom of previews. Until now, KashCal sent all five of its widgets to the audition in the same outfit: a blank panel, a spinner, and the app name. The agenda, the month, the week, the date, the upcoming list, five siblings in one gray coat, none willing to say which one they were. Picking a widget meant adding it, squinting, guessing wrong, removing it, and going again.

They introduce themselves now. Each widget previews as what it is: the agenda shows a day with a couple of events on it, the month shows a grid, the week its strip, the date its day, the upcoming its list. The previews run the same code the live widget runs, so the model in the showroom is the one that gets delivered, and the sample text is translated like everything else.

The month widget changed the most. It wears single-letter day names across the top, an optional week-number column down the side, and marks today with a solid filled circle instead of a faint tint. The circle grows with your font size, so it stays round from default text up to the largest. And it listens: change your first day of the week or turn week numbers on, and it updates without a restart. The headers match heights now, the plus sign reads clearly and is easy to hit, and the body lets your accent color tint through instead of sitting on flat gray.

Two smaller things. The view menu runs shortest to longest now, a single day up through the whole year, thanks to a pull request from [@az0ran](https://github.com/az0ran). And Settings and the profile hub slide between each other instead of cutting.

Five widgets that answer the door when you knock.

### Everything in this release

- Added per-widget previews in the system widget picker on Android 15 and up, so the agenda, month, week, date, and upcoming widgets each preview as themselves instead of an identical placeholder
- Added localized sample content to those previews, drawn from the same composables the live widgets use so a preview matches the real widget
- Added single-letter day-of-week names across the top of the month widget
- Added an optional week-number column to the month widget
- Changed the month widget to mark today with a solid filled accent circle around the date, and gave the agenda, week, and upcoming widgets a uniform day-header color
- Fixed the month widget's today marker to grow with the system font scale so it stays round at large text sizes
- Fixed the month widget to apply first-day-of-week and week-number changes immediately instead of only after a restart
- Fixed multi-day events spilling outside the month widget's visible range by clamping them to the shown window (#306)
- Added header refresh buttons to the agenda, week, and upcoming widgets
- Improved widget headers with matching heights, a plain readable "+", and larger touch targets, with spacing so a month-navigation tap can't land on the add button
- Improved the event-row tap target in the list widgets to 40dp
- Changed the widget body to let the accent color tint through instead of showing flat gray
- Changed the view menu to run shortest span first, from a single day up through the full year, via a pull request from [@az0ran](https://github.com/az0ran)
- Added an animated transition when moving between Settings sub-screens and the profile hub

## [2026.07.22]

Every family has the sibling who peaked early and the one who spent the summer reinventing themselves.

In KashCal, those siblings are the account hub and Settings. The hub is the one that showed up recently in a full-screen makeover, all round avatar and tidy sections, and collected the compliments. Settings, meanwhile, had been wearing the same nested-menus-and-dropdowns outfit since roughly the invention of the dropdown, and it had feelings about the attention.

So this release, Settings went for it. Every row now wears its current value on the right, so you read your whole setup at a glance instead of opening each one to check. Tap a row and a picker slides up from the bottom instead of a menu unfolding on top of everything. The section headers learned to search, so "which screen was the widget limit on again" is a question you type, not a scavenger hunt. Default alerts split into one for timed events and one for all-day, because "remind me 10 minutes before" and "remind me the morning of" were never the same wish. Sync frequency, which used to hide, now sits out in the open as its own row. And the custom-alert wheel stopped dismissing itself mid-spin, waiting politely for you to say Done.

Not to be upstaged, the hub tailored its own outfit. The Accounts entry wears a proper button now so adding or managing an account is easy to find, external links speak up for screen readers, and the small things got straightened out, a clipped pencil badge and an avatar that used to vanish on pale themes. We will let you decide which sibling looks better. We are not picking sides at family dinner.

While the two of them fussed over mirrors, the quieter work happened in the plumbing, and it matters more than any button. Edit a single occurrence of a repeating event and it stays a single edit, instead of quietly multiplying into duplicate or brand-new events on the next sync. When your server drops an exception from a recurring series, KashCal prunes its local copy to match instead of keeping a ghost. Import an .ics file full of a repeating event and it arrives as one linked series, and events that came with no unique ID no longer collide and overwrite each other.

Two siblings, one fresh coat of paint each, and a calendar underneath that finally keeps repeating events straight.

### Everything in this release

- Added a current-value display on the right of every Settings row, with the search match highlighted
- Changed Settings rows to open a bottom-sheet picker instead of a dropdown menu
- Added searchable Settings section headers that surface the whole group on a match
- Split the default alert into separate timed-event and all-day defaults, with an all-day 9 AM hint
- Added sync frequency as a visible Settings row instead of a hidden control
- Fixed the custom-alert wheel to commit only on `Done`, stop dismissing the sheet mid-scroll, and preserve off-grid or unchanged alert values
- Improved Settings layout: inlined the emoji and app-lock toggles, clearer labels, tighter grouping, and consistent row units
- Changed the backup, restore, and widget-limit rows to use distinct icons
- Added screen-reader announcements for Settings picker selection state and for external links that open in the browser
- Changed the account hub's Accounts entry to a clearly outlined button that is easier to find
- Fixed a clipped pencil badge and added an outline to the hub avatar and Accounts pill so they stay visible on pale accent themes
- Fixed the "today" highlight to stay visible on pale accent colors by outlining it
- Added translation of the built-in "Local" calendar name in each language
- Fixed editing a single occurrence of a recurring event multiplying into duplicate or new events on sync
- Fixed local exceptions lingering after the server dropped them from a recurring series; they are now pruned to match
- Fixed importing an `.ics` recurring event with changed occurrences so it arrives as one linked series
- Fixed imported events with a blank or missing UID colliding and overwriting each other
- Localized the import and calendar-permission messages

## [2026.07.19]

This release of KashCal is about being understood.

It is the thing everyone claims to want and almost no one delivers. Couples pay a therapist by the hour to translate "I'm fine" into its actual meaning. Ask any marriage that lasted forty years for the secret and you never hear "love" or "patience," you hear some version of the same quiet miracle: one person knowing what the other meant without being told twice. Being understood is the whole ballgame. Most software is not even parked outside the stadium.

So we taught Quick Add to listen. We were going to tell you we made it "better." Scratch that. "Better" is what you call a slightly bigger button. This is Quick Add finally understanding what you meant.

Type "lunch every 2nd Tuesday" and the right monthly repeat is just there, no dialog, no wheel-picker archaeology. Write "rent last day of the month" and it lands on the last day, every month, without you counting on your fingers. "Gym weekday at 7" already knows you mean Monday through Friday. Trail a thought after a " //" and everything past it slips quietly into the note. The box stretched to several lines with a character count, because sometimes the event is short and the story is not. And when you would rather point than type, a picker now lets you choose a "2nd Tuesday" or a "last Thursday" yourself. KashCal listens to [the whole sentence](https://kashcal.onekash.org/features/natural-language-add-event-calendar) and never once asks how that makes you feel.

The rest of the app got easier to read while we were in there. The agenda greets you with "Today" and "Tomorrow" instead of bare dates, folds its week strip away when you want the room, and now reads those week cells aloud to screen readers. The old three-dots menu became a round avatar: tap it for a full-screen hub where you set your two initials, reach Accounts & Settings, jump to a date, share your availability, and pick your theme, accent color, and app icon, all in one place.

We can't save your marriage. We can, at last, get "every 2nd Tuesday" right the first time.

### Everything in this release

- Quick Add: natural-language recurrence, weekend, and time parsing
- Quick Add: multi-line notes field with a 500-character cap and counter
- Quick Add: capture an inline note with a " //" delimiter
- Quick Add: "…of this month" phrases resolve to a one-off date in the current month
- Quick Add: "last day of the/every month" parses as the last day of the month
- Quick Add: monthly recurrence anchors its start to the first occurrence
- Recurrence: choose an ordinal weekday for monthly rules (1st–4th or Last), e.g. "2nd Tuesday" or "last Thursday" (#193)
- Agenda: relative day headers ("Today"/"Tomorrow") and a collapsible week bar
- Agenda: week-bar cell colors match the date picker, and week snapping is fixed
- Accessibility: agenda week-bar cells are described to screen readers
- The top-bar menu is now a circular avatar that opens a full-screen hub (Invites, Go to date, Share availability, Insights, Settings, About, Privacy & Security)
- Set your own two-letter initials on the avatar; a neutral person glyph shows until you do
- Theme, accent color, and app icon now live in a "Make it yours" section in the hub
- An "Accounts & Settings" button at the top of the hub makes adding or managing an account easy to find
- Settings backup now includes the agenda week-bar expanded/collapsed choice

## [2026.07.18]

New app icon. The old one was three calendar cards in a loose pile, like they had just been dealt. Now they are a proper deck, squared up and stacked, matching the cards KashCal has always drawn on your share images. The top-bar logo got the same tidy-up and still counts off today's date; the supporter icon kept its gold card and its heart.

While we were in there: empty days now say something kinder than "touch grass" and vary the line so it never nags, and CalDAV servers on your own home network are reachable on Android 17 with a quick permission ask instead of a mystery error.

### Everything in this release

- New deck-of-cards app icon, top-bar logo, and supporter icon
- Empty days show gentler, rotating copy instead of one fixed line
- Target Android 17 (API 37), with an inline local-network permission ask for CalDAV servers on your home network
- Tidied translations across all 67 languages for consistent ordering and calendar wording

## [2026.07.17]

A calendar is for scheduling things. So naturally, we asked our marketing team to sit with that idea for an afternoon and write up this release. Several donut holes past the point of good judgment, they came back not with scheduled thoughts but with a whole feed of ["unscheduled thoughts"](https://kashcal.onekash.org/unscheduled-thoughts): a steady drip of tiny, mostly kind notes like "You are not behind, time is just ambitious," which is, for the marketing arm of a scheduling company, either a resignation letter or a mission statement. We could not decide which, so we shipped it as a calendar you can subscribe to. It never blocks your day, it means well mostly, and yes, the "webcal://" fix in this very release is what lets you add it in one tap. Subscribe, and let a donut-addled marketing team quietly improve your week.

With them safely occupied, we wrote the actual notes. A calendar has exactly one job: to tell you, without drama, what is on and what is not. So it was philosophically upsetting to find the week and 3-day views treating that as optional. Delete an event and it lingered; add one and it refused to show; the only way to force the matter was to look away, drift over to the month view, and come back, at which point the event would resolve into existence as though it had been waiting for an audience. We turned it into a small thought exercise. An event you have created but the screen will not confirm: is it scheduled, or not? For a while, in those two views, the honest answer was both, right up until you observed it. Charming for a cat in a box. Unhelpful for a Tuesday.

This release collapses the waveform. Add, edit, or delete anything in day, week, or 3-day and it appears the instant you act, sync included, no detour required. Agenda and month, already well behaved, now share the same plumbing. The + button also stopped freelancing: in a time-grid view it starts a new event today at the next hour rather than some wrong day at an odd time. And that "webcal://" subscribe link, which KashCal used to answer with a network error while privately knowing exactly what it meant, now simply works, with a message in your own language on the rare feed that will not load.

Everything scheduled, nothing left in the box.

### Everything in this release

- Fixed the week and 3-day views not showing added, edited, or deleted events until you switched views and back (#297)
- The + button in day, week, and 3-day views now starts a new event today at the next hour, instead of the wrong day or a default hour
- Accept webcal:// links in the "Fetch Calendar" subscription preview instead of failing with a network error
- Localized the ICS subscription fetch error messages

## [2026.07.15]

Since the beginning of time, or at least of KashCal, your events have been sorted the way a coat check sorts coats: by which calendar they were flung into, and not one thought more. This release lets you label them yourself. Meet tags. Type one in the event form, or fling a #dentist straight into Quick Add and watch it land as a smug little colored chip that then follows the event around the day, week, and agenda views like it owns the place. Tap the event open and the tags are right there in quick view, quietly confirming that yes, this is a #focus block, and no, it is not the third #standup of the day you had every right to skip. Start typing and KashCal hands back the tags you already use, which is the only known cure for "Errands" fracturing into "errands," "ERRANDS," and one deeply confident "Errnads" by Thursday. And should you decide tags belong above your notes rather than below, the row's little ⋮ menu will move it, and we will pretend that was our idea all along.

While the tags moved in, the rest of the event form had a tidy-up. The location field came up to sit under the title where you reach for it first, free and busy moved to the bottom where it belongs, and a small army of stray dividers and uneven margins were shown the door. Nothing you can name, everything you can feel.

Two quieter fixes matter more than they look. If you run your own server on a LAN or a VPN, sync no longer hangs forever on "Preparing to sync" because Android could not phone home to the public internet first. Reachable is now enough. And the thirty-day "your changes could not sync" warning has stopped buzzing you over and over for the same events, and now names every calendar involved instead of shrugging.

Now the fine print, delivered upfront because we have no interest in being that app. Tags currently work on the events KashCal syncs itself, your iCloud and other CalDAV calendars. The device's own calendars, the Google, Samsung, and Exchange ones Android politely shoves through the door, are still tag-free for a release or two while we teach them manners. Rather than pretend otherwise and let your tags quietly vanish into the void, we simply hid the tag row on those events, which we feel is the mature response. More tags, more places, more tricks are queued up. This is version one of roughly several, and we are only telling you that so nobody accuses us of overselling a chip.

A few more papercuts, since we had the tweezers out. Events with no length, the ones you pin to a single moment, used to disappear entirely in the day and week views; they now show up as the small blocks they always meant to be. Emoji in a synced description arrive as the emoji you sent instead of a puzzled little box. And a garbled duration from some other app can no longer bend an event's end time back to before it started.

Small labels, better plumbing, fewer papercuts. Tag it and move on.

### Everything in this release

- Event tags (first release): colored chips on events, shown in day, week, and agenda views and the quick view, on iCloud and CalDAV events for now
- Create tags from the event form with usage-ranked suggestions and inline # autocomplete in the title
- Add tags from Quick Add by typing #tag, persisting across create and all edit scopes
- Reorder the form tag row above or below notes from its ⋮ menu
- Moved the location field up under the title in the event form
- Moved free/busy to the last row of the event form
- Tidied the event-form layout: consistent divider spacing, aligned row icons, and tighter all-day and title rows
- Shortened the location placeholder to "Address or link"
- Fixed self-hosted CalDAV/ICS sync hanging on "Preparing to sync" over LAN or VPN networks (#296)
- Fixed the expired-sync notification repeatedly alerting, and it now names every affected calendar
- Localized the agenda card date labels ("All day", "Day X of Y", "Starts", "Ends")
- Fixed zero-duration and very short events disappearing or overlapping in the day, 3-day, and week views
- Fixed emoji and other extended characters in synced event descriptions rendering as a stray box or wrong character
- Fixed a malformed event duration producing an end time before the start
- Sharpened the widget header contrast for pure white and pure black accent colors

## [2026.07.13]

Your agenda finally learned to read the room. For years its top bar proudly announced "Agenda," heroically confirming that the agenda screen was, against all odds, the agenda screen. Thank you, brave label. It has now been reassigned to showing the month you are actually looking at, keeping pace as you scroll, so August becomes September without you wondering where the summer went. And there is more of it: ninety days ahead instead of thirty, because your future has a way of arriving whether we render it or not.

The day timeline has also been persuaded to stop having amnesia. Pinch to zoom the hours in or out, and it now stays exactly where you left it after you close the app, rather than resetting to default overnight and pretending the two of you never met.

The month view, meanwhile, had a charming habit of opening in December 1969 if you hadn't tapped a day first. Lovely for nostalgia, useless for dentist appointments. It now opens in the current month, having been gently reminded which decade we are all living in.

KashCal has now retired from its brief career impersonating a relic from 1969 and checked in to 2026, where the rest of us have been waiting.

### Everything in this release

- Agenda now shows the next 90 days of events instead of 30
- The agenda top bar shows the current month and updates as you scroll, replacing the static "Agenda" title
- Fixed the month and full-month views opening on December 1969 when no day was selected yet
- The day timeline remembers your pinch-to-zoom hour height across app restart

## [2026.07.11]

Last release we gave you KashCal Teal and were very pleased with ourselves. Then someone pointed out the obvious: we had spent a whole update letting you choose your color, and then chose it for you. A calendar telling you your favorite color is teal is a bit like a waiter ordering for the table. Bold. Rarely correct.

So this release we got out of the way. Pick your accent from all 92 colors, and it runs through the entire app and, for the first time, out onto your home screen widgets too. The agenda, week, month, and date widgets all wear it, down to a proper raised add button. Want the old magic where the color follows your wallpaper? "Automatic" keeps your Material You colors exactly as they were.

Our marketing team, several donuts deep by mid-afternoon, has decided to call this "Calendar You." We did the math on 92 colors, one calendar, and infinite you, and we could not find the flaw, so it is approved. Please clap.

While we were in a generous mood: the week and 3-day views now remember where you were looking and put you back there when you reopen the app, instead of scrolling you off to some default hour like nothing happened. And moving an event to another calendar no longer quietly eats a title or note you edited in the same breath.

Ninety-two colors. Still one calendar. Now unmistakably yours.

### Everything in this release

- Accent color picker: theme the whole app and all home-screen widgets with any of 92 colors, defaulting to KashCal Teal (#293)
- "Automatic" accent keeps your Material You / wallpaper colors
- Agenda, week, month, and date widgets recolor to your accent, with a raised add button
- Week and 3-day views restore your last scroll position across app restart (#224)
- Moving an event to another calendar preserves title/note edits made in the same save (#292)

## [2026.07.06]

This release is about making KashCal yours. Two new choices, both in Settings, one for how the app looks and one for how it shows up on your home screen.

First, color. KashCal now has a proper theme picker. Stay on System, pin Light or Dark, or switch on KashCal Teal, our own palette that follows your phone's light and dark setting while keeping the brand green front and center. It runs through the whole app, contrast-checked so text stays readable on every surface, light or dark.

Second, the icon on your home screen. If you've chipped in to keep KashCal free and ad-free ([or you'd like to](https://kashcal.onekash.org/donate)), you can now wear it: a gold Supporter icon with a little heart on the calendar card. Pick it under Settings then App Icon in whichever flavor you like. Keep the KashCal name, or go incognito with the same icon labeled simply "Calendar." Whether you're already a supporter or about to become one, here's a thank you from us.

KashCal, the calendar you already love, now in your colors and wearing your badge.

### Everything in this release

- New theme picker in Settings: System, Light, Dark, or KashCal Teal
- KashCal Teal palette follows your phone's light/dark setting and is applied app-wide, with WCAG-checked contrast
- New supporter app icon: a gold card with a heart, chosen under Settings then App Icon
- Two supporter variants, sharing one icon: one keeps the "KashCal" name, one shows a discreet "Calendar" name on the home screen

## [2026.07.05]

For a while now, KashCal has had a quiet flaw: you could only use it by looking at it. It worked beautifully with your eyes, and went silent as a stone the moment you turned on a screen reader. A calendar that only works when you're watching it is, on reflection, a poster. So this release taught it to talk.

With TalkBack on, you can now move through KashCal by ear. Jump between headings, hear sync and offline status the moment it changes, and get told when a sign-in or a save fails instead of wondering why nothing happened. Events announce what they are, so a cancelled event says "cancelled" out loud rather than just looking faintly sad about it (it wears a line through it now, for the sighted crowd too). Bottom sheets say their name as they open, the drawer tells you which view you're in, and a subscription can finally be deleted with a real action instead of a swipe nobody could find.

While we were teaching it manners, we sent the languages out to live where they belong. KashCal now advertises all 67 of them to Android, so on Android 13 and up you pick the app's language in system settings alongside everything else, instead of spelunking through ours.

Two smaller dignities came along for the ride. Rotating your phone in the middle of an event no longer throws the whole thing away, and typing a title now capitalizes the first letter like a grown-up.

Same calendar. Now it works with the screen off, the phone sideways, and your eyes shut.

## [2026.07.02]

Dispatch from [OneKash Labs](https://onekash.org/), best known (of all things) for a calendar:

We keep meaning to invent something important. Then July 22 rolls around, the lab throws its annual Pi Approximation Day party (the one where 22/7 gets to cosplay as π and nobody files a complaint), someone gets ideas, and we end up improving the calendar again. Here is what escaped this time.

Exhibit one: precise numbers. The 5-minute time wheel is lovely right up until you need "3:47," at which point it just shrugs. So there is now a keyboard button on the time picker: tap it, type any minute you please. Events already sitting on an odd minute show the exact time as tappable text instead of quietly rounding themselves to the nearest five while you looked away. Your 8:52 standup stays 8:52.

Exhibit two: in honor of 22/7 being a wonderfully compact stand-in for something infinite, we went hunting for fat to trim and found the app hauling a crate of packing peanuts. We cleaned house. KashCal is now roughly a third smaller to download and install. Same calendar, less luggage.

Still just a calendar. Now a slightly better one. Happy (approximately) Pi Day.

## [2026.07.01]

- Spent way too long shaving pixels off the widgets so you don't have to think about them: slimmer event rows, a skinnier color bar, and spacing that finally lines up. More of your day, less of the chrome.
- The time column no longer eats your PMs. 12-hour times fit on one line like they always should have.

## [2026.06.30]

- New documentation site: https://kashcal.onekash.org/docs/
- Widget times no longer clip or wrap. 12-hour times now fit on one line.
- Fixed CalDAV sync with Xandikos servers being treated as read-only.

<!-- Newest release on top. Each "## [version]" heading must match the public
VERSION_NAME exactly; CI slices its section for the GitHub release body. The
terse, glanceable notes live in fastlane/metadata/android/en-US/changelogs/. -->
