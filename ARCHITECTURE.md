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
| `DialActivity` | The number pad; also what other apps open for "dial this number". A number from outside (tel: link, automation) with `*` or `#` is refused and the pad opens empty: it is an MMI/USSD code ("**21*…#" forwards every call), not a number. Codes typed on the pad work as always. |
| `DialMatch` | Pure: whose number is being typed. Same number (same digits, or national vs. country-code form of the phone's own country, `Automation.sameNumber`) gives "✓ Name" (plain text); otherwise, from 4 digits on, the one contact whose number starts like that (also in national form of a +CC number) is offered as "Name? Tap for …", which fills it in. Several candidates: nothing shown. Unit tested. |
| `DialNumber` | Pure: what may be typed and how a number from another app is cleaned (`clean`) or refused when it is a code (`fromOutside`). Unit tested. |
| `Calls` | `place()`: places a call through Telecom, asking for the call permission if needed; refused (a call is already on: `busy()`) or failed, it says so. |
| `CallBanner` | The green "Back to the call" on the list, the pad and a contact page; follows `CallService` while the screen is started. |
| `CallService` | The `InCallService` Telecom binds while there is a call. The only owner of call state; answer, decline, hang up, speaker, mute, keypad tones (DTMF). |
| `CallActivity` | The call screen: incoming (Answer / Decline), calling and connected (Hang up, Speaker, Louder, Microphone, Keypad), ended. Over the lock screen. The keypad (also opened by Telecom's `onBringToForeground(showDialpad)`) is a big 0–9 * # pad in the number pad's look (`DialActivity.keyView`) that sends `playDtmfTone`/`stopDtmfTone` and shows the typed digits; Hang up keeps its size and place. |
| `CallNotifier` | The incoming-call notification (full screen, Answer / Decline) and the ongoing one (Hang up). |
| `CallActionReceiver` | Decline and Hang up from the notifications. After a process restart (Telecom has not bound the call service in this process yet), a tap is kept for 3 s and applied when the call arrives; otherwise a tap that finds no call only removes the notification, so it can never end the next call. |
| `MissedCalls` | Receiver for Telecom's `ACTION_SHOW_MISSED_CALLS_NOTIFICATION` (as the phone app, Telecom leaves the notification to us): one big notification, title = caller's name or number, text "Missed call · 14:05" (reads well on SeniorLauncher's notification card), newest missed call from the call log. Tap: the person's page (over the list) or the pad with the number; Call back: the same with "Call …?". Opening, Call back or swiping tells Telecom they were seen (`cancelMissedCallsNotification`). Only our own notification can do that: the swipe goes to the non-exported `MissedCalls.Seen` (the exported `MissedCalls` only takes Telecom's broadcast), and the pad and the list honour the "seen" extra only when started through the non-exported aliases `MissedCallDial` / `MissedCallList` (or on the non-exported contact page), so another app cannot silently clear the missed calls. The call-log VIEW intent (`vnd.android.cursor.dir/calls`) opens the contact list: there is no call-log screen. |
| `CallState` | Pure: Telecom states to screens, which call gets the screen (never a conference participant: the conference call does, so Hang up ends all of it), why a call ended, the timer text. Unit tested. |
| `Role` | Asks once per start to become the phone app; Settings shows the state. |
| `SettingsActivity` | Lock (choose a PIN), Colours, Phone app, phone settings. |
| `Ui`, `Palette` | Copied from SeniorLauncher: shared buttons, screens and dialogs; the four colour themes, contrast-tested. `Ui.confirm` (every "Call X?") ignores Yes until it has been in front for 0.7 s (again after it gets the focus back), drops taps through another app's window (`filterTouchesWhenObscured`) and, on 12+, hides other apps' overlays while it shows (`HIDE_OVERLAY_WINDOWS`): another app can open the question, so it must not be able to time or disguise the tap on Yes. |
| `NavBar` | Whether SeniorNav's Back & Home bar is visible: SeniorNav's read-only provider `content://app.phueber.seniornav.bar/state` (column `visible`, also 0 while automation pauses the bar), observed live while the Back button is attached; without that provider, whether its accessibility service is enabled. While the bar shows, the pinned Back button is hidden, since the bar already has Back and Home; also re-checked whenever a screen gets focus. Pure part unit tested. |
| `Store` | Own lock flag with PIN salt/hash, and theme, in device-protected storage (direct boot); `locked()` is the launcher's lock or our own; `unlock(pin)` lifts both; the reset PIN (`Pin.RESET`) works for either. |
| `LauncherLock` | Reads SeniorLauncher's lock, and lifts it with its PIN; when the launcher cannot answer, the last known value is used (while it is installed). |
| `Pin` | PIN validation, salted hash, constant-time compare, and the fixed reset PIN. Same as SeniorAlert's. Pure. |
| `Automation` | Pure: the automation actions and extras, lenient parsing of the string extras, which contact a name means and which of its numbers a number is (`sameNumber`). Unit tested. The phone's country comes in as `Automation.Home` (`Contacts.home`: network country, else SIM country, through `PhoneNumberUtils.formatNumberToE164`; Android has no public country-to-calling-code table). |
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
  sideways slip. New screens ignore taps for 0.7 s (`Ui.TOUCH_GUARD_MS`), so a tap meant for what
  was there before never lands on Hang up. Calling and connected have the
  same buttons in the same places, so the other side answering does not
  restart that pause.
- Back does nothing during a call (with the keypad open it closes the keypad). While a call is on, the contact list,
  the number pad and a contact page show a green "Back to the call"
  (`CallBanner`, following `CallService` while the screen is started, so it
  comes and goes with the call). Call, "Call X?" and automation's question
  then say "You are already on a call" with "Back to the call" instead of
  starting a second call (`Calls.busy`), and the pad clears a number typed
  before the call when a call starts or ends.
- Direct boot: `CallService`, `CallActivity` and `CallActionReceiver` are
  `directBootAware`, so our screen (not the stock dialer's) shows a call
  that comes in before the first unlock after a restart. Everything they
  read then comes from device-protected storage: `Store` (theme, also the
  lock flag and PIN hash, a guard rail rather than a secret) lives there and
  is moved over once, the first time the app runs unlocked. Limits: the
  address book is unreadable before the unlock, so the caller is shown by
  number; a call before that first move after an update uses the default
  colours; the missed-call notification and every other screen wait for the
  unlock.
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
  then by `Automation.pickPhone` (PhoneLookup depends on the phone's
  country): same digits, or one number with a country code and the other
  national, where the national one, read as a number of the phone's own
  country (network, else SIM), is the international one. Never by a shared
  ending alone: +49 30 12345678 and +49 40 12345678 are different people;
  and never across countries: on a German phone "+43 171 2345678" is not the
  contact saved as "0171 2345678", or a caller from abroad would get that
  contact's name and picture on the call screen. Country unknown: only same
  digits match. The call screen and the missed-call notification use the
  same rule when PhoneLookup misses (`Contacts.idByNumber`). When the page
  found has no such number (PhoneLookup is looser than our rule), the
  question names the number, not the person: "Call 0171 2345678?".
  A contact page is opened over a fresh contact list
  (`ContactActivity.overList`, also used by the widgets and the missed-call
  notification), so it is always the page asked for and Back goes to the
  list, never to a page left over from an earlier command; the pad uses
  CLEAR_TOP so an open pad is replaced rather than just brought to the front.
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

Open by decision: `AutomationActivity` and `AutomationReceiver` take commands
from any app, with no permission and no caller check, so Trigly, Tasker and
the like work without setup. What that allows is therefore limited in the
app itself, not by who sends:

- No app can place a call through us: every call stops at "Call X?", and
  its Yes ignores taps for 0.7 s after the question appears (or gets the
  focus back), drops taps through other windows and, on 12+, hides other
  apps' overlays (`Ui.confirm`).
- A number from outside (automation's `number`, a tel: link) with `*` or `#`
  is refused, so no network code (call forwarding, USSD) arrives pre-typed
  (`DialNumber.fromOutside`).
- A contact's name is shown only for one of the contact's own numbers, by
  the phone's own country (`Automation.sameNumber`, and the number itself in
  "Call …?" otherwise).
- Missed calls can be marked seen only from our own notification
  (`MissedCalls`).
- Call control needs ANSWER_PHONE_CALLS (`CallControlReceiver`); the lock is
  a guard rail, not security (`AutomationReceiver`).

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
