package app.phueber.seniorphone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
        DialMatch.Result r = DialMatch.match(book, "01712345678");
        assertEquals(DialMatch.Kind.EXACT, r.kind);
        assertEquals("Maria Schmidt", r.entry.name);
    }

    @Test public void exactSameDigits() {
        assertEquals("Peter Huber", DialMatch.match(book, "01709876111").entry.name);
    }

    @Test public void uniquePrefixOffersTheContact() {
        DialMatch.Result r = DialMatch.match(book, "0171");
        assertEquals(DialMatch.Kind.PREFIX, r.kind);
        assertEquals("Maria Schmidt", r.entry.name);
        assertEquals("Peter Huber", DialMatch.match(book, "01709").entry.name);
    }

    @Test public void tooShortOrAmbiguousSaysNothing() {
        assertNull(DialMatch.match(book, "017"));
        assertNull(DialMatch.match(book, "0891"));   // two numbers start 0891
        assertNull(DialMatch.match(book, "05551"));
        assertNull(DialMatch.match(book, ""));
        assertNull(DialMatch.match(null, "0171"));
    }

    @Test public void internationalTypedMatchesInternationalSaved() {
        assertEquals("Maria Schmidt", DialMatch.match(book, "+49171").entry.name);
    }
}
