package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.netpilot.model.CodeFormat;
import org.netpilot.model.GenerationSettings;
import org.netpilot.model.HotspotUser;

class UserGeneratorTest {

    private final UserGenerator generator = new UserGenerator(new Random(42));

    private static GenerationSettings settings(int count, CodeFormat format, String prefix, String suffix, int length,
            int passwordLength) {
        return new GenerationSettings(count, format, prefix, suffix, length, passwordLength, "2M", "all", "1h");
    }

    @Test
    void cardNumbersAreUniqueAndHaveTheRequestedLength() {
        List<HotspotUser> users = generator.generate(settings(2_000, CodeFormat.DIGITS, "", "", 6, 6));

        Set<String> names = new HashSet<>();
        for (HotspotUser u : users) {
            assertTrue(names.add(u.username()), "duplicate " + u.username());
            assertTrue(u.username().matches("\\d{6}"));
            assertTrue(u.password().matches("\\d{6}"));
        }
        assertEquals(2_000, users.size());
    }

    @Test
    void passwordCanBeTheCardNumberOrOneSharedPassword() {
        GenerationSettings same = new GenerationSettings(10, CodeFormat.DIGITS, "", "", 8,
                org.netpilot.model.PasswordStyle.SAME_AS_CODE, 4, "", "2M", "all", "1h");
        for (HotspotUser u : generator.generate(same)) {
            assertEquals(u.username(), u.password());
        }
        GenerationSettings shared = new GenerationSettings(10, CodeFormat.DIGITS, "", "", 8,
                org.netpilot.model.PasswordStyle.SHARED, 4, "2026", "2M", "all", "1h");
        for (HotspotUser u : generator.generate(shared)) {
            assertEquals("2026", u.password());
        }
        GenerationSettings missing = new GenerationSettings(1, CodeFormat.DIGITS, "", "", 8,
                org.netpilot.model.PasswordStyle.SHARED, 4, "", "2M", "all", "1h");
        assertThrows(IllegalArgumentException.class, () -> generator.generate(missing), "shared needs a password");
    }

    @Test
    void passwordLengthZeroMeansNoPassword() {
        for (HotspotUser u : generator.generate(settings(20, CodeFormat.DIGITS, "", "", 8, 0))) {
            assertEquals("", u.password());
        }
    }

    @Test
    void shortPasswordsFromOneCharacterAreAllowed() {
        for (int length = 1; length <= 3; length++) {
            for (HotspotUser u : generator.generate(settings(20, CodeFormat.DIGITS, "", "", 8, length))) {
                assertEquals(length, u.password().length());
            }
        }
    }

    @Test
    void prefixAndSuffixAreKeptAndCountInTheLength() {
        for (HotspotUser u : generator.generate(settings(50, CodeFormat.DIGITS, "9", "77", 8, 0))) {
            assertTrue(u.username().matches("9\\d{5}77"), u.username());
        }
    }

    @Test
    void lettersAndDigitsAvoidLookAlikes() {
        for (HotspotUser u : generator.generate(settings(200, CodeFormat.LETTERS_AND_DIGITS, "", "", 10, 8))) {
            assertTrue(u.username().matches("[a-km-zA-HJ-NP-Z2-9]{10}"), u.username());
            assertEquals(8, u.password().length());
            assertNotEquals(u.username(), u.password());
        }
    }

    @Test
    void usersCarryNetworkSettings() {
        HotspotUser user = generator.generate(settings(1, CodeFormat.DIGITS, "", "", 8, 0)).getFirst();

        assertEquals("2M", user.profile());
        assertEquals("all", user.server());
        assertEquals("1h", user.limitUptime());
    }

    @Test
    void exampleFollowsTheSettings() {
        assertTrue(generator.example(settings(1, CodeFormat.DIGITS, "90", "", 8, 0)).matches("90\\d{6}"));
    }

    @Test
    void rejectsLengthTooShortForUniqueNumbers() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> generator.generate(settings(5_000, CodeFormat.DIGITS, "", "", 5, 0)));

        assertTrue(e.getMessage().contains("too short"));
    }

    @Test
    void rejectsInvalidSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> generator.generate(settings(0, CodeFormat.DIGITS, "", "", 6, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> generator.generate(settings(1, CodeFormat.DIGITS, "a b", "", 6, 0)));
    }
}
