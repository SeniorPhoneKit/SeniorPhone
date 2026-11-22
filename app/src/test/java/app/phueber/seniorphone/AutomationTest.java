package app.phueber.seniorphone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class AutomationTest {

    private static ContactInfo c(long id, String name) {
        return new ContactInfo(id, "k" + id, name, false, null, null);
    }

    @Test public void actionsAreMatchedLeniently() {
        assertEquals(Automation.CALL, Automation.action(" APP.PHUEBER.SENIORPHONE.ACTION.call ",
                Automation.CALL, Automation.OPEN_CONTACT));
        assertNull(Automation.action(Automation.LOCK, Automation.CALL, Automation.OPEN_CONTACT));
        assertNull(Automation.action(null, Automation.CALL));
    }

    @Test public void speakerState() {
        assertEquals(Automation.Speaker.ON, Automation.parseSpeaker(" On "));
        assertEquals(Automation.Speaker.ON, Automation.parseSpeaker("true"));
        assertEquals(Automation.Speaker.OFF, Automation.parseSpeaker("OFF"));
        assertEquals(Automation.Speaker.OFF, Automation.parseSpeaker("0"));
        assertEquals(Automation.Speaker.TOGGLE, Automation.parseSpeaker("toggle"));
        assertEquals(Automation.Speaker.TOGGLE, Automation.parseSpeaker(null));
        assertEquals(Automation.Speaker.TOGGLE, Automation.parseSpeaker("  "));
        assertNull(Automation.parseSpeaker("loud"));
    }

    @Test public void themes() {
        assertEquals(Palette.Theme.LIGHT, Automation.parseTheme("Light"));
        assertEquals(Palette.Theme.DARK, Automation.parseTheme(" dark "));
        assertEquals(Palette.Theme.HIGH_CONTRAST, Automation.parseTheme("high-contrast"));
        assertEquals(Palette.Theme.HIGH_CONTRAST, Automation.parseTheme("High Contrast"));
        assertEquals(Palette.Theme.HIGH_CONTRAST, Automation.parseTheme("HIGH_CONTRAST"));
        assertEquals(Palette.Theme.COLOURFUL, Automation.parseTheme("colorful"));
        assertNull(Automation.parseTheme("pink"));
        assertNull(Automation.parseTheme(null));
    }

    @Test public void numbersAreCleanedAndNeedADigit() {
        assertEquals("+491712345678", Automation.parseNumber(" +49 (0)171 234-5678 "));
        assertNull(Automation.parseNumber("+"));
        assertNull(Automation.parseNumber("abc"));
        assertNull(Automation.parseNumber(null));
        assertNull(Automation.parseNumber("**21*01711234567#")); // a code, not a number
        assertNull(Automation.parseNumber("*100#"));
    }

    @Test public void names() {
        assertEquals("Maria", Automation.parseName("  Maria "));
        assertNull(Automation.parseName("   "));
        assertNull(Automation.parseName(null));
    }

    @Test public void exactNameWinsIgnoringCaseAndAccents() {
        ContactInfo mueller = c(1, "Maria Müller");
        ContactInfo marias = c(2, "Maria Müller-Schmidt");
        List<ContactInfo> all = Arrays.asList(marias, mueller);
        assertSame(mueller, Automation.pickContact(all, "maria muller"));
    }

    @Test public void uniquePartialName() {
        ContactInfo anna = c(1, "Anna Schmidt");
        List<ContactInfo> all = Arrays.asList(anna, c(2, "Maria Müller"));
        assertSame(anna, Automation.pickContact(all, "anna"));
    }

    @Test public void ambiguousOrUnknownNameIsNobody() {
        List<ContactInfo> all = Arrays.asList(c(1, "Maria Müller"), c(2, "Maria Schmidt"));
        assertNull(Automation.pickContact(all, "Maria"));
        assertNull(Automation.pickContact(Arrays.asList(c(1, "Anna"), c(2, "anna")), "Anna"));
        assertNull(Automation.pickContact(all, "Otto"));
        assertNull(Automation.pickContact(all, "  "));
        assertNull(Automation.pickContact(Collections.emptyList(), "Maria"));
    }

    /** A phone in a country with a trunk 0 (Germany +49, Austria +43): "0171 …" is "+cc 171 …". */
    static Automation.Home trunkZero(String cc) {
        return n -> {
            String d = n.replaceAll("[^0-9]", "");
            return d.startsWith("0") && !d.startsWith("00") ? "+" + cc + d.substring(1) : null;
        };
    }

    static final Automation.Home DE = trunkZero("49");
    static final Automation.Home AT = trunkZero("43");
    /** The US: ten digits, no trunk 0. */
    static final Automation.Home US = n -> {
        String d = n.replaceAll("[^0-9]", "");
        return d.length() == 10 ? "+1" + d : null;
    };

    @Test public void phoneByDigitsOrNationalForm() {
        List<String> numbers = Arrays.asList("030 1234567", "0171 2345678");
        assertEquals(1, Automation.pickPhone(numbers, "01712345678", DE));
        assertEquals(1, Automation.pickPhone(numbers, "+49 171 2345678", DE));
        assertEquals(0, Automation.pickPhone(numbers, "(030) 123-4567", DE));
        assertEquals(-1, Automation.pickPhone(numbers, "0172 9999999", DE));
        assertEquals(-1, Automation.pickPhone(numbers, "4567", DE));
        assertEquals(-1, Automation.pickPhone(numbers, "", DE));
    }

    @Test public void sameEndingInAnotherAreaIsAnotherNumber() {
        List<String> berlin = Collections.singletonList("+49 30 12345678");
        assertEquals(-1, Automation.pickPhone(berlin, "+49 40 12345678", DE));
        assertEquals(-1, Automation.pickPhone(berlin, "040 12345678", DE));
        assertEquals(0, Automation.pickPhone(berlin, "030 12345678", DE));
        assertEquals(0, Automation.pickPhone(berlin, "0049 30 12345678", DE));
        assertEquals(-1, Automation.pickPhone(Collections.singletonList("030 12345678"), "040 12345678", DE));
    }

    @Test public void nationalAndInternationalForms() {
        assertTrue(Automation.sameNumber("+49 (0)171 2345678", "0171 2345678", DE));
        assertTrue(Automation.sameNumber("0171 2345678", "+49 171 2345678", DE));
        assertTrue(Automation.sameNumber("0171 2345678", "0049 171 2345678", DE));
        assertTrue(Automation.sameNumber("+1 212 555 1234", "212 555 1234", US)); // no trunk 0
        assertTrue(Automation.sameNumber("+43 1 234567", "01 234567", AT));
        assertFalse(Automation.sameNumber("+49 171 2345678", "0172 2345678", DE));
        assertFalse(Automation.sameNumber("+49 89 1234", "089 1234".substring(0, 5), DE)); // too short
        assertFalse(Automation.sameNumber(null, "0171", DE));
    }

    @Test public void nationalNumberIsOnlyOneOfTheOwnCountry() {
        // A caller from abroad must not get a German contact's name and picture.
        assertFalse(Automation.sameNumber("+43 171 2345678", "0171 2345678", DE));
        assertFalse(Automation.sameNumber("0171 2345678", "+43 171 2345678", DE));
        assertEquals(-1, Automation.pickPhone(Collections.singletonList("0171 2345678"), "+43 171 2345678", DE));
        // The same pair on an Austrian phone: the +43 one is the national one.
        assertTrue(Automation.sameNumber("+43 171 2345678", "0171 2345678", AT));
        assertFalse(Automation.sameNumber("+49 171 2345678", "0171 2345678", AT));
        // Country unknown: the two forms never match; same digits still do.
        assertFalse(Automation.sameNumber("+49 171 2345678", "0171 2345678", null));
        assertTrue(Automation.sameNumber("0171 2345678", "0171-2345678", null));
        // Not a valid national number here: no match.
        assertFalse(Automation.sameNumber("+1 212 555 1234", "212 555 123", US));
    }

    @Test public void twoInternationalNumbersNeedTheSameCountryCode() {
        assertFalse(Automation.sameNumber("+43 171 2345678", "+49 171 2345678", DE));
        assertTrue(Automation.sameNumber("+49 (0)171 2345678", "+49 171 2345678", DE));
        assertTrue(Automation.sameNumber("0049 171 2345678", "+49 171 2345678", null));
    }
}
