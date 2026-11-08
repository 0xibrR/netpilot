package org.netpilot.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GenerationSettingsTest {

    private static GenerationSettings card(int count, CodeFormat format, String prefix, String suffix, int length,
            int passwordLength) {
        return new GenerationSettings(count, format, prefix, suffix, length, passwordLength, "2M", "all", "1h");
    }

    private static GenerationSettings withUptime(String uptime) {
        return new GenerationSettings(20, CodeFormat.DIGITS, "", "", 8, 0, "2M", "all", uptime);
    }

    @Test
    void defaultsAreValidSingleNumberCards() {
        assertTrue(GenerationSettings.defaults().validate().isEmpty());
        assertTrue(GenerationSettings.defaults().pin());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "30m", "1h", "1d12h", "1w", "01:30:00"})
    void acceptsRouterOsDurations(String uptime) {
        assertTrue(withUptime(uptime).validate().isEmpty(), uptime);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1 hour", "h1", "1h;", "abc"})
    void rejectsBadDurations(String uptime) {
        assertFalse(withUptime(uptime).validate().isEmpty(), uptime);
    }

    @Test
    void rejectsQuotesInProfile() {
        GenerationSettings s = new GenerationSettings(10, CodeFormat.DIGITS, "", "", 8, 0, "2M\" disabled=yes", "all",
                "1h");
        assertFalse(s.validate().isEmpty());
    }

    @Test
    void lengthIncludesPrefixAndSuffix() {
        GenerationSettings s = card(10, CodeFormat.DIGITS, "9", "x", 8, 0);
        assertEquals(6, s.randomLength());
        assertEquals(BigInteger.TEN.pow(6), s.possibleCodes());
        assertTrue(s.validate().isEmpty());
    }

    @Test
    void prefixAndSuffixMustLeaveRandomCharacters() {
        assertFalse(card(1, CodeFormat.DIGITS, "1234", "56", 8, 0).validate().isEmpty(), "only 2 random left");
        assertFalse(card(1, CodeFormat.DIGITS, "9-", "", 8, 0).validate().isEmpty(), "letters and digits only");
    }

    @Test
    void passwordLengthIsUpToTheUser() {
        for (int length : new int[]{0, 1, 2, 3, 6, 32}) {
            assertTrue(card(10, CodeFormat.DIGITS, "", "", 8, length).validate().isEmpty(), "length " + length);
        }
        assertFalse(card(10, CodeFormat.DIGITS, "", "", 8, 33).validate().isEmpty());
        assertFalse(card(10, CodeFormat.DIGITS, "", "", 8, -1).validate().isEmpty());
    }

    @Test
    void tooFewPossibleNumbersForTheCountIsRefused() {
        assertFalse(card(5_000, CodeFormat.DIGITS, "", "", 5, 0).validate().isEmpty(),
                "100,000 numbers, about 2,000 well spaced, for 5,000");
        assertTrue(card(5_000, CodeFormat.LETTERS_AND_DIGITS, "", "", 5, 0).validate().isEmpty());
    }
}
