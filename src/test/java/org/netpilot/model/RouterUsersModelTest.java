package org.netpilot.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RouterUsersModelTest {

    @ParameterizedTest
    @CsvSource({
            "30m, 1800",
            "1h30m, 5400",
            "1w2d3h4m5s, 788645",
            "45s, 45",
            "0s, 0",
            "06:13:16, 22396",
            "4d06:13:16, 367996",
            "1w00:00:01, 604801",
            "2s500ms, 2"})
    void parsesRouterOsDurations(String text, long seconds) {
        assertEquals(Optional.of(Duration.ofSeconds(seconds)), RouterDuration.parse(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "abc", "1x", "12:3:4", "m"})
    void unreadableDurationsAreEmpty(String text) {
        assertEquals(Optional.empty(), RouterDuration.parse(text));
    }

    @Test
    void formatsDurationsShortly() {
        assertEquals("0", RouterDuration.format(Duration.ZERO));
        assertEquals("45s", RouterDuration.format(Duration.ofSeconds(45)));
        assertEquals("1m 5s", RouterDuration.format(Duration.ofSeconds(65)));
        assertEquals("1h 20m", RouterDuration.format(Duration.ofMinutes(80)));
        assertEquals("2d 3h", RouterDuration.format(Duration.ofHours(51)));
        assertEquals("1d", RouterDuration.format(Duration.ofDays(1)));
    }

    private static RouterUser user(String name, String uptime, String limit, long bytes, boolean disabled,
            String comment, String profile) {
        return new RouterUser("*1", name, profile, "all", RouterDuration.parse(uptime).orElseThrow(), limit, bytes, 0,
                disabled, comment);
    }

    @Test
    void expiredMeansTimeLimitUsedUp() {
        assertTrue(user("a", "8h", "8h", 1, false, "", "1M").expired());
        assertTrue(user("a", "9h", "8h", 1, false, "", "1M").expired());
        assertFalse(user("a", "7h", "8h", 1, false, "", "1M").expired());
        assertFalse(user("a", "99h", "", 1, false, "", "1M").expired(), "no limit never expires");
        assertFalse(user("a", "99h", "0s", 1, false, "", "1M").expired(), "0 means no limit in RouterOS");
    }

    @Test
    void filterCombinesTextBatchProfileAndStatus() {
        RouterUser fresh = user("714133", "0s", "8h", 0, false, "NetPilot 2026-11-06 21:56 #b062", "1M");
        RouterUser used = user("ABC", "8h", "8h", 500, false, "NetPilot 2026-11-01 10:00 #aaaa", "2M");
        RouterUser off = user("old", "1h", "", 10, true, "", "1M");
        Set<String> online = Set.of("ABC");

        assertTrue(UserFilter.ALL.matches(off, online));
        assertTrue(new UserFilter("abc", "", "", UserFilter.Status.ALL).matches(used, online), "case-insensitive");
        assertTrue(new UserFilter("b062", "", "", UserFilter.Status.ALL).matches(fresh, online), "matches comment");
        assertFalse(new UserFilter("zzz", "", "", UserFilter.Status.ALL).matches(fresh, online));

        assertTrue(new UserFilter("", "NetPilot 2026-11-06 21:56 #b062", "", null).matches(fresh, online));
        assertFalse(new UserFilter("", "NetPilot 2026-11-06 21:56 #b062", "", null).matches(used, online));
        assertFalse(new UserFilter("", "", "2M", null).matches(fresh, online));

        assertTrue(new UserFilter("", "", "", UserFilter.Status.ONLINE).matches(used, online));
        assertFalse(new UserFilter("", "", "", UserFilter.Status.ONLINE).matches(fresh, online));
        assertTrue(new UserFilter("", "", "", UserFilter.Status.NEVER_USED).matches(fresh, online));
        assertFalse(new UserFilter("", "", "", UserFilter.Status.NEVER_USED).matches(used, online));
        assertTrue(new UserFilter("", "", "", UserFilter.Status.EXPIRED).matches(used, online));
        assertTrue(new UserFilter("", "", "", UserFilter.Status.DISABLED).matches(off, online));
        assertFalse(new UserFilter("", "", "", UserFilter.Status.DISABLED).matches(fresh, online));
    }

    @Test
    void trialUserIsBuiltInAndNotACleanupCandidate() {
        RouterUser trial = user("default-trial", "0s", "", 0, false, "counters and limits for trial users", "");

        assertTrue(trial.builtIn());
        assertFalse(user("card", "0s", "", 0, false, "", "1M").builtIn());
        assertFalse(new UserFilter("", "", "", UserFilter.Status.NEVER_USED).matches(trial, Set.of()));
        assertTrue(UserFilter.ALL.matches(trial, Set.of()), "still listed, just protected");
    }
}
