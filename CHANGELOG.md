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
