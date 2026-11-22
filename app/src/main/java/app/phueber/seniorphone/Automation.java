package app.phueber.seniorphone;

import java.util.List;
import java.util.Locale;

/**
 * Commands that automation apps (Trigly, Tasker, ...) send: the action and
 * extra names, and lenient parsing of the extras. Automation apps can only
 * send string extras, so every value arrives as text; case and spaces do not
 * matter, and malformed input parses to null (the command is ignored).
 * Pure (no android.* imports), so it is unit tested.
 *
 * Three doors, by what a command may do:
 *  - AutomationActivity (activity start): CALL, OPEN_CONTACT, OPEN_DIALPAD,
 *    OPEN_CONTACTS. A screen must appear; CALL only asks "Call X?".
 *  - CallControlReceiver (broadcast, sender needs ANSWER_PHONE_CALLS):
 *    ANSWER, HANG_UP, SPEAKER, LOUDER.
 *  - AutomationReceiver (broadcast): LOCK, UNLOCK, SET_THEME.
 */
final class Automation {
    private static final String A = "app.phueber.seniorphone.action.";

    /** Ask "Call X?" for a number or a contact's name (extra number or name). Never calls by itself. */
    static final String CALL = A + "CALL";
    /** Open a contact's page (extra name or number). */
    static final String OPEN_CONTACT = A + "OPEN_CONTACT";
    /** Open the number pad, optionally with a number typed in (extra number). */
    static final String OPEN_DIALPAD = A + "OPEN_DIALPAD";
    /** Open the contact list, optionally searched (extra name). */
    static final String OPEN_CONTACTS = A + "OPEN_CONTACTS";

    /** Answer the ringing call. */
    static final String ANSWER = A + "ANSWER";
    /** End the call on the screen: decline it while ringing, hang up otherwise. */
    static final String HANG_UP = A + "HANG_UP";
    /** Speaker on, off or toggled (extra state; missing = toggle). */
    static final String SPEAKER = A + "SPEAKER";
    /** One step louder on the call volume, like the Louder button. */
    static final String LOUDER = A + "LOUDER";

    /** Lock with a new PIN (extra pin); only without SeniorLauncher and while unlocked. */
    static final String LOCK = A + "LOCK";
    /** Unlock with the PIN or the reset PIN (extra pin); lifts the launcher's lock too. */
    static final String UNLOCK = A + "UNLOCK";
    /** Colour theme (extra theme: light, dark, high_contrast, colourful). */
    static final String SET_THEME = A + "SET_THEME";

    static final String EXTRA_NUMBER = "number";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_PIN = "pin";
    static final String EXTRA_THEME = "theme";

    enum Speaker { ON, OFF, TOGGLE }

    private Automation() {}

    /** The action as one of ours (canonical spelling), or null. Case and spaces do not matter. */
    static String action(String action, String... known) {
        if (action == null) return null;
        String a = action.trim();
        for (String k : known) if (k.equalsIgnoreCase(a)) return k;
        return null;
    }

