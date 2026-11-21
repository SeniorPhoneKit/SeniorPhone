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

    /** Height of one favourites row, the space between rows, and the "+N more" line (dp). */
    static final int ROW_DP = 80;
    static final int GAP_DP = 6;
    static final int MORE_DP = 52;
    /** Used when the home screen has not told the widget its size (the provider's minHeight). */
    static final int DEFAULT_HEIGHT_DP = 110;

    /**
     * What the favourites widget shows at a height: the first {@code rows}
     * favourites as whole rows, then, when {@code more > 0}, one line
     * "+N more – tap to see all" (with no rows: "N favourites – tap to see all").
     */
    static final class Fit {
        final int rows;
        final int more;

        Fit(int rows, int more) {
            this.rows = rows;
            this.more = more;
        }
    }

    private WidgetModel() {}

    /**
     * How many of {@code count} favourites fit as whole rows into a widget
     * {@code heightDp} tall, so no row is ever cut in half (a senior would not
     * know the rest is there). When not all fit, the last line is the "more"
     * line instead of a row. heightDp <= 0 means unknown.
     */
    static Fit fit(int count, int heightDp) {
        if (count <= 0) return new Fit(0, 0);
        int h = heightDp > 0 ? heightDp : DEFAULT_HEIGHT_DP;
        if (count * ROW_DP + (count - 1) * GAP_DP <= h) return new Fit(count, 0);
        // Each shown row takes its gap below it; the more line comes last.
        int rows = Math.max(0, (h - MORE_DP) / (ROW_DP + GAP_DP));
        rows = Math.min(rows, count - 1);
        return new Fit(rows, count - rows);
    }

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
