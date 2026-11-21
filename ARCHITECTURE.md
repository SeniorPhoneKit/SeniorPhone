# Architecture

Package `app.phueber.seniorphone`. The phone and the address book for seniors:
a contact list with big names and favourites at the top, a number pad, and its
own big incoming-call and in-call screens. It takes Android's phone-app role
(DIALER), which is what lets it show those call screens.

Two icons on the home screen, because seniors look for "Phone" and
"Contacts" separately: **Phone** (`DialActivity`) opens the number pad,
**Contacts** (`ContactsActivity`, also `APP_CONTACTS`) the contact list. The
number pad has its own task affinity, so each icon always opens its own screen;
opened from the contact list it stays in the list's task, and Back returns
there.

Contacts and favourites are not stored by the app. They are the phone's own
address book (`ContactsContract`, favourite = `STARRED`), so they are the same
in every app and survive a reinstall. The app stores only its lock flag and
colour theme (`Store`).

## Files

| Class | Role |
|---|---|
| `ContactsActivity` | The first screen: Number pad and Settings (or, locked, a padlock), search, Favourites, then everyone A–Z with letter headers. A ListView, because an address book can hold hundreds of people. "Back to the call" banner while a call is on. |
| `ContactActivity` | One person: Back · Edit · Delete (locked: only Back), big picture, name, one green Call per number, which asks "Call Maria Schmidt?" first. |
| `EditContactActivity` | Our own simple form: first name, last name, one number. Not available when locked. |
| `Contacts` | Address-book access on one background thread (so a star or a delete always finishes before the reload after it). |
| `ContactInfo`, `ContactList`, `ContactForm` | Pure: the contact value, sorting and sections (Ä under A, Favourites first), search key, form rules. Unit tested. |
| `DialActivity` | The number pad; also what other apps open for "dial this number". |
| `DialMatch` | Pure: whose number is being typed. Same digits or same last nine digits gives "✓ Name" (plain text); otherwise, from 4 digits on, the one contact whose number starts like that (also in national form of a +CC number) is offered as "Name? Tap for …", which fills it in. Several candidates: nothing shown. Unit tested. |
| `DialNumber` | Pure: what may be typed and how a number from another app is cleaned. Unit tested. |
| `Calls` | `place()`: places a call through Telecom, asking for the call permission if needed. |
| `CallService` | The `InCallService` Telecom binds while there is a call. The only owner of call state; answer, decline, hang up, speaker. |
| `CallActivity` | The call screen: incoming (Answer / Decline), calling and connected (Hang up, Speaker, Louder), ended. Over the lock screen. |
| `CallNotifier` | The incoming-call notification (full screen, Answer / Decline) and the ongoing one (Hang up). |
| `CallActionReceiver` | Decline and Hang up from the notifications. After a process restart, a tap before Telecom has rebound the call service is kept for 3 s and applied when the call arrives. |
| `CallState` | Pure: Telecom states to screens, which call gets the screen, why a call ended, the timer text. Unit tested. |
| `Role` | Asks once per start to become the phone app; Settings shows the state. |
| `SettingsActivity` | Lock (choose a PIN), Colours, Phone app, phone settings. |
| `Ui`, `Palette` | Copied from SeniorLauncher: shared buttons, screens and dialogs; the four colour themes, contrast-tested. |
| `NavBar` | Whether SeniorNav's Back & Home bar is switched on (the enabled accessibility services list it). While it is, the pinned Back button is hidden, since the bar already has Back and Home; re-checked whenever a screen gets focus. Pure part unit tested. |
| `Store` | Own lock flag with PIN salt/hash, and theme; `locked()` is the launcher's lock or our own; `unlock(pin)` lifts both; the reset PIN (`Pin.RESET`) works for either. |
| `LauncherLock` | Reads SeniorLauncher's lock, and lifts it with its PIN; when the launcher cannot answer, the last known value is used (while it is installed). |
| `Pin` | PIN validation, salted hash, constant-time compare, and the fixed reset PIN. Same as SeniorAlert's. Pure. |
| `Automation` | Pure: the automation actions and extras, lenient parsing of the string extras, which contact a name means and which of its numbers a number is. Unit tested. |
| `AutomationActivity` | Invisible trampoline for CALL, OPEN_CONTACT, OPEN_DIALPAD, OPEN_CONTACTS: finds the contact off the main thread, opens the page / pad / list, closes. Never calls. |
| `AutomationReceiver` | Broadcasts LOCK, UNLOCK, SET_THEME, through `Store` on a background thread (goAsync). |
| `CallControlReceiver` | Broadcasts ANSWER, HANG_UP, SPEAKER, LOUDER through `CallService`, guarded by ANSWER_PHONE_CALLS. |
| `PersonWidget` | Widget: one person, one cell. Round photo or letter, the first name big; tap → their page with "Call …?". Deleted: "?" → the list. |
| `FavouritesWidget` | Widget: as many favourites as whole rows fit its height, then "+N more" → the list; tap a row → "Call …?". Empty: "No favourites yet" → the list. |
| `WidgetPickActivity` | The one-person widget's configuration: contacts, favourites first, search. Cancel = RESULT_CANCELED. Also reconfigure. |
| `PhoneWidgets` | What both widgets share: the remembered person (id + lookup key), coalesced refresh, ContentObserver, round pictures, taps. |
| `ContactsChangedJob` | JobScheduler content trigger on the address book: redraws the widgets while the app is not running. |
| `WidgetModel` | Pure: first name for the button, favourites order, how many rows fit a height (`fit`), the stored pick. Unit tested. |
| `PinDialog` | Choose a PIN (twice); enter it to unlock. The unlock prompt closes after 10 s without typing. |

