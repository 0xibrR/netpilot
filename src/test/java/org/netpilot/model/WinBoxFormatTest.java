package org.netpilot.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Numbers taken from a real WinBox screen, so NetPilot shows exactly the same. */
class WinBoxFormatTest {

    private static final long MIB = 1024L * 1024;

    @Test
    void sizesLookLikeWinBox() {
        assertEquals("0 B", DataSize.format(0));
        assertEquals("3000 B", DataSize.format(3000));
        assertEquals("524.3 MiB", DataSize.format(Math.round(524.3 * MIB)));
        assertEquals("5078.1 MiB", DataSize.format(Math.round(5078.1 * MIB)), "MiB until five GiB");
        assertEquals("4905.9 MiB", DataSize.format(Math.round(4905.9 * MIB)));
        assertEquals("5.9 GiB", DataSize.format(Math.round(5.9 * 1024 * MIB)));
        assertEquals("48.4 GiB", DataSize.format(Math.round(48.4 * 1024 * MIB)));
    }

    @Test
    void uptimesLookLikeWinBox() {
        assertEquals("2d 09:56:36", RouterDuration.clock(Duration.parse("P2DT9H56M36S")));
        assertEquals("13d 06:09:00", RouterDuration.clock(Duration.parse("P13DT6H9M")));
        assertEquals("17:33:03", RouterDuration.clock(Duration.parse("PT17H33M3S")));
        assertEquals("00:00:00", RouterDuration.clock(Duration.ZERO));
    }
}
