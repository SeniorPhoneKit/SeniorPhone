package app.phueber.seniorphone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The pure part of the home-screen widgets: which name the one-person button
 * shows, which contacts the favourites widget lists and in what order, and how
 * the person a widget calls is remembered. No android.* imports, so it is
 * checked by a JVM test.
 */
final class WidgetModel {
    /** The person a one-person widget calls: the aggregate id and the lookup key that survives re-aggregation. */
    static final class Pick {
        final long id;
        final String lookupKey;

        Pick(long id, String lookupKey) {
            this.id = id;
            this.lookupKey = lookupKey == null ? "" : lookupKey;
        }
    }

    private WidgetModel() {}

    /**
     * The first name, shown big on the one-person button: "Maria Schmidt" →
     * "Maria", "Schmidt, Maria" → "Maria", "Dr. Maria Schmidt" → "Maria". A
     * name that does not start with a letter (a bare number such as
     * "+49 170 1234567", "(Arzt)") is kept whole: cutting it would show
     * something nobody recognises. "" for no name.
     */
    static String firstName(String name) {
        if (name == null) return "";
        String s = name.trim();
        if (s.isEmpty()) return "";
        if (!startsWithLetter(s)) return s;
        int comma = s.indexOf(',');
        if (comma > 0 && !s.substring(comma + 1).trim().isEmpty()) {
            s = s.substring(comma + 1).trim(); // "Schmidt, Maria"
        }
        String[] words = s.split("\\s+");
        int i = 0;
        // Titles like "Dr." or "Fr." come first; the name is the word after them.
        while (i < words.length - 1 && words[i].endsWith(".")) i++;
        String w = words[i].replaceAll(",+$", ""); // "Schmidt," alone
        return w.isEmpty() ? words[i] : w;
    }

    private static boolean startsWithLetter(String s) {
        return Character.isLetter(s.codePointAt(0));
    }

    /**
     * The favourites widget's rows: the starred contacts, in the order of the
     * contact list (ContactList.comparator), so the widget and the app's
     * Favourites section never disagree.
     */
    static List<ContactInfo> favourites(List<ContactInfo> all, Comparator<ContactInfo> order) {
        List<ContactInfo> out = new ArrayList<>();
        for (ContactInfo c : all) if (c.starred) out.add(c);
        out.sort(order);
        return out;
    }

    /** "id:lookupKey", for SharedPreferences. */
    static String encode(Pick p) {
        return p.id + ":" + p.lookupKey;
    }

    /** The stored pick, or null when there is none or it cannot be read. */
    static Pick decode(String s) {
        if (s == null) return null;
        int colon = s.indexOf(':');
        if (colon <= 0) return null;
        try {
            long id = Long.parseLong(s.substring(0, colon));
            return id < 0 ? null : new Pick(id, s.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