## A call can always be answered, declined and ended

This is the part that must never fail.

- Telecom binds `CallService` for every call because we hold the DIALER role,
  and starts it again after the process was killed. `onCallAdded` opens
  `CallActivity` directly; a bound InCallService may do that from the
  background, so the screen appears even with notifications switched off.
- `CallActivity` is `showWhenLocked` and `turnScreenOn`. It keeps no call
  state of its own: it reads `CallService` on every change and every second,
  so a recreated screen or a call that ended in the background never leaves a
  stale screen.
- The incoming notification has Answer and Decline buttons and a full-screen
  intent, as the fallback when the screen cannot be opened directly. Once our
  screen is in front, the notification turns quiet, so a heads-up never covers
  the caller's name.
- The ongoing notification has Hang up and comes back if swiped away.
- Ringing stays with the system (no `IN_CALL_SERVICE_RINGING`): the phone rings
  with its normal ringtone and volume.
- Answer is huge and on top, Decline smaller and below: declining by mistake
  is worse than answering by mistake, and stacked buttons cannot be hit by a
  sideways slip. New screens ignore taps for 0.7 s, so a tap meant for what
  was there before never lands on Hang up.
- Back does nothing during a call. While a call is on, the contact list shows
  a green "Back to the call".
- The proximity wake lock turns the screen off at the ear, only for earpiece
  calls, never while ringing.

## Lock

One lock for the whole phone. With SeniorLauncher installed, this app follows
the home screen's lock (`LauncherLock` reads the launcher's `LockProvider`; a
ContentObserver follows changes while the list is open). Settings then only
explains that. Holding the padlock here asks for the home screen's PIN and
lifts the lock in place, for the whole phone, so the helper never has to go
back to the home screen. Without the launcher, Settings → Lock (choose a PIN)
is this app's own lock, unlocked the same way. The PIN prompt never lingers:
it closes after 10 s without typing.

Locked: Settings is replaced by a padlock, the star buttons go, a favourite shows its
star inside its pill on the right, and New contact, Edit and Delete
disappear. Calling works as always.

Every screen has its Back button at the bottom, like the other Senior apps,
and it goes straight to the home screen in one tap (`Ui.goHome`), closing the
app's screens behind it: back step by step (contact page, list, home) was too
many steps. The system back gesture still goes one step.

## Automation

Automation apps (Trigly, Tasker) can send only an action and up to three
string extras, so every extra is parsed from text, leniently, and a malformed
one makes the command do nothing (`Automation`, unit tested). Three doors, by
what a command may do:

