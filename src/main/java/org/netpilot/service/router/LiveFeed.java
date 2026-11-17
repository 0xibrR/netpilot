package org.netpilot.service.router;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.netpilot.model.LogEntry;
import org.netpilot.model.RouterResource;

/**
 * Live updates from one router through the RouterOS API: the router tells NetPilot the moment
 * something changes (a customer logs in or out, a card is added, an interface goes down, a log
 * line), and sends its load every second, instead of NetPilot asking every few seconds. Listening
 * costs the router next to nothing.
 *
 * <p>Runs on its own thread. When the API cannot be reached (service off, blocked, wrong
 * certificate) or the connection breaks, it says so and tries again later; meanwhile the app keeps
 * reading the router every few seconds as before. Listeners are called on the feed's thread.
 */
public final class LiveFeed implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(LiveFeed.class.getName());

    /** Plain API port. */
    public static final int API_PORT = 8728;
    /** API over TLS. */
    public static final int API_SSL_PORT = 8729;

    static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** Wait before trying again when the API is off or refused us: it will not change by itself soon. */
    static final Duration RETRY_SLOW = Duration.ofSeconds(60);
    /** First wait after a broken connection; doubles up to {@link #RETRY_SLOW}. */
    static final Duration RETRY_FAST = Duration.ofSeconds(5);

    /** Where the feed stands. */
    public enum State {
        CONNECTING,
        LIVE,
        UNAVAILABLE,
        STOPPED
    }

    /** The state and, when unavailable, why. */
    public record Status(State state, Optional<ApiConnection.ApiException.Kind> problem) {

        static Status of(State state) {
            return new Status(state, Optional.empty());
        }
    }

    /** What changed; all optional. */
    public interface Listener {

        /** Someone logged in to or out of the hotspot. */
        default void online() {
        }

        /** Cards were added, removed or changed on the router. */
        default void cards() {
        }

        /** An interface went up or down, or was turned on or off. */
        default void interfaces() {
        }

        /** A new line in the router's log. */
        default void log(LogEntry entry) {
        }

        /** The router's load, every second. */
        default void resource(RouterResource resource) {
        }

        default void status(Status status) {
        }
    }

    /** Opens API sessions; the router session's login in the app, a fake in tests. */
    @FunctionalInterface
    interface Opener {

        ApiConnection open(int port, boolean tls, Duration timeout) throws ApiConnection.ApiException;
    }

    private final Opener opener;
    private final int port;
    private final boolean tls;
    private final Listener listener;
    private final Duration slow;
    private final Duration fast;
    private final Object lock = new Object();
    /** Each interface's last running / disabled state, so only real changes are reported. */
    private final Map<String, String> interfaceStates = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile ApiConnection current;
    private volatile Status status = Status.of(State.CONNECTING);
    private Thread thread;

    /**
     * @param port 8728, or 8729 with {@code tls}
     */
    public LiveFeed(ApiLogin login, int port, boolean tls, Listener listener) {
        this(login::open, port, tls, listener, RETRY_SLOW, RETRY_FAST);
    }

    LiveFeed(Opener opener, int port, boolean tls, Listener listener, Duration slow, Duration fast) {
        this.opener = Objects.requireNonNull(opener);
        this.port = port;
        this.tls = tls;
        this.listener = Objects.requireNonNull(listener);
        this.slow = slow;
        this.fast = fast;
    }

    public synchronized void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this::run, "netpilot-live-feed");
        thread.setDaemon(true);
        thread.start();
    }

    public Status status() {
        return status;
    }

    private void run() {
        Duration brokenWait = fast;
        while (!closed) {
            setStatus(Status.of(State.CONNECTING));
            ApiConnection.ApiException.Kind problem;
            try (ApiConnection connection = opener.open(port, tls, TIMEOUT)) {
                current = connection;
                Object ended = new Object();
                boolean[] done = {false};
                connection.start(cause -> {
                    synchronized (ended) {
                        done[0] = true;
                        ended.notifyAll();
                    }
                });
                subscribe(connection);
                setStatus(Status.of(State.LIVE));
                LOG.log(System.Logger.Level.INFO, "Live updates on (port {0})", port);
                brokenWait = fast;
                synchronized (ended) {
                    while (!done[0] && !closed) {
                        ended.wait(1000);
                    }
                }
                problem = ApiConnection.ApiException.Kind.BROKEN;
            } catch (ApiConnection.ApiException e) {
                problem = e.kind();
                LOG.log(System.Logger.Level.INFO, "Live updates unavailable ({0}): {1}", e.kind(), e.getMessage());
            } catch (java.io.IOException e) {
                problem = ApiConnection.ApiException.Kind.BROKEN;
                LOG.log(System.Logger.Level.INFO, "Live updates stopped: {0}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                current = null;
            }
            if (closed) {
                break;
            }
            setStatus(new Status(State.UNAVAILABLE, Optional.of(problem)));
            Duration wait = problem == ApiConnection.ApiException.Kind.BROKEN ? brokenWait : slow;
            if (problem == ApiConnection.ApiException.Kind.BROKEN) {
                brokenWait = brokenWait.multipliedBy(2).compareTo(slow) > 0 ? slow : brokenWait.multipliedBy(2);
            }
            synchronized (lock) {
                try {
                    if (!closed) {
                        lock.wait(wait.toMillis());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        setStatus(Status.of(State.STOPPED));
    }

    private void subscribe(ApiConnection c) throws java.io.IOException {
        c.send(List.of("/ip/hotspot/active/listen"), reply -> {
            if (reply.is("!re")) {
                listener.online();
            }
        });
        c.send(List.of("/ip/hotspot/user/listen"), reply -> {
            if (reply.is("!re")) {
                listener.cards();
            }
        });
        c.send(List.of("/interface/listen"), reply -> {
            if (reply.is("!re") && interfaceChanged(reply)) {
                listener.interfaces();
            }
        });
        c.send(List.of("/log/listen"), reply -> {
            if (reply.is("!re") && !reply.get("message").isEmpty()) {
                listener.log(new LogEntry(reply.get(".id"), reply.get("time"), LogEntry.topics(reply.get("topics")),
                        reply.get("message")));
            }
        });
        c.send(List.of("/system/resource/print", "=interval=1",
                "=.proplist=cpu-load,free-memory,total-memory,uptime"), reply -> {
                    if (reply.is("!re")) {
                        listener.resource(new RouterResource(number(reply.get("cpu-load")),
                                longNumber(reply.get("free-memory")), longNumber(reply.get("total-memory")),
                                reply.get("uptime")));
                    }
                });
    }

    /**
     * Interfaces report their traffic counters too; only a change of running or disabled (or one
     * removed) counts.
     */
    private boolean interfaceChanged(ApiProtocol.Reply reply) {
        String key = reply.get(".id").isEmpty() ? reply.get("name") : reply.get(".id");
        if ("yes".equals(reply.get(".dead")) || "true".equals(reply.get(".dead"))) {
            return interfaceStates.remove(key) != null;
        }
        String running = reply.get("running");
        String disabled = reply.get("disabled");
        if (running.isEmpty() && disabled.isEmpty()) {
            return false;
        }
        String before = interfaceStates.getOrDefault(key, "");
        String[] old = before.split("\\|", -1);
        String state = (running.isEmpty() && old.length == 2 ? old[0] : running) + "|"
                + (disabled.isEmpty() && old.length == 2 ? old[1] : disabled);
        interfaceStates.put(key, state);
        return !state.equals(before);
    }

    private static int number(String text) {
        return (int) Math.min(Integer.MAX_VALUE, longNumber(text));
    }

    private static long longNumber(String text) {
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void setStatus(Status next) {
        status = next;
        try {
            listener.status(next);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Live status listener failed", e);
        }
    }

    @Override
    public void close() {
        closed = true;
        ApiConnection c = current;
        if (c != null) {
            c.close();
        }
        synchronized (lock) {
            lock.notifyAll();
        }
    }
}
