package org.netpilot.service.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.netpilot.model.PingReply;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.service.tools.PingSession;
import org.netpilot.service.tools.PingStatistics;

class PingTest {

    private static final String USER = "netpilot";
    private static final String PASSWORD = "secret";
    private static final String REPLY = "[{\"seq\":\"0\",\"host\":\"8.8.8.8\",\"size\":\"56\",\"ttl\":\"117\","
            + "\"time\":\"11ms450us\",\"sent\":\"1\",\"received\":\"1\",\"packet-loss\":\"0\"}]";
    private static final String TIMEOUT = "[{\"seq\":\"0\",\"host\":\"10.9.9.9\",\"status\":\"timeout\","
            + "\"sent\":\"1\",\"received\":\"0\",\"packet-loss\":\"100\"}]";

    private FakeRouter router;
    private RouterClient client;

    @BeforeEach
    void connect() throws Exception {
        router = FakeRouter.http(USER, PASSWORD);
        client = new RouterConnector(Duration.ofSeconds(2), Duration.ofSeconds(2))
                .connect(new RouterEndpoint("127.0.0.1", router.port(), false, USER), PASSWORD.toCharArray(), null)
                .client();
    }

    @AfterEach
    void disconnect() {
        client.close();
        router.close();
    }

    /** Collects what a session reports. */
    private static final class Recorder implements PingSession.Listener {

        final List<PingReply> replies = new CopyOnWriteArrayList<>();
        final AtomicReference<PingStatistics> totals = new AtomicReference<>();
        final AtomicReference<Optional<Exception>> error = new AtomicReference<>();
        final CountDownLatch first = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);

        @Override
        public void reply(PingReply reply, PingStatistics t) {
            replies.add(reply);
            first.countDown();
        }

        @Override
        public void finished(PingStatistics t, Optional<Exception> e) {
            totals.set(t);
            error.set(e);
            done.countDown();
        }
    }

    @Test
    void readsRouterOsTimesAndRefusesAnythingButAnAddress() {
        assertEquals(Optional.of(Duration.ofMillis(12)), PingReply.parseTime("12ms"));
        assertEquals(Optional.of(Duration.ofNanos(11_450_000)), PingReply.parseTime("11ms450us"));
        assertEquals(Optional.of(Duration.ofMillis(1003)), PingReply.parseTime("1s3ms"));
        assertEquals(Optional.of(Duration.ofNanos(450_000)), PingReply.parseTime("450us"));
        assertEquals(Optional.empty(), PingReply.parseTime("fast"));
        assertEquals(Optional.empty(), PingReply.parseTime(""));

        assertEquals("8.8.8.8", PingReply.target(" 8.8.8.8 "));
        assertEquals("google.com", PingReply.target("google.com"));
        assertEquals("2001:4860:4860::8888", PingReply.target("2001:4860:4860::8888"));
        for (String bad : List.of("", "8.8.8.8; /system reboot", "a b", "999.1.1.1", "1.2.3", "\"x\"", "-bad-.com")) {
            assertThrows(IllegalArgumentException.class, () -> PingReply.target(bad), bad);
        }
    }

    @Test
    void oneProbeGoesThroughTheRoutersOwnPing() throws Exception {
        router.route("/rest/ping", 200, REPLY);
        PingReply reply = client.ping("8.8.8.8", 0);

        assertTrue(reply.answered());
        assertEquals(Duration.ofNanos(11_450_000), reply.time().orElseThrow());
        assertEquals(117, reply.ttl());
        assertEquals(56, reply.size());
        assertEquals("8.8.8.8", reply.host());

        router.route("/rest/ping", 200, TIMEOUT);
        PingReply lost = client.ping("10.9.9.9", 1);
        assertFalse(lost.answered());
        assertEquals("timeout", lost.status());
        assertThrows(IllegalArgumentException.class, () -> client.ping("1.1.1.1 count=1000", 0),
                "never sent to the router");
    }

    @Test
    void aSessionSendsItsCountAndAddsUpTheTotals() throws Exception {
        router.route("/rest/ping", 200, REPLY);
        Recorder recorder = new Recorder();

        new PingSession(client, "8.8.8.8", 3, Duration.ZERO, recorder).start();

        assertTrue(recorder.done.await(5, TimeUnit.SECONDS));
        assertEquals(3, recorder.replies.size());
        assertEquals(List.of(0, 1, 2), recorder.replies.stream().map(PingReply::seq).toList());
        PingStatistics totals = recorder.totals.get();
        assertEquals(3, totals.sent());
        assertEquals(3, totals.received());
        assertEquals(0, totals.lossPercent());
        assertEquals(Duration.ofNanos(11_450_000), totals.average().orElseThrow());
        assertEquals(Optional.empty(), recorder.error.get());
    }

    @Test
    void aContinuousSessionStopsWhenAskedAndCountsLostProbes() throws Exception {
        router.route("/rest/ping", 200, TIMEOUT);
        Recorder recorder = new Recorder();
        PingSession session = new PingSession(client, "10.9.9.9", 0, Duration.ofMillis(200), recorder).start();

        assertTrue(recorder.first.await(5, TimeUnit.SECONDS));
        assertTrue(session.running());
        session.stop();

        assertTrue(recorder.done.await(5, TimeUnit.SECONDS), "stops during the pause, not after it");
        assertFalse(session.running());
        PingStatistics totals = recorder.totals.get();
        assertEquals(totals.sent(), recorder.replies.size());
        assertEquals(0, totals.received());
        assertEquals(100, totals.lossPercent());
        assertEquals(Optional.empty(), totals.average());
        assertEquals(Optional.empty(), recorder.error.get(), "stopping is not an error");
        assertThrows(IllegalStateException.class, session::start, "a session runs once");
    }

    @Test
    void aRouterErrorEndsTheSessionWithTheReason() throws Exception {
        router.route("/rest/ping", 400, "{\"error\":400,\"message\":\"Bad Request\",\"detail\":\"invalid value\"}");
        Recorder recorder = new Recorder();

        new PingSession(client, "8.8.8.8", 4, Duration.ZERO, recorder).start();

        assertTrue(recorder.done.await(5, TimeUnit.SECONDS));
        assertTrue(recorder.error.get().isPresent());
        assertEquals(0, recorder.totals.get().sent());
        assertThrows(IllegalArgumentException.class,
                () -> new PingSession(client, "not an address!", 1, Duration.ZERO, recorder));
    }

    @Test
    void totalsGiveLossAndMinAverageMax() {
        PingStatistics t = PingStatistics.EMPTY
                .add(new PingReply(0, "h", Optional.of(Duration.ofMillis(10)), 64, 56, ""))
                .add(new PingReply(1, "h", Optional.empty(), 0, 0, "timeout"))
                .add(new PingReply(2, "h", Optional.of(Duration.ofMillis(30)), 64, 56, ""))
                .add(new PingReply(3, "h", Optional.of(Duration.ofMillis(20)), 64, 56, ""));

        assertEquals(4, t.sent());
        assertEquals(3, t.received());
        assertEquals(25, t.lossPercent());
        assertEquals(Duration.ofMillis(10), t.min().orElseThrow());
        assertEquals(Duration.ofMillis(30), t.max().orElseThrow());
        assertEquals(Duration.ofMillis(20), t.average().orElseThrow());
        assertEquals(0, PingStatistics.EMPTY.lossPercent());
    }
}