- Screens (`AutomationActivity`, activity start): a screen must appear.
  CALL never places a call: it opens the contact page or number pad with the
  usual "Call X?" question (`EXTRA_ASK_CALL`), so it gives another app no more
  than ACTION_DIAL does. A name must match one contact; several or none open
  the list searched for it. A number is looked up with Android's PhoneLookup,
  then by its last nine digits (PhoneLookup depends on the phone's country).
  The trampoline uses CLEAR_TOP so an open contact page or pad is replaced
  rather than just brought to the front.
- Call control (`CallControlReceiver`, broadcast): guarded by
  `android:permission="android.permission.ANSWER_PHONE_CALLS"`, so only an
  app that may answer and end calls itself can send it. It calls the same
  `CallService` commands as the buttons (HANG_UP = the screen's call: decline
  while ringing, else hang up), so screen and notifications follow. Without a
  call it does nothing.
- Settings (`AutomationReceiver`, broadcast, no permission): the lock is a
  guard rail, not security, so the helper's automation may change it while
  locked. LOCK is ignored with SeniorLauncher installed (one lock, set on the
  home screen) and while already locked (it would replace the PIN the helper
  knows). UNLOCK goes through `Store.unlock`, so it lifts the launcher's lock
  with its PIN too. Open screens listen to `Store` (SharedPreferences
  listener) and follow a lock or colour change at once.

Broadcasts are preferred: they need no overlay permission from the sender
when it runs in the background. Nothing is reported back.

## Widgets

Two home-screen widgets, in the app's own colours (`Store` theme), big text:

- **One person** (`PersonWidget`, 1x1): the helper chooses the person when
  adding it (`WidgetPickActivity`, a required configuration, so SeniorLauncher
  runs it; `reconfigurable` to change it later). Remembered by contact id
  and lookup key, so a merged or re-numbered contact is still found
  (`Contacts.find`). SeniorLauncher shows it like an app tile, with the
  widget's label ("Call") below.
- **Favourites** (`FavouritesWidget`, wide, 4x2 by default, resizable taller
  but never one cell): the starred contacts, in the contact list's order
  (`WidgetModel.favourites`), as fixed 80 dp rows. Only whole rows, never a
  scrolling list (a senior does not see that a half row means "there is
  more"): `WidgetModel.fit` takes the widget's height and gives how many rows
  fit; when not all do, the last line is "+N more – tap to see all" (the
  contact list) instead of a row. The rows are plain RemoteViews added into
  a LinearLayout (`removeAllViews` first: the home screen applies updates
  onto the views it has, so rows would pile up). The height: on 12+ one
  layout per entry of `OPTION_APPWIDGET_SIZES` (the home screen picks);
  before, `OPTION_APPWIDGET_MIN_HEIGHT` (the smaller of portrait and
  landscape, so nothing is cut). `onAppWidgetOptionsChanged` redraws on a
  resize. Each row has its own PendingIntent (the contact id in the action,
  or rows would share one).

The widget list shows `previewLayout` (12+) and, before and in launchers
that use it (SeniorLauncher), `previewImage`: vector pictures in the Light
colours, Maria for the one-person widget and three rows for the favourites,
with hand-drawn stroke letters (no font asset).

A tap never calls: it opens the contact page with `EXTRA_ASK_CALL`, the same
"Call Maria Schmidt?" question automation's CALL gets (with several numbers,
the page with one Call per number). Nothing to show is said in words:
deleted person "?", no favourites "No favourites yet", no contacts
permission "Allow contacts"; each opens the contact list, which asks for the
permission.

Up to date without polling (`PhoneWidgets.changed`, coalesced into one
redraw): a ContentObserver on the address book while the process lives; a
`ContactsChangedJob` content trigger when it does not; and direct refreshes
after the app's own star, save, delete, the contacts permission being
granted, and a colour change. Only while a widget is placed.

## Permissions

- Contacts (read, write), phone (call, phone state, call log): what a phone
  app needs. Becoming the phone app usually grants them.
- Notifications and full-screen intent: the incoming-call notification. Calls
  still work without them.
- `android.hardware.telephony` is required: without a phone line there is
  nothing to do.