    /** on/true/1/yes, off/false/0/no, toggle; missing or blank = toggle; anything else null. */
    static Speaker parseSpeaker(String s) {
        if (s == null || s.trim().isEmpty()) return Speaker.TOGGLE;
        switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "on": case "true": case "1": case "yes": return Speaker.ON;
            case "off": case "false": case "0": case "no": return Speaker.OFF;
            case "toggle": return Speaker.TOGGLE;
            default: return null;
        }
    }

    /** light, dark, high_contrast (also high-contrast, "high contrast", contrast), colourful/colorful; else null. */
    static Palette.Theme parseTheme(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        switch (t) {
            case "light": return Palette.Theme.LIGHT;
            case "dark": return Palette.Theme.DARK;
            case "high_contrast": case "highcontrast": case "contrast": return Palette.Theme.HIGH_CONTRAST;
            case "colourful": case "colorful": return Palette.Theme.COLOURFUL;
            default: return null;
        }
    }

    /** A number to dial, cleaned like one from another app; null if it has no digit or is a code with * or #. */
    static String parseNumber(String s) {
        String n = DialNumber.fromOutside(s == null ? null : s.trim());
        return DialNumber.canCall(n) ? n : null;
    }

    /** The name to look for, trimmed; null if blank. */
    static String parseName(String s) {
        if (s == null) return null;
        String n = s.trim();
        return n.isEmpty() ? null : n;
    }

    /**
     * The contact a name means, or null when none or several fit. A name equal
     * to a contact's (ignoring case and accents: "maria muller" is "Maria
     * Müller") wins; otherwise the one contact whose name contains it ("Maria"
     * when there is only one Maria). Two contacts with that name: null, so a
     * command never picks the wrong person.
     */
    static ContactInfo pickContact(List<ContactInfo> contacts, String name) {
        String key = name == null ? "" : ContactList.searchKey(name.trim());
        if (key.isEmpty() || contacts == null) return null;
        ContactInfo exact = null, partial = null;
        int exacts = 0, partials = 0;
        for (ContactInfo c : contacts) {
            String k = ContactList.searchKey(c.name);
            if (k.equals(key)) {
                exacts++;
                exact = c;
            } else if (k.contains(key)) {
                partials++;
                partial = c;
            }
        }
        if (exacts > 0) return exacts == 1 ? exact : null;
        return partials == 1 ? partial : null;
    }

    /**
     * The phone's own country, for reading a national number ("0171 2345678")
     * as an international one. Android has no public way from a country to its
     * calling code, so the caller passes PhoneNumberUtils.formatNumberToE164
     * with the phone's country (Contacts.home); tests pass a fake.
     */
    interface Home {
        /** The national number in E.164 ("+491712345678"), or null if it is not a valid one here. */
        String e164(String national);
    }

    /**
     * Which of a contact's numbers is the given one: same digits, or the same
     * number written once with a country code and once nationally ("+49 171
     * 2345678" is "0171 2345678" in Germany, see {@link #sameNumber}); -1 if none.
     */
    static int pickPhone(List<String> numbers, String number, Home home) {
        String want = digits(number);
        if (want.length() == 0 || numbers == null) return -1;
        for (int i = 0; i < numbers.size(); i++) {
            if (digits(numbers.get(i)).equals(want)) return i;
        }
        for (int i = 0; i < numbers.size(); i++) {
            if (sameNumber(numbers.get(i), number, home)) return i;
        }
        return -1;
    }

    /**
     * Whether two numbers are the same one. Same digits always are. Two
     * international numbers ("+" and "00" alike, a "(0)" after the country
     * code ignored) must have the same digits, country code included:
     * "+49 30 12345678" and "+49 40 12345678" end alike but are different
     * people. A national number matches an international one only as a
     * number of the phone's own country (home): "0171 2345678" is
     * "+49 171 2345678" on a German phone, but never "+43 171 2345678",
     * which would otherwise borrow a German contact's name and picture for
     * a caller from abroad. Without a home country, or for national numbers
     * shorter than 6 digits, the two forms never match.
     */
    static boolean sameNumber(String a, String b, Home home) {
        String da = digits(a), db = digits(b);
        if (da.isEmpty()) return false;
        if (da.equals(db)) return true;
        String ia = international(a), ib = international(b);
        if (ia != null && ib != null) return ia.equals(ib); // "+49 …" is "0049 …"
        if (ia == null && ib == null) return false;
        String intl = ia != null ? ia : ib;
        String nat = ia != null ? b : a;
        if (home == null || digits(nat).length() < 6) return false;
        String e;
        try {
            e = international(home.e164(nat));
        } catch (RuntimeException ex) {
            return false;
        }
        return e != null && !e.isEmpty() && e.equals(intl);
    }

    /** The digits after "+" or "00" (without a "(0)" after it), or null for a national number. */
    private static String international(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("+")) return digits(t.replaceFirst("\\(\\s*0\\s*\\)", ""));
        String d = digits(t);
        return d.startsWith("00") ? digits(t.replaceFirst("\\(\\s*0\\s*\\)", "")).substring(2) : null;
    }

    private static String digits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
        }
        return b.toString();
    }
}
