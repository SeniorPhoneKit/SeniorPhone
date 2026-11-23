package app.phueber.seniorphone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class PhoneBackupTest {
    private static PhoneBackup.Favourite fav(String name, String... numbers) {
        return new PhoneBackup.Favourite(name, Arrays.asList(numbers));
    }

    private static PhoneBackup.Candidate contact(long id, String name, String... numbers) {
        return new PhoneBackup.Candidate(id, name, Arrays.asList(numbers));
    }

    @Test public void roundTripThroughTheFile() throws Exception {
        PhoneBackup.Settings s = new PhoneBackup.Settings(Palette.Theme.HIGH_CONTRAST, Arrays.asList(
                fav("Maria Schmidt", "+49 171 2345678", "030 1234567"), fav("Dr. Weber", "0221 98765")));
        String file = Backup.writeOne("2026-11-23T10:00:00Z", "app.phueber.seniorphone", "0.0.2",
                PhoneBackup.toMap(s));
        PhoneBackup.Settings back = PhoneBackup.fromMap(Backup.settingsFor(file, "app.phueber.seniorphone"));
        assertEquals(Palette.Theme.HIGH_CONTRAST, back.theme);
        assertEquals(2, back.favourites.size());
        assertEquals("Maria Schmidt", back.favourites.get(0).name);
        assertEquals(Arrays.asList("+49 171 2345678", "030 1234567"), back.favourites.get(0).numbers);
        assertEquals("Dr. Weber", back.favourites.get(1).name);
    }

    @Test public void favouritesLeftOutStayLeftOut() throws Exception {
        PhoneBackup.Settings s = new PhoneBackup.Settings(Palette.Theme.DARK, null);
        Map<String, Object> m = PhoneBackup.toMap(s);
        assertTrue(!m.containsKey(PhoneBackup.K_FAVOURITES));
        assertNull(PhoneBackup.fromMap(m).favourites);
        PhoneBackup.Settings none = PhoneBackup.fromMap(
                PhoneBackup.toMap(new PhoneBackup.Settings(Palette.Theme.DARK, Collections.emptyList())));
        assertTrue(none.favourites.isEmpty());
    }

    @Test public void missingFieldsTakeDefaults() throws Exception {
        PhoneBackup.Settings s = PhoneBackup.fromMap(new LinkedHashMap<>());
        assertEquals(Palette.Theme.LIGHT, s.theme);
        assertNull(s.favourites);
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> f = new LinkedHashMap<>();
        m.put("favourites", Arrays.asList(f));
        s = PhoneBackup.fromMap(m);
        assertEquals("", s.favourites.get(0).name);
        assertTrue(s.favourites.get(0).numbers.isEmpty());
        assertEquals("?", s.favourites.get(0).label());
    }

    @Test public void wrongTypesAreMalformed() {
        Object[][] bad = {
                {"theme", 3L}, {"theme", "PURPLE"}, {"favourites", "Maria"},
                {"favourites", Arrays.asList("Maria")},
                {"favourites", Arrays.asList(Collections.singletonMap("name", 5L))},
                {"favourites", Arrays.asList(Collections.singletonMap("numbers", "0171"))},
                {"favourites", Arrays.asList(Collections.singletonMap("numbers", Arrays.asList(171L)))},
        };
        for (Object[] b : bad) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put((String) b[0], b[1]);
            try {
                PhoneBackup.fromMap(m);
                fail("accepted " + Arrays.toString(b));
            } catch (Backup.Failure e) {
                assertEquals(Backup.ERROR_MALFORMED, e.code);
            }
        }
    }

    @Test public void numberFirstThenNameEachContactOnce() {
        List<PhoneBackup.Candidate> phone = Arrays.asList(
                contact(1, "Anna", "0170 1111111"),
                contact(2, "Maria Schmidt", "0171 2345678"),
                contact(3, "Maria Schmidt", "0172 9999999"),
                contact(4, "Dr. Weber", "0221 98765"));
        PhoneBackup.Match m = PhoneBackup.match(Arrays.asList(
                // Name says 2, but its number is contact 3's: the number wins.
                fav("Maria Schmidt", "0172 9999999"),
                // Same name again, no number: takes the other Maria, not 3 twice.
                fav("maria  schmidt "),
                // Number written with the country code: same number in Germany.
                fav("Weber", "+49 221 98765"),
                fav("Unknown Person", "0999 000000"),
                fav("Anna")), phone, AutomationTest.DE);
        assertEquals(Arrays.asList(3L, 2L, 4L, 1L), m.ids);
        assertEquals(Arrays.asList("Unknown Person"), m.unmatched);
    }

    @Test public void aNameMatchNeverTakesAContactAnotherFavouritesNumberFound() {
        List<PhoneBackup.Candidate> phone = Arrays.asList(contact(7, "Anna", "0170 1111111"));
        PhoneBackup.Match m = PhoneBackup.match(Arrays.asList(
                fav("Anna"), fav("Mum", "0170 1111111")), phone, AutomationTest.DE);
        assertEquals(Arrays.asList(7L), m.ids);
        assertEquals(Arrays.asList("Anna"), m.unmatched);
    }

    @Test public void noHomeCountryOnlySameDigits() {
        List<PhoneBackup.Candidate> phone = Arrays.asList(contact(1, "X", "0221 98765"));
        assertEquals(Arrays.asList("Weber"),
                PhoneBackup.match(Arrays.asList(fav("Weber", "+49 221 98765")), phone, null).unmatched);
        assertEquals(Arrays.asList(1L),
                PhoneBackup.match(Arrays.asList(fav("Weber", "0221/98765")), phone, null).ids);
    }

    @Test public void anEmptyFavouriteMatchesNothing() {
        PhoneBackup.Match m = PhoneBackup.match(Arrays.asList(fav("")),
                Arrays.asList(contact(1, "", "")), null);
        assertTrue(m.ids.isEmpty());
        assertEquals(Arrays.asList("?"), m.unmatched);
    }
}
