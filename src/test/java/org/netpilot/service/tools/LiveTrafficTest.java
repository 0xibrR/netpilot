package org.netpilot.service.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.netpilot.model.ActiveSession;
import org.netpilot.service.tools.LiveTraffic.Rate;
import org.netpilot.service.tools.LiveTraffic.Snapshot;

class LiveTrafficTest {

    private static final Instant T0 = Instant.parse("2026-11-11T10:00:00Z");

    /** A session that uploaded {@code in} and downloaded {@code out} bytes so far. */
    private static ActiveSession session(String id, String user, long in, long out) {
        return new ActiveSession(id, user, "10.5.50.2", "AA:BB:CC:DD:EE:FF", Duration.ofMinutes(3), in, out,
                Optional.empty());
    }

    @Test
    void speedIsTheChangeInBytesOverTheTimeBetweenReadings() {
        LiveTraffic traffic = new LiveTraffic();
        Snapshot first = traffic.update(List.of(session("*1", "ali", 1_000, 10_000)), T0);
        assertTrue(first.perSession().isEmpty(), "one reading cannot give a speed");
        assertEquals(Rate.ZERO, first.total());
        assertTrue(first.history().isEmpty());

        // 5 s later: +625 000 bytes down = 1 Mbps, +62 500 up = 100 kbps
        Snapshot second = traffic.update(List.of(session("*1", "ali", 63_500, 635_000)), T0.plusSeconds(5));
        assertEquals(new Rate(1_000_000, 100_000), second.rateOf("*1").orElseThrow());
        assertEquals(new Rate(1_000_000, 100_000), second.total());
        assertEquals(1, second.history().size());
    }

    @Test
    void theTotalAddsOnlyTheSpeedsThatAreKnown() {
        LiveTraffic traffic = new LiveTraffic();
        traffic.update(List.of(session("*1", "ali", 0, 0), session("*2", "sara", 0, 0)), T0);
        Snapshot s = traffic.update(List.of(session("*1", "ali", 0, 125_000), session("*2", "sara", 0, 250_000),
                session("*3", "new", 999, 999_999)), T0.plusSeconds(1));

        assertEquals(1_000_000, s.rateOf("*1").orElseThrow().downloadBps());
        assertEquals(2_000_000, s.rateOf("*2").orElseThrow().downloadBps());
        assertFalse(s.rateOf("*3").isPresent(), "just logged in: no speed until the next reading");
        assertEquals(3_000_000, s.total().downloadBps());
    }

    @Test
    void aCounterThatWentDownOrAnotherUserOnTheSameIdHasNoSpeed() {
        LiveTraffic traffic = new LiveTraffic();
        traffic.update(List.of(session("*1", "ali", 50_000, 900_000), session("*2", "sara", 10, 10)), T0);

        Snapshot s = traffic.update(List.of(session("*1", "ali", 100, 200), session("*2", "omar", 500, 500)),
                T0.plusSeconds(5));
        assertTrue(s.perSession().isEmpty(), "never negative, never mixed between two people");
        assertEquals(Rate.ZERO, s.total());

        Snapshot next = traffic.update(List.of(session("*1", "ali", 100, 125_200)), T0.plusSeconds(6));
        assertEquals(1_000_000, next.rateOf("*1").orElseThrow().downloadBps(), "measured again from the new start");
    }

    @Test
    void readingsTooCloseTogetherWaitForTheNextOne() {
        LiveTraffic traffic = new LiveTraffic();
        traffic.update(List.of(session("*1", "ali", 0, 0)), T0);

        Snapshot tooSoon = traffic.update(List.of(session("*1", "ali", 0, 5_000)), T0.plusMillis(100));
        assertTrue(tooSoon.perSession().isEmpty());
        assertTrue(tooSoon.history().isEmpty());

        // compared with the reading at T0, not the one 100 ms later
        Snapshot later = traffic.update(List.of(session("*1", "ali", 0, 250_000)), T0.plusSeconds(2));
        assertEquals(1_000_000, later.rateOf("*1").orElseThrow().downloadBps());
    }

    @Test
    void theChartKeepsFiveMinutesAndResetForgetsEverything() {
        LiveTraffic traffic = new LiveTraffic();
        Instant at = T0;
        long bytes = 0;
        for (int i = 0; i <= 70; i++) { // 70 readings, 5 s apart = 350 s
            traffic.update(List.of(session("*1", "ali", 0, bytes)), at);
            at = at.plusSeconds(5);
            bytes += 1_000;
        }
        Snapshot s = traffic.update(List.of(session("*1", "ali", 0, bytes)), at);
        Instant oldest = s.history().get(0).at();
        assertFalse(oldest.isBefore(at.minus(LiveTraffic.WINDOW)));
        assertEquals(61, s.history().size(), "300 s of readings every 5 s, both ends included");

        traffic.reset();
        Snapshot fresh = traffic.update(List.of(session("*1", "ali", 0, bytes + 1_000)), at.plusSeconds(5));
        assertTrue(fresh.perSession().isEmpty());
        assertTrue(fresh.history().isEmpty());
    }

    @Test
    void speedsReadLikeNetworkSpeeds() {
        assertEquals("0 bps", LiveTraffic.format(0));
        assertEquals("950 bps", LiveTraffic.format(950));
        assertEquals("1.5 kbps", LiveTraffic.format(1_500));
        assertEquals("12.3 Mbps", LiveTraffic.format(12_345_678));
        assertEquals("1.0 Gbps", LiveTraffic.format(1_000_000_000));
        assertEquals("0 bps", LiveTraffic.format(-5));
    }

    @Test
    void searchFindsANameAnAddressOrAMac() {
        ActiveSession s = session("*1", "Card-AB12", 0, 0);
        assertTrue(s.matches(""));
        assertTrue(s.matches(null));
        assertTrue(s.matches("ab12"));
        assertTrue(s.matches(" 10.5.50 "));
        assertTrue(s.matches("cc:dd"));
        assertFalse(s.matches("zz"));
        assertEquals(0, new ActiveSession("*2", "u", null, null, null, -5, -5, null).bytesIn(),
                "bad counters are clamped");
    }
}
