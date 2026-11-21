package app.phueber.seniorphone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

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

    @Test public void phoneByDigitsOrLastNine() {
        List<String> numbers = Arrays.asList("030 1234567", "0171 2345678");
        assertEquals(1, Automation.pickPhone(numbers, "01712345678"));
        assertEquals(1, Automation.pickPhone(numbers, "+49 171 2345678"));
        assertEquals(0, Automation.pickPhone(numbers, "(030) 123-4567"));
        assertEquals(-1, Automation.pickPhone(numbers, "0172 9999999"));
        assertEquals(-1, Automation.pickPhone(numbers, "4567"));
        assertEquals(-1, Automation.pickPhone(numbers, ""));
    }
}
