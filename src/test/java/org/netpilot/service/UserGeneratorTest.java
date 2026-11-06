package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.netpilot.model.GenerationSettings;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.PasswordMode;
import org.netpilot.model.UsernameMode;

class UserGeneratorTest {

    private final UserGenerator generator = new UserGenerator(new Random(42));

    private static GenerationSettings settings(int count, UsernameMode um, int ul, int us,
            PasswordMode pm, int pl, int ps, String prefix) {
        return new GenerationSettings(count, um, ul, us, pm, pl, ps, prefix, "2M", "all", "1h");
    }

    @Test
    void randomUsernamesAreUniqueAndHaveRequestedLength() {
        List<HotspotUser> users = generator.generate(
                settings(2_000, UsernameMode.RANDOM_NUMERIC, 5, 1, PasswordMode.RANDOM_NUMERIC, 6, 0, ""));

        Set<String> names = new HashSet<>();
        for (HotspotUser u : users) {
            assertTrue(names.add(u.username()), "duplicate " + u.username());
            assertTrue(u.username().matches("\\d{5}"));
            assertTrue(u.password().matches("\\d{6}"));
        }
        assertEquals(2_000, users.size());
    }

    @Test
    void sequentialUsernamesStartFromConfiguredNumber() {
        List<HotspotUser> users = generator.generate(
                settings(3, UsernameMode.SEQUENTIAL, 6, 41, PasswordMode.SEQUENTIAL, 6, 5001, ""));

        assertEquals(List.of("user0041", "user0042", "user0043"), users.stream().map(HotspotUser::username).toList());
        assertEquals(List.of("5001", "5002", "5003"), users.stream().map(HotspotUser::password).toList());
    }

    @Test
    void sequencePaddingGrowsWithLargeNumbers() {
        List<HotspotUser> users = generator.generate(
                settings(2, UsernameMode.PROFILE_BASED, 6, 99_999, PasswordMode.SAME_AS_USERNAME, 6, 0, ""));

        assertEquals("2M099999", users.get(0).username(), "padded to the width of the last number");
        assertEquals("2M100000", users.get(1).username());
        assertEquals(users.get(1).username(), users.get(1).password());
    }

    @Test
    void prefixPasswordMode() {
        HotspotUser user = generator.generate(
                settings(1, UsernameMode.SEQUENTIAL, 6, 1, PasswordMode.PREFIX_USERNAME, 6, 0, "vip")).getFirst();

        assertEquals("vipuser0001", user.password());
    }

    @Test
    void usersCarryNetworkSettings() {
        HotspotUser user = generator.generate(
                settings(1, UsernameMode.RANDOM_ALPHANUMERIC, 8, 1, PasswordMode.RANDOM_ALPHANUMERIC, 10, 0, ""))
                .getFirst();

        assertEquals("2M", user.profile());
        assertEquals("all", user.server());
        assertEquals("1h", user.limitUptime());
        assertEquals(8, user.username().length());
        assertEquals(10, user.password().length());
    }

    @Test
    void rejectsLengthTooShortForUniqueNames() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> generator.generate(
                settings(5_000, UsernameMode.RANDOM_NUMERIC, 4, 1, PasswordMode.RANDOM_NUMERIC, 6, 0, "")));

        assertTrue(e.getMessage().contains("too short"));
    }

    @Test
    void rejectsInvalidSettings() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(
                settings(0, UsernameMode.RANDOM_NUMERIC, 6, 1, PasswordMode.RANDOM_NUMERIC, 6, 0, "")));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(
                settings(1, UsernameMode.SEQUENTIAL, 6, 1, PasswordMode.PREFIX_USERNAME, 6, 0, "")));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(
                settings(1, UsernameMode.SEQUENTIAL, 6, 1, PasswordMode.PREFIX_USERNAME, 6, 0, "a b")));
    }
}
