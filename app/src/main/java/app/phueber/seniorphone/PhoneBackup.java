package app.phueber.seniorphone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What SeniorPhone keeps in a settings backup, and how favourites from a file
 * find their contacts on this phone. Pure (no android.* imports), unit tested.
 *
 * <pre>
 * "settings": {"theme": "DARK",
 *              "favourites": [{"name": "Maria Schmidt", "numbers": ["+49 171 2345678"]}]}
 * </pre>
 *
 * The lock is never part of it. Favourites are saved as name and numbers, not
 * as contact ids (those differ on every phone), and the address book itself
 * is not saved: a restore only stars contacts the phone already has.
 * The one-person widget's chosen contact is not saved either: it belongs to a
 * widget id, which is new on every phone.
 */
final class PhoneBackup {
    private PhoneBackup() {}

    static final String K_THEME = "theme";
    static final String K_FAVOURITES = "favourites";
    static final String K_NAME = "name";
    static final String K_NUMBERS = "numbers";

    /** A favourite as the file keeps it. */
    static final class Favourite {
        final String name;
        final List<String> numbers;

        Favourite(String name, List<String> numbers) {
            this.name = name == null ? "" : name.trim();
            this.numbers = numbers == null ? new ArrayList<>() : numbers;
        }

        /** How the helper is told about it when no contact matches. */
        String label() {
            if (!name.isEmpty()) return name;
            return numbers.isEmpty() ? "?" : numbers.get(0);
        }
    }

    /** The app's settings. */
    static final class Settings {
        final Palette.Theme theme;
        /**
         * Null when the file has none, e.g. saved while the app could not read
         * the contacts: then a restore leaves the favourites as they are. An
         * empty list means "no favourites".
         */
        final List<Favourite> favourites;

        Settings(Palette.Theme theme, List<Favourite> favourites) {
            this.theme = theme;
            this.favourites = favourites;
        }
    }

    static Map<String, Object> toMap(Settings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(K_THEME, s.theme.name());
        if (s.favourites != null) {
            List<Object> list = new ArrayList<>();
            for (Favourite f : s.favourites) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put(K_NAME, f.name);
                one.put(K_NUMBERS, new ArrayList<Object>(f.numbers));
                list.add(one);
            }
            m.put(K_FAVOURITES, list);
        }
        return m;
    }

    /** All or nothing: a field of the wrong type, or an unknown theme, is a malformed file. */
    static Settings fromMap(Map<String, Object> m) throws Backup.Failure {
        String themeName = Backup.string(m, K_THEME, Palette.Theme.LIGHT.name());
        Palette.Theme theme = null;
        for (Palette.Theme t : Palette.Theme.values()) if (t.name().equals(themeName)) theme = t;
        if (theme == null) throw new Backup.Failure(Backup.ERROR_MALFORMED, "theme " + themeName);
        List<Favourite> favourites = null;
        if (m.get(K_FAVOURITES) != null) {
            favourites = new ArrayList<>();
            for (Object o : Backup.list(m, K_FAVOURITES)) {
                Map<String, Object> one = Backup.object(o, "favourite");
                favourites.add(new Favourite(Backup.string(one, K_NAME, ""),
                        Backup.strings(one, K_NUMBERS)));
            }
        }
        return new Settings(theme, favourites);
    }

    /** A contact on this phone, as the matcher needs it. */
    static final class Candidate {
        final long id;
        final String name;
        final List<String> numbers;

        Candidate(long id, String name, List<String> numbers) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.numbers = numbers == null ? Collections.emptyList() : numbers;
        }
    }

    /** Which contacts become the favourites, and which favourites found none. */
    static final class Match {
        /** Contact ids, in the file's order. */
        final List<Long> ids = new ArrayList<>();
        /** Labels of the favourites without a contact, in the file's order. */
        final List<String> unmatched = new ArrayList<>();
    }

    /**
     * Finds each favourite's contact: by phone number first (the same number by
     * Automation.sameNumber: same digits, or "0171 …" vs. "+49 171 …" in the
     * phone's own country), for every favourite, then by name (case and outer
     * spaces ignored) for the ones still without one. A number is the surer
     * sign: a name match never takes a contact another favourite's number
     * found. Each contact is used once.
     */
    static Match match(List<Favourite> favourites, List<Candidate> contacts, Automation.Home home) {
        int n = favourites.size();
        Candidate[] found = new Candidate[n];
        List<Candidate> free = new ArrayList<>(contacts);
        for (int i = 0; i < n; i++) {
            Candidate c = byNumber(favourites.get(i), free, home);
            if (c != null) {
                found[i] = c;
                free.remove(c);
            }
        }
        for (int i = 0; i < n; i++) {
            if (found[i] != null) continue;
            Candidate c = byName(favourites.get(i), free);
            if (c != null) {
                found[i] = c;
                free.remove(c);
            }
        }
        Match m = new Match();
        for (int i = 0; i < n; i++) {
            if (found[i] != null) m.ids.add(found[i].id);
            else m.unmatched.add(favourites.get(i).label());
        }
        return m;
    }

    private static Candidate byNumber(Favourite f, List<Candidate> free, Automation.Home home) {
        for (String number : f.numbers) {
            for (Candidate c : free) {
                for (String own : c.numbers) {
                    if (Automation.sameNumber(own, number, home)) return c;
                }
            }
        }
        return null;
    }

    private static Candidate byName(Favourite f, List<Candidate> free) {
        String want = nameKey(f.name);
        if (want.isEmpty()) return null;
        for (Candidate c : free) if (nameKey(c.name).equals(want)) return c;
        return null;
    }

    private static String nameKey(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
