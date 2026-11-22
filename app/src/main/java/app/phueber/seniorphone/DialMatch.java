package app.phueber.seniorphone;

import java.util.ArrayList;
import java.util.List;

/**
 * Who a number typed on the pad belongs to. Pure, unit tested.
 *
 * - EXACT: the typed number is a contact's number (Automation.pickPhone: same
 *   digits, or national vs. country-code form in the phone's own country, so
 *   "0171 234…" finds "+49 171 234…" on a German phone, but "040 …" never
 *   "+49 30 …"): the pad says whose.
 * - PREFIX: at least {@link #MIN_PREFIX} digits typed and exactly one contact
 *   number starts with them, also when it is saved with a country code: the pad
 *   offers it, one tap fills in the rest.
 * Several candidates, or none: nothing is shown, so nothing misleads.
 */
final class DialMatch {
    static final int MIN_PREFIX = 4;

    enum Kind { EXACT, PREFIX }

    static final class Entry {
        final String name;
        final String number;

        Entry(String name, String number) {
            this.name = name;
            this.number = number;
        }
    }

    static final class Result {
        final Kind kind;
        final Entry entry;

        Result(Kind kind, Entry entry) {
            this.kind = kind;
            this.entry = entry;
        }
    }

    private DialMatch() {}

    static Result match(List<Entry> entries, String typed, Automation.Home home) {
        if (entries == null || entries.isEmpty()) return null;
        String want = digits(typed);
        if (want.isEmpty()) return null;
        List<String> numbers = new ArrayList<>();
        for (Entry e : entries) numbers.add(e.number);
        int exact = Automation.pickPhone(numbers, typed, home);
        if (exact >= 0) return new Result(Kind.EXACT, entries.get(exact));
        if (want.length() < MIN_PREFIX) return null;
        Entry found = null;
        for (Entry e : entries) {
            if (!startsWith(e.number, want)) continue;
            if (found != null && !(found.name.equals(e.name) && digits(found.number).equals(digits(e.number)))) {
                return null; // more than one person or number: say nothing
            }
            found = e;
        }
        return found == null ? null : new Result(Kind.PREFIX, found);
    }

    /** Whether the typed digits start this number as saved, or its national form ("+49 171" → "0171"). */
    private static boolean startsWith(String saved, String want) {
        String d = digits(saved);
        if (d.startsWith(want)) return true;
        String s = saved == null ? "" : saved.trim();
        boolean international = s.startsWith("+") || d.startsWith("00");
        if (!international || !want.startsWith("0")) return false;
        String rest = d.startsWith("00") ? d.substring(2) : d;
        for (int cc = 1; cc <= 3 && cc < rest.length(); cc++) {
            if (("0" + rest.substring(cc)).startsWith(want)) return true;
        }
        return false;
    }

    static String digits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
        }
        return b.toString();
    }
}
