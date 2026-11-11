package org.netpilot.service.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.netpilot.model.LogEntry;
import org.netpilot.model.PingReply;
import org.netpilot.model.TraceHop;
import org.netpilot.service.router.RouterClient;
import org.netpilot.service.router.RouterException;

/** Traceroute walking, hop figures, and following the router log, without a router. */
class NetworkToolsLogicTest {

    private static final String TARGET = "8.8.8.8";

    private static PingReply viaRouter(int ttl, String host, long millis) {
        return new PingReply(ttl - 1, host, Optional.of(Duration.ofMillis(millis)), 64, 84, "TTL exceeded");
    }

    private static PingReply lost(int ttl) {
        return new PingReply(ttl - 1, TARGET, Optional.empty(), 0, 0, "timeout");
    }

    private static PingReply arrived(int ttl, long millis) {
        return new PingReply(ttl - 1, TARGET, Optional.of(Duration.ofMillis(millis)), 117, 84, "");
    }

    /** A router that answers {@code probe} with the given function and nothing else. */
    private static RouterClient router(BiFunction<String, Integer, PingReply> probe) {
        return (RouterClient) Proxy.newProxyInstance(RouterClient.class.getClassLoader(),
                new Class<?>[]{RouterClient.class}, (proxy, method, args) -> {
                    if (method.getName().equals("probe")) {
                        PingReply reply = probe.apply((String) args[0], (Integer) args[1]);
                        if (reply == null) {
                            throw new RouterException(RouterException.Kind.UNREACHABLE, "router gone", null);
                        }
                        return reply;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Collects what a trace reports. */
    private static final class Recorder implements TraceSession.Listener {

        final List<TraceHop> updates = new CopyOnWriteArrayList<>();
        final AtomicReference<List<TraceHop>> hops = new AtomicReference<>();
        final AtomicBoolean reached = new AtomicBoolean();
        final AtomicReference<Optional<Exception>> error = new AtomicReference<>();
        final CountDownLatch first = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);

        @Override
        public void hop(TraceHop hop) {
            updates.add(hop);
            first.countDown();
        }

        @Override
        public void finished(List<TraceHop> all, boolean arrived, Optional<Exception> e) {
            hops.set(all);
            reached.set(arrived);
            error.set(e);
            done.countDown();
        }
    }

    @Test
    void walksTheWayOneTtlAtATimeUntilTheAddressAnswers() throws Exception {
        Recorder recorder = new Recorder();
        RouterClient fake = router((address, ttl) -> switch (ttl) {
            case 1 -> viaRouter(1, "192.168.1.1", 1);
            case 2 -> lost(2); // a router that keeps quiet
            case 3 -> viaRouter(3, "100.64.0.1", 12);
            default -> arrived(ttl, 30);
        });

        new TraceSession(fake, TARGET, recorder).start();

        assertTrue(recorder.done.await(5, TimeUnit.SECONDS));
        List<TraceHop> hops = recorder.hops.get();
        assertTrue(recorder.reached.get());
        assertEquals(4, hops.size(), "stops at the address, not at 30");
        assertEquals("192.168.1.1", hops.get(0).host());
        assertFalse(hops.get(1).responded());
        assertEquals(100, hops.get(1).lossPercent());
        assertTrue(hops.get(3).target());
        assertEquals(TraceSession.PROBES, hops.get(0).sent());
        assertEquals(4 * TraceSession.PROBES, recorder.updates.size(), "a row is updated after each probe");
        assertEquals(Optional.empty(), recorder.error.get());
    }

    @Test
    void givesUpAtTheHopLimitAndCanBeStopped() throws Exception {
        Recorder limited = new Recorder();
        new TraceSession(router((a, ttl) -> lost(ttl)), TARGET, 5, limited).start();
        assertTrue(limited.done.await(5, TimeUnit.SECONDS));
        assertFalse(limited.reached.get());
        assertEquals(5, limited.hops.get().size());

        Recorder stopped = new Recorder();
        AtomicInteger probes = new AtomicInteger();
        TraceSession endless = new TraceSession(router((a, ttl) -> {
            probes.incrementAndGet();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return lost(ttl);
        }), TARGET, stopped).start();
        assertTrue(stopped.first.await(5, TimeUnit.SECONDS));
        endless.stop();
        assertTrue(stopped.done.await(5, TimeUnit.SECONDS));
        assertFalse(endless.running());
        assertTrue(probes.get() < TraceSession.MAX_HOPS * TraceSession.PROBES);
        assertEquals(Optional.empty(), stopped.error.get(), "stopping is not an error");
        assertThrows(IllegalStateException.class, endless::start);
    }

    @Test
    void aRouterErrorEndsTheTraceWithTheReason() throws Exception {
        Recorder recorder = new Recorder();
        new TraceSession(router((a, ttl) -> ttl == 1 ? viaRouter(1, "192.168.1.1", 1) : null), TARGET, recorder)
                .start();
        assertTrue(recorder.done.await(5, TimeUnit.SECONDS));
        assertTrue(recorder.error.get().isPresent());
        assertThrows(IllegalArgumentException.class,
                () -> new TraceSession(router((a, t) -> lost(t)), "not an address!", recorder));
    }

    @Test
    void aHopKeepsBestAverageWorstAndLoss() {
        TraceHop hop = TraceHop.start(2)
                .add(viaRouter(2, "10.0.0.1", 10))
                .add(lost(2))
                .add(viaRouter(2, "10.0.0.1", 30));
        assertEquals("10.0.0.1", hop.host());
        assertEquals(3, hop.sent());
        assertEquals(2, hop.received());
        assertEquals(33, hop.lossPercent());
        assertEquals(Duration.ofMillis(10), hop.best().orElseThrow());
        assertEquals(Duration.ofMillis(30), hop.worst().orElseThrow());
        assertEquals(Duration.ofMillis(30), hop.last().orElseThrow());
        assertEquals(Duration.ofMillis(20), hop.average().orElseThrow());
        assertFalse(hop.target());
        assertEquals(0, TraceHop.start(1).lossPercent());
        assertEquals(Optional.empty(), TraceHop.start(1).average());
    }

    private static LogEntry line(String id, String topics, String message) {
        return new LogEntry(id, "10:00", LogEntry.topics(topics), message);
    }

    @Test
    void theLogFeedHandsOutOnlyNewLinesInOrder() {
        LogFeed feed = new LogFeed(3);
        assertEquals(2, feed.add(List.of(line("*1", "system,info", "a"), line("*2", "system,info", "b"))).size());
        List<LogEntry> next = feed.add(List.of(line("*1", "system,info", "a"), line("*2", "system,info", "b"),
                line("*10", "hotspot,info", "d"), line("*F", "hotspot,info", "c")));
        assertEquals(List.of("c", "d"), next.stream().map(LogEntry::message).toList(), "*F before *10");
        assertTrue(feed.add(List.of(line("*10", "hotspot,info", "d"))).isEmpty());
        assertEquals(3, feed.limit());

        feed.clear();
        assertEquals(1, feed.add(List.of(line("*1", "system,info", "a"))).size(), "a cleared feed starts over");
    }

    @Test
    void logLinesHaveASeverityAFilterAndASearch() {
        LogEntry login = line("*1", "hotspot,info,debug", "card1 (10.5.50.7): logged in");
        LogEntry failure = line("*2", "system,error,critical", "login failure for user admin");
        LogEntry warning = line("*3", "dhcp,warning", "offering lease without success");

        assertEquals(LogEntry.Severity.INFO, login.severity());
        assertEquals(LogEntry.Severity.ERROR, failure.severity());
        assertEquals(LogEntry.Severity.WARNING, warning.severity());
        assertTrue(LogEntry.Filter.HOTSPOT.test(login));
        assertFalse(LogEntry.Filter.HOTSPOT.test(failure));
        assertTrue(LogEntry.Filter.SYSTEM.test(failure));
        assertTrue(LogEntry.Filter.DHCP.test(warning));
        assertTrue(LogEntry.Filter.PROBLEMS.test(warning));
        assertFalse(LogEntry.Filter.PROBLEMS.test(login));
        assertTrue(LogEntry.Filter.ALL.test(login));
        assertTrue(login.matches("CARD1"));
        assertTrue(login.matches("hotspot"));
        assertTrue(login.matches(" "));
        assertFalse(login.matches("admin"));
        assertEquals("10:00  hotspot,info,debug  card1 (10.5.50.7): logged in", login.line());
        assertEquals(-1, new LogEntry("x", null, null, null).order());
    }
}
