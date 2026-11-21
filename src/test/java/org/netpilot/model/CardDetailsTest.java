package org.netpilot.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CardDetailsTest {

    private static CardDetails card(String name, String password, String profile, boolean disabled, String address,
            String mac, String uptime, long bytes) {
        return new CardDetails("*5", name, password, "all", profile, "", disabled, address, mac, uptime, bytes,
                Duration.ZERO, 0, 0);
    }

    @Test
    void onlyWhatChangedIsSent() {
        CardDetails before = card("48291", "", "1M", false, "", "", "8h", 0);
        assertEquals(Map.of(), before.changesTo(card("48291", "", "1M", false, "", "", "8h", 0)), "nothing changed");

        CardDetails after = card("48291", "s3cret", "2M", true, "10.10.0.50", "aa:bb:cc:dd:ee:ff", "", 1_073_741_824L);
        assertEquals(Map.of("password", "s3cret", "profile", "2M", "disabled", "true", "address", "10.10.0.50",
                "mac-address", "AA:BB:CC:DD:EE:FF", "limit-uptime", "0s", "limit-bytes-total", "1073741824"),
                before.changesTo(after), "no time limit is sent as 0s, MAC in capitals");
        assertEquals(Map.of("name", "48292"), before.changesTo(card(" 48292 ", "", "1M", false, "", "", "8h", 0)));
    }

    @Test
    void settingsTheRouterWouldRefuseAreCaughtFirst() {
        assertEquals(List.of(), card("48291", "pw", "1M", false, "10.10.0.50", "AA:BB:CC:DD:EE:FF", "1d2h", 0)
                .problems());
        assertEquals(List.of(), card("48291", "", "1M", false, "", "", "01:30:00", 0).problems());
        assertEquals(6, new CardDetails("*5", "", "a\"b", "all", "1M", "x\"", false, "10.10.0.300", "AA:BB", "8 hours",
                0, Duration.ZERO, 0, 0).problems().size(), "name, password, comment, time, MAC and IP");
        assertEquals("all", new CardDetails("*5", "x", "", " ", "1M", null, false, null, null, null, -5, null, 0, 0)
                .server(), "no server means every server");
    }

    @Test
    void aCardIsUnusedOnlyWithoutTimeAndData() {
        assertTrue(card("x", "", "1M", false, "", "", "", 0).neverUsed());
        assertFalse(new CardDetails("*5", "x", "", "all", "1M", "", false, "", "", "", 0, Duration.ofSeconds(1), 0, 0)
                .neverUsed());
        assertFalse(new CardDetails("*5", "x", "", "all", "1M", "", false, "", "", "", 0, Duration.ZERO, 10, 0)
                .neverUsed());
    }
}
