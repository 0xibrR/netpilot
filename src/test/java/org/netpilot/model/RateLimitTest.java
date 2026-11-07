package org.netpilot.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RateLimitTest {

    @Test
    void readsRouterOsRateLimits() {
        assertEquals(new RateLimit("2M", "2M"), RateLimit.parse("2M/2M"));
        assertEquals(new RateLimit("100M", "1G"), RateLimit.parse("100m/1g"));
        assertEquals(new RateLimit("512k", "512k"), RateLimit.parse("512K"), "one value applies to both sides");
        assertEquals(new RateLimit("2M", "4M"), RateLimit.parse("2M/4M 4M/8M 1M/2M 10/10"), "bursts are ignored");
        assertTrue(RateLimit.parse("").unlimited());
        assertEquals(new RateLimit("", "10M"), RateLimit.parse("0/10M"));
    }

    @Test
    void writesRouterOsForm() {
        assertEquals("100M/1G", new RateLimit("100M", "1G").format());
        assertEquals("0/1000M", new RateLimit("", "1000M").format(), "only download limited");
        assertEquals("", new RateLimit("", "").format());
    }

    @Test
    void anySpeedTheUserTypesIsAccepted() {
        for (String speed : List.of("1G", "1000M", "500M", "40M", "512k", "2.5M", "64000", "")) {
            assertTrue(RateLimit.validRate(speed), speed);
        }
        assertFalse(RateLimit.validRate("fast"));
        assertFalse(RateLimit.valid("10M/abc"));
    }

    @Test
    void profilesAreValidated() {
        assertEquals(List.of(), new HotspotProfile("", "Fiber 1G", "100M/1G", 2).validate());
        assertEquals(List.of(), new HotspotProfile("", "باقة-الشهر", "", 1).validate(), "Arabic names work");
        assertEquals(2, new HotspotProfile("", "", "x/y", 1).validate().size());
        assertEquals(1, new HotspotProfile("", "a", "", 0).sharedUsers(), "at least one device");
        assertTrue(new HotspotProfile("*0", "default", "", 1).builtIn());
    }
}
