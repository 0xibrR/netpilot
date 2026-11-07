package org.netpilot.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class TimeLimitTest {

    @Test
    void anyAmountTheUserChooses() {
        assertEquals("45m", new TimeLimit(45, TimeLimit.Unit.MINUTES).routerText());
        assertEquals("8h", new TimeLimit(8, TimeLimit.Unit.HOURS).routerText());
        assertEquals("3d", new TimeLimit(3, TimeLimit.Unit.DAYS).routerText());
        assertEquals("2w", new TimeLimit(2, TimeLimit.Unit.WEEKS).routerText());
        assertEquals("30d", new TimeLimit(1, TimeLimit.Unit.MONTHS).routerText(), "RouterOS has no month unit");
        assertEquals("180d", new TimeLimit(6, TimeLimit.Unit.MONTHS).routerText());
        assertEquals("1h", new TimeLimit(0, TimeLimit.Unit.HOURS).routerText(), "at least one unit");
    }

    @Test
    void savedTextIsShownInTheLargestExactUnit() {
        assertEquals(Optional.of(new TimeLimit(8, TimeLimit.Unit.HOURS)), TimeLimit.parse("8h"));
        assertEquals(Optional.of(new TimeLimit(36, TimeLimit.Unit.HOURS)), TimeLimit.parse("1d12h"));
        assertEquals(Optional.of(new TimeLimit(1, TimeLimit.Unit.WEEKS)), TimeLimit.parse("7d"));
        assertEquals(Optional.of(new TimeLimit(1, TimeLimit.Unit.MONTHS)), TimeLimit.parse("30d"));
        assertEquals(Optional.of(new TimeLimit(3, TimeLimit.Unit.MONTHS)), TimeLimit.parse("90d"));
        assertEquals(Optional.of(new TimeLimit(4, TimeLimit.Unit.WEEKS)), TimeLimit.parse("28d"));
        assertEquals(Optional.of(new TimeLimit(10, TimeLimit.Unit.DAYS)), TimeLimit.parse("10d"));
        assertEquals(Optional.of(new TimeLimit(90, TimeLimit.Unit.MINUTES)), TimeLimit.parse("1h30m"));
    }

    @Test
    void blankOrUnreadableMeansNoLimit() {
        assertEquals(Optional.empty(), TimeLimit.parse(""));
        assertEquals(Optional.empty(), TimeLimit.parse("soon"));
        assertEquals(Optional.empty(), TimeLimit.parse("30s"), "less than a minute is not offered");
    }
}
