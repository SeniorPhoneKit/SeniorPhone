package app.phueber.seniorphone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class DialMatchTest {
    private final List<DialMatch.Entry> book = Arrays.asList(
            new DialMatch.Entry("Maria Schmidt", "+49 171 2345678"),
            new DialMatch.Entry("Peter Huber", "0170 9876111"),
            new DialMatch.Entry("Praxis Dr. Kern", "089 123456"),
            new DialMatch.Entry("Pharmacy", "089 123999"));

    @Test public void exactAcrossCountryCode() {
        DialMatch.Result r = DialMatch.match(book, "01712345678", AutomationTest.DE);
        assertEquals(DialMatch.Kind.EXACT, r.kind);
        assertEquals("Maria Schmidt", r.entry.name);
    }

    @Test public void exactSameDigits() {
        assertEquals("Peter Huber", DialMatch.match(book, "01709876111", AutomationTest.DE).entry.name);
    }

    @Test public void uniquePrefixOffersTheContact() {
        DialMatch.Result r = DialMatch.match(book, "0171", AutomationTest.DE);
        assertEquals(DialMatch.Kind.PREFIX, r.kind);
        assertEquals("Maria Schmidt", r.entry.name);
        assertEquals("Peter Huber", DialMatch.match(book, "01709", AutomationTest.DE).entry.name);
    }

    @Test public void tooShortOrAmbiguousSaysNothing() {
        assertNull(DialMatch.match(book, "017", AutomationTest.DE));
        assertNull(DialMatch.match(book, "0891", AutomationTest.DE));   // two numbers start 0891
        assertNull(DialMatch.match(book, "05551", AutomationTest.DE));
        assertNull(DialMatch.match(book, "", AutomationTest.DE));
        assertNull(DialMatch.match(null, "0171", AutomationTest.DE));
    }

    @Test public void internationalTypedMatchesInternationalSaved() {
        assertEquals("Maria Schmidt", DialMatch.match(book, "+49171", AutomationTest.DE).entry.name);
    }

    @Test public void sameEndingInAnotherAreaIsNobody() {
        List<DialMatch.Entry> b = Arrays.asList(new DialMatch.Entry("Oma Berlin", "+49 30 12345678"));
        assertNull(DialMatch.match(b, "04012345678", AutomationTest.DE));
        assertEquals(DialMatch.Kind.EXACT, DialMatch.match(b, "03012345678", AutomationTest.DE).kind);
    }

    @Test public void abroadIsNeverExact() {
        // "0171 …" typed on a German phone is not the Austrian "+43 171 …": no "✓ Name".
        List<DialMatch.Entry> b = Arrays.asList(new DialMatch.Entry("Onkel Wien", "+43 171 2345678"));
        DialMatch.Result r = DialMatch.match(b, "01712345678", AutomationTest.DE);
        assertTrue(r == null || r.kind != DialMatch.Kind.EXACT);
        assertEquals(DialMatch.Kind.EXACT, DialMatch.match(b, "01712345678", AutomationTest.AT).kind);
    }
}
