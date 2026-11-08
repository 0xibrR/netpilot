package org.netpilot.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CleanupSettingsTest {

    private static final LocalTime THREE = LocalTime.of(3, 0);

    private static LocalDateTime at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 11, day, hour, minute);
    }

    @Test
    void offIsNeverDue() {
        assertFalse(CleanupSettings.defaults().due(at(8, 23, 0)));
    }

    @Test
    void neverRunIsDueOnceTodaysTimeHasPassed() {
        CleanupSettings on = CleanupSettings.defaults().withSchedule(true, THREE);

        assertFalse(on.due(at(8, 2, 59)));
        assertTrue(on.due(at(8, 3, 0)));
        assertTrue(on.due(at(8, 15, 0)));
    }

    @Test
    void runsOncePerDayAndCatchesUpAfterAMissedDay() {
        CleanupSettings ranAt3 = CleanupSettings.defaults().withSchedule(true, THREE).ran(at(8, 3, 0), 5);

        assertFalse(ranAt3.due(at(8, 23, 59)), "already ran today");
        assertFalse(ranAt3.due(at(9, 2, 59)));
        assertTrue(ranAt3.due(at(9, 3, 0)), "next day at 03:00");
        assertTrue(ranAt3.due(at(11, 10, 0)), "NetPilot was closed for days: runs at the next chance");

        CleanupSettings ranEarly = ranAt3.ran(at(9, 1, 0), 0); // a late catch-up before today's 03:00
        assertTrue(ranEarly.due(at(9, 3, 0)), "today's own run still happens");
    }

    @Test
    void keepsTheLastResultAndCleansUpBadValues() {
        CleanupSettings ran = CleanupSettings.defaults().ran(at(8, 3, 0), 14);
        CleanupSettings odd = new CleanupSettings(true, LocalTime.of(4, 30, 12), null, -3);

        assertEquals(Optional.of(at(8, 3, 0)), ran.lastRun());
        assertEquals(14, ran.lastDeleted());
        assertEquals(LocalTime.of(4, 30), odd.at());
        assertEquals(Optional.empty(), odd.lastRun());
        assertEquals(0, odd.lastDeleted());
        assertEquals(ran.lastRun(), ran.withSchedule(true, LocalTime.of(5, 0)).lastRun(), "changing the time keeps it");
    }
}
