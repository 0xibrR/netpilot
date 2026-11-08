package org.netpilot.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DataLimitTest {

    private static final long GIB = 1L << 30;

    @Test
    void bytesAndBackLikeWinBox() {
        assertEquals(GIB, new DataLimit(1, DataLimit.Unit.GB).bytes());
        assertEquals(500L << 20, new DataLimit(500, DataLimit.Unit.MB).bytes());
        assertEquals("1 GB", new DataLimit(1, DataLimit.Unit.GB).label());

        assertEquals(Optional.of(new DataLimit(1, DataLimit.Unit.GB)), DataLimit.ofBytes(GIB));
        assertEquals(Optional.of(new DataLimit(1536, DataLimit.Unit.MB)), DataLimit.ofBytes(1536L << 20),
                "1.5 GB is shown in MB to stay exact");
        assertEquals(Optional.empty(), DataLimit.ofBytes(0), "0 = no limit");
        assertEquals(Optional.empty(), DataLimit.ofBytes(1_000_000_000L), "not whole megabytes (set in WinBox)");
        assertEquals(1, new DataLimit(0, null).amount());
        assertEquals(DataLimit.Unit.GB, new DataLimit(0, null).unit());
    }

    @Test
    void aCardEndsWhenItsDataOrItsTimeRunsOut() {
        RouterUser dataLeft = new RouterUser("*1", "a", "1M", "all", Duration.ofMinutes(10), "8h", GIB / 4, GIB / 4,
                false, "", GIB);
        RouterUser dataUsed = new RouterUser("*2", "b", "1M", "all", Duration.ofMinutes(10), "8h", GIB / 2, GIB / 2,
                false, "", GIB);
        RouterUser timeUsed = new RouterUser("*3", "c", "1M", "all", Duration.ofHours(8), "8h", 10, 10, false, "",
                GIB);
        RouterUser noLimits = new RouterUser("*4", "d", "1M", "all", Duration.ofDays(9), "", 5 * GIB, 0, false, "");

        assertFalse(dataLeft.expired());
        assertTrue(dataUsed.expired(), "upload and download together reached 1 GiB");
        assertTrue(timeUsed.expired(), "time ran out first");
        assertFalse(noLimits.expired());
        assertEquals(0, noLimits.limitBytes());
    }

    @Test
    void cardsCarryTheLimitFromTheForm() {
        GenerationSettings old = new GenerationSettings(10, CodeFormat.DIGITS, "", "", 8, PasswordStyle.NONE, 4, "",
                "1M", "all", "8h");
        HotspotUser card = new HotspotUser("12345678", "", "1M", "all", "", 2 * GIB);

        assertEquals(0, old.limitBytes(), "settings saved before data limits have none");
        assertEquals(Optional.of(new DataLimit(2, DataLimit.Unit.GB)), card.dataLimit());
        assertEquals(Optional.empty(), new HotspotUser("x", "", "1M", "all", "1h").dataLimit());
    }
}
