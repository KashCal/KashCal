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
