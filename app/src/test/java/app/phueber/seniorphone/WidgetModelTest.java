package app.phueber.seniorphone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

public class WidgetModelTest {
    private static ContactInfo c(long id, String name, boolean starred) {
        return new ContactInfo(id, "k" + id, name, starred, null, null);
    }

    @Test
    public void firstNameIsTheFirstWord() {
        assertEquals("Maria", WidgetModel.firstName("Maria Schmidt"));
        assertEquals("Maria", WidgetModel.firstName("  Maria   Anna Schmidt "));
        assertEquals("Peter", WidgetModel.firstName("Peter"));
        assertEquals("Ömer", WidgetModel.firstName("Ömer Yılmaz"));
    }

    @Test
    public void firstNameOfLastCommaFirst() {
        assertEquals("Maria", WidgetModel.firstName("Schmidt, Maria"));
        assertEquals("Schmidt", WidgetModel.firstName("Schmidt,"));
    }

    @Test
    public void firstNameSkipsTitles() {
        assertEquals("Maria", WidgetModel.firstName("Dr. Maria Schmidt"));
        assertEquals("Dr.", WidgetModel.firstName("Dr."));
        assertEquals("Hausarzt", WidgetModel.firstName("Dr. med. Hausarzt"));
    }

    @Test
    public void numbersAndSymbolsStayWhole() {
        assertEquals("+49 170 1234567", WidgetModel.firstName("+49 170 1234567"));
        assertEquals("(Arzt) Müller", WidgetModel.firstName(" (Arzt) Müller"));
    }

    @Test
    public void noName() {
        assertEquals("", WidgetModel.firstName(null));
        assertEquals("", WidgetModel.firstName("   "));
    }

    @Test
    public void favouritesAreTheStarredInListOrder() {
        List<ContactInfo> all = new ArrayList<>(Arrays.asList(
                c(1, "Zoe", true), c(2, "Peter Huber", false), c(3, "Ärztin", true),
                c(4, "+49 170", true), c(5, "Maria Schmidt", true)));
        List<ContactInfo> fav = WidgetModel.favourites(all,
                ContactList.comparator(Collator.getInstance(Locale.GERMAN)));
        List<String> names = new ArrayList<>();
        for (ContactInfo i : fav) names.add(i.name);
        assertEquals(Arrays.asList("Ärztin", "Maria Schmidt", "Zoe", "+49 170"), names);
    }

    @Test
    public void favouritesOfNone() {
        assertEquals(0, WidgetModel.favourites(new ArrayList<>(),
                ContactList.comparator(Collator.getInstance(Locale.GERMAN))).size());
    }

    @Test
    public void pickRoundTrips() {
        WidgetModel.Pick p = WidgetModel.decode(WidgetModel.encode(new WidgetModel.Pick(42, "0r1-abc:x")));
        assertEquals(42, p.id);
        assertEquals("0r1-abc:x", p.lookupKey);
        assertEquals("", WidgetModel.decode("7:").lookupKey);
        assertEquals("", new WidgetModel.Pick(7, null).lookupKey);
    }

    @Test
    public void brokenPicksAreNone() {
        assertNull(WidgetModel.decode(null));
        assertNull(WidgetModel.decode(""));
        assertNull(WidgetModel.decode("abc"));
        assertNull(WidgetModel.decode(":key"));
        assertNull(WidgetModel.decode("x1:key"));
        assertNull(WidgetModel.decode("-3:key"));
    }
}
