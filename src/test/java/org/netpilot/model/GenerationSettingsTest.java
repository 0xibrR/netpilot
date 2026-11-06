package org.netpilot.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationSettingsTest {

    private static GenerationSettings withUptime(String uptime) {
        GenerationSettings d = GenerationSettings.defaults();
        return new GenerationSettings(d.count(), d.usernameMode(), d.usernameLength(), d.usernameStart(),
                d.passwordMode(), d.passwordLength(), d.passwordStart(), d.passwordPrefix(),
                d.profile(), d.server(), uptime);
    }

    @Test
    void defaultsAreValid() {
        assertTrue(GenerationSettings.defaults().validate().isEmpty());
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
        GenerationSettings d = GenerationSettings.defaults();
        GenerationSettings s = new GenerationSettings(d.count(), d.usernameMode(), d.usernameLength(),
                d.usernameStart(), d.passwordMode(), d.passwordLength(), d.passwordStart(), "",
                "2M\" disabled=yes", "all", "1h");
        assertFalse(s.validate().isEmpty());
    }

    @Test
    void lengthIsIgnoredForModesThatDoNotUseIt() {
        GenerationSettings s = new GenerationSettings(10, UsernameMode.SEQUENTIAL, 0, 1,
                PasswordMode.SEQUENTIAL, 0, 1, "", "2M", "all", "1h");
        assertTrue(s.validate().isEmpty());
    }
}
