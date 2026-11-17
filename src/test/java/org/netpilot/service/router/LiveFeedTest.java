package org.netpilot.service.router;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.LogEntry;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.model.RouterResource;
import org.netpilot.model.RouterSettings;
import org.netpilot.repository.SettingsRepository;

class LiveFeedTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @TempDir
    Path dir;

    // ------------------------------------------------------------------ the wire format

    @Test
    void lengthsAndSentencesRoundTrip() throws Exception {
        for (int length : new int[]{0, 1, 0x7F, 0x80, 0x3FFF, 0x4000, 0x1FFFFF, 0x200000, 0xFFFFFFF, 0x10000000}) {
            byte[] encoded = ApiProtocol.encodeLength(length);
            assertEquals(length, ApiProtocol.readLength(new ByteArrayInputStream(encoded)), "length " + length);
        }
        assertArrayEquals(new byte[]{(byte) 0x80, (byte) 0x80}, ApiProtocol.encodeLength(0x80));
        assertThrows(IllegalArgumentException.class, () -> ApiProtocol.encodeLength(-1));
        assertThrows(IOException.class,
                () -> ApiProtocol.readLength(new ByteArrayInputStream(new byte[]{(byte) 0xF8})));
        assertThrows(IOException.class, () -> ApiProtocol.readLength(new ByteArrayInputStream(new byte[0])));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0); // an empty sentence (keep-alive) is skipped
        ApiProtocol.writeSentence(out, List.of(ApiProtocol.word("!re"), ApiProtocol.word("=name=محل"),
                ApiProtocol.word("=comment=a=b"), ApiProtocol.word("=flag"), ApiProtocol.word(".tag=7")));
        List<String> words = ApiProtocol.readSentence(new ByteArrayInputStream(out.toByteArray()));
        ApiProtocol.Reply reply = ApiProtocol.Reply.of(words);
        assertTrue(reply.is("!re"));
        assertEquals("7", reply.tag().orElseThrow());
        assertEquals(Map.of("name", "محل", "comment", "a=b", "flag", ""), reply.attributes());
        assertEquals("", reply.get("missing"));
        assertEquals("session terminated",
                ApiProtocol.Reply.of(List.of("!fatal", "session terminated")).get("message"));

        byte[] secret = ApiProtocol.secretWord("=password=", "سر1".toCharArray());
        assertEquals("=password=سر1", new String(secret, StandardCharsets.UTF_8));

        byte[] cut = {5, 'a', 'b'}; // says 5 bytes, has 2
        assertThrows(IOException.class, () -> ApiProtocol.readSentence(new ByteArrayInputStream(cut)));
    }

    // ------------------------------------------------------------------ a fake router

    /** Speaks the RouterOS API on a local port: login, then answers what the test tells it. */
    private static final class FakeRouter implements AutoCloseable {

        final ServerSocket server = new ServerSocket(0);
        final BlockingQueue<List<String>> received = new LinkedBlockingQueue<>();
        final List<Socket> clients = new CopyOnWriteArrayList<>();
        /** Every command received so far, in order. */
        final List<List<String>> history = new CopyOnWriteArrayList<>();
        volatile String password = "secret";
        volatile OutputStream out;
        private final Thread thread;

        FakeRouter() throws IOException {
            thread = new Thread(this::serve, "fake-router");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    clients.add(socket);
                    Thread t = new Thread(() -> talk(socket), "fake-router-client");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void talk(Socket socket) {
            try (socket) {
                InputStream in = socket.getInputStream();
                OutputStream o = socket.getOutputStream();
                List<String> login = ApiProtocol.readSentence(in);
                if (!login.contains("=password=" + password) || !login.contains("=name=admin")) {
                    send(o, "!trap", "=message=invalid user name or password (6)");
                    return;
                }
                send(o, "!done");
                out = o;
                while (true) {
                    received.add(ApiProtocol.readSentence(in));
                }
            } catch (IOException e) {
                // the client went away
            }
        }

        synchronized void send(OutputStream o, String... words) throws IOException {
            List<byte[]> sentence = new ArrayList<>();
            for (String w : words) {
                sentence.add(ApiProtocol.word(w));
            }
            ApiProtocol.writeSentence(o, sentence);
            o.flush();
        }

        void reply(String... words) throws IOException {
            send(out, words);
        }

        /** The tag the client gave to the command starting with {@code path}. */
        String tagOf(String path) throws InterruptedException {
            long end = System.nanoTime() + WAIT.toNanos();
            List<List<String>> seen = history;
            while (System.nanoTime() < end) {
                List<String> command = received.poll(100, TimeUnit.MILLISECONDS);
                if (command != null) {
                    seen.add(command);
                }
                for (List<String> c : seen) {
                    if (c.getFirst().equals(path)) {
                        return c.stream().filter(w -> w.startsWith(".tag=")).findFirst().orElseThrow()
                                .substring(5);
                    }
                }
            }
            throw new AssertionError("No " + path + " command; got " + seen);
        }

        void dropClients() throws IOException {
            for (Socket s : clients) {
                s.close();
            }
            clients.clear();
        }

        @Override
        public void close() throws IOException {
            server.close();
            dropClients();
        }
    }

    /** Records what the feed reported. */
    private static final class Recorder implements LiveFeed.Listener {

        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        final BlockingQueue<LiveFeed.Status> statuses = new LinkedBlockingQueue<>();
        final List<LogEntry> log = new CopyOnWriteArrayList<>();
        final List<RouterResource> resources = new CopyOnWriteArrayList<>();

        @Override
        public void online() {
            events.add("online");
        }

        @Override
        public void cards() {
            events.add("cards");
        }

        @Override
        public void interfaces() {
            events.add("interfaces");
        }

        @Override
        public void log(LogEntry entry) {
            log.add(entry);
            events.add("log");
        }

        @Override
        public void resource(RouterResource resource) {
            resources.add(resource);
            events.add("resource");
        }

        @Override
        public void status(LiveFeed.Status status) {
            statuses.add(status);
        }

        String next() throws InterruptedException {
            String e = events.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            if (e == null) {
                throw new AssertionError("No event");
            }
            return e;
        }

        LiveFeed.Status until(LiveFeed.State state) throws InterruptedException {
            long end = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < end) {
                LiveFeed.Status s = statuses.poll(100, TimeUnit.MILLISECONDS);
                if (s != null && s.state() == state) {
                    return s;
                }
            }
            throw new AssertionError("Never " + state);
        }
    }

    private static LiveFeed feed(FakeRouter router, String password, Recorder recorder) {
        ApiLogin login = new ApiLogin("127.0.0.1", "admin", password.toCharArray(), null);
        return new LiveFeed(login::open, router.port(), false, recorder, Duration.ofMillis(200),
                Duration.ofMillis(50));
    }

    @Test
    void changesArriveAsTheyHappenAndTheFeedComesBackAfterADrop() throws Exception {
        try (FakeRouter router = new FakeRouter()) {
            Recorder recorder = new Recorder();
            try (LiveFeed feed = feed(router, "secret", recorder)) {
                feed.start();
                recorder.until(LiveFeed.State.LIVE);
                assertEquals(LiveFeed.State.LIVE, feed.status().state());

                router.reply("!re", "=.id=*A", "=user=card1", ".tag=" + router.tagOf("/ip/hotspot/active/listen"));
                assertEquals("online", recorder.next());
                router.reply("!re", "=.id=*1", "=name=card1", ".tag=" + router.tagOf("/ip/hotspot/user/listen"));
                assertEquals("cards", recorder.next());

                String ifTag = router.tagOf("/interface/listen");
                router.reply("!re", "=.id=*2", "=name=ether1", "=running=true", "=disabled=false", ".tag=" + ifTag);
                assertEquals("interfaces", recorder.next());
                router.reply("!re", "=.id=*2", "=rx-byte=1000", ".tag=" + ifTag); // counters only
                router.reply("!re", "=.id=*2", "=running=true", ".tag=" + ifTag); // nothing changed
                router.reply("!re", "=.id=*2", "=running=false", ".tag=" + ifTag);
                assertEquals("interfaces", recorder.next(), "only the real change");
                router.reply("!re", "=.id=*2", "=.dead=yes", ".tag=" + ifTag);
                assertEquals("interfaces", recorder.next(), "removed");

                router.reply("!re", "=.id=*9", "=time=10:04:12", "=topics=hotspot,info", "=message=card1 logged in",
                        ".tag=" + router.tagOf("/log/listen"));
                assertEquals("log", recorder.next());
                assertEquals(new LogEntry("*9", "10:04:12", List.of("hotspot", "info"), "card1 logged in"),
                        recorder.log.getFirst());

                router.reply("!re", "=cpu-load=12", "=free-memory=50000000", "=total-memory=134217728",
                        "=uptime=1d2h", ".tag=" + router.tagOf("/system/resource/print"));
                assertEquals("resource", recorder.next());
                assertEquals(new RouterResource(12, 50_000_000L, 134_217_728L, "1d2h"), recorder.resources.getFirst());

                router.dropClients(); // the router restarted, the cable came out …
                LiveFeed.Status down = recorder.until(LiveFeed.State.UNAVAILABLE);
                assertEquals(ApiConnection.ApiException.Kind.BROKEN, down.problem().orElseThrow());
                recorder.until(LiveFeed.State.LIVE); // … and it is back by itself
            }
            recorder.until(LiveFeed.State.STOPPED);
        }
    }

    @Test
    void aWrongPasswordOrAClosedPortIsReportedAndNothingElseBreaks() throws Exception {
        try (FakeRouter router = new FakeRouter()) {
            Recorder recorder = new Recorder();
            try (LiveFeed feed = feed(router, "wrong", recorder)) {
                feed.start();
                assertEquals(ApiConnection.ApiException.Kind.LOGIN_FAILED,
                        recorder.until(LiveFeed.State.UNAVAILABLE).problem().orElseThrow());
            }
        }
        int closedPort;
        try (ServerSocket free = new ServerSocket(0)) {
            closedPort = free.getLocalPort();
        }
        ApiConnection.ApiException e = assertThrows(ApiConnection.ApiException.class, () -> ApiConnection.open(
                "127.0.0.1", closedPort, false, null, "admin", "x".toCharArray(), Duration.ofSeconds(2)));
        assertEquals(ApiConnection.ApiException.Kind.UNREACHABLE, e.kind());

        ApiLogin login = new ApiLogin("127.0.0.1", "admin", "x".toCharArray(), "ab");
        assertEquals("ab", login.trustedFingerprint());
        assertEquals("127.0.0.1", login.host());
        assertEquals("admin", login.username());
        login.close();
        assertEquals(ApiConnection.ApiException.Kind.BROKEN, assertThrows(ApiConnection.ApiException.class,
                () -> login.open(closedPort, false, Duration.ofSeconds(1))).kind(), "wiped: never used again");
    }

    @Test
    void commandsCanBeCancelledAndTheirEndIsHeard() throws Exception {
        try (FakeRouter router = new FakeRouter();
                ApiConnection c = ApiConnection.open("127.0.0.1", router.port(), false, null, "admin",
                        "secret".toCharArray(), Duration.ofSeconds(2))) {
            BlockingQueue<String> got = new LinkedBlockingQueue<>();
            BlockingQueue<Throwable> closed = new LinkedBlockingQueue<>();
            c.start(cause -> closed.add(cause == null ? new IOException("none") : cause));
            String tag = c.send(List.of("/interface/listen"), reply -> got.add(reply.type()));
            assertEquals(tag, router.tagOf("/interface/listen"));
            c.cancel(tag);
            router.tagOf("/cancel");
            router.reply("!trap", "=category=2", "=message=interrupted", ".tag=" + tag);
            router.reply("!done", ".tag=" + tag);
            assertEquals("!trap", got.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            assertTrue(c.isOpen());
            router.reply("!fatal", "too many commands");
            assertTrue(closed.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS) instanceof ApiConnection.ApiException);
            assertFalse(c.isOpen());
        }
    }

    @Test
    void liveUpdatesAreOnByDefaultAndTheirPortIsSaved() throws Exception {
        RouterEndpoint plain = new RouterEndpoint("10.0.0.1", 80, false, "admin");
        RouterEndpoint secure = new RouterEndpoint("10.0.0.1", 443, true, "admin");
        RouterSettings defaults = RouterSettings.defaults();
        assertTrue(defaults.live());
        assertEquals(8728, defaults.livePortFor(plain));
        assertEquals(8729, defaults.livePortFor(secure));
        assertEquals(9000, defaults.withLive(true, 9000).livePortFor(secure));
        assertEquals(0, defaults.withLive(true, 70_000).livePort(), "out of range: automatic");

        SettingsRepository repository = new SettingsRepository(dir.resolve("settings.properties"));
        repository.save(repository.load().withRouter(repository.load().router().withLive(false, 8800)
                .withAutoConnect(true)));
        RouterSettings back = new SettingsRepository(dir.resolve("settings.properties")).load().router();
        assertFalse(back.live());
        assertEquals(8800, back.livePort());
        assertTrue(back.autoConnect(), "the other router settings keep their values");
    }
}
