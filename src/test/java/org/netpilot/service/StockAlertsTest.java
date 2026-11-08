package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.netpilot.model.RouterUser;
import org.netpilot.service.StockAlerts.Low;

class StockAlertsTest {

    private static final String BATCH = "NetPilot 2026-11-08 15:59 #13eb";

    private static RouterUser card(String name, String profile, boolean used, boolean disabled, String comment) {
        return new RouterUser("*1", name, profile, "all", used ? Duration.ofHours(1) : Duration.ZERO, "8h",
                used ? 10 : 0, used ? 10 : 0, disabled, comment);
    }

    @Test
    void reportsPackagesBelowTheLimitEmptiestFirst() {
        List<RouterUser> users = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            users.add(card("a" + i, "5M", false, false, BATCH)); // 12 ready: enough
        }
        for (int i = 0; i < 3; i++) {
            users.add(card("b" + i, "1M", false, false, BATCH)); // 3 ready
        }
        users.add(card("b-used", "1M", true, false, BATCH));
        users.add(card("b-off", "1M", false, true, BATCH)); // disabled is not for sale
        users.add(card("c-used", "2M", true, false, BATCH)); // sold out
        users.add(card("admin", "default", false, false, "")); // hand-made: not stock
        users.add(new RouterUser("*2", "default-trial", "trial", "all", Duration.ZERO, "", 0, 0, false, BATCH));

        assertEquals(List.of(new Low("2M", 0), new Low("1M", 3)), StockAlerts.low(users, 10));
    }

    @Test
    void zeroTurnsAlertsOffAndAnExactCountIsEnough() {
        List<RouterUser> users = List.of(card("x", "1M", false, false, BATCH), card("y", "1M", false, false, BATCH));

        assertEquals(List.of(), StockAlerts.low(users, 0));
        assertEquals(List.of(), StockAlerts.low(users, 2), "2 left with a limit of 2 is not below it");
        assertEquals(List.of(new Low("1M", 2)), StockAlerts.low(users, 3));
    }
}
