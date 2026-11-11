package org.netpilot.service.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.netpilot.model.PingReply;
import org.netpilot.model.TraceHop;
import org.netpilot.service.router.RouterClient;
import org.netpilot.service.router.RouterException;

/**
 * A traceroute run by the connected router: pings with TTL 1, 2, 3 … so each router on the way
 * answers "TTL exceeded" in turn, until the address itself answers or the hop limit is reached.
 * Each probe is its own short request on a background thread, so hops appear as they are found,
 * {@link #stop()} takes effect within one probe, and nothing keeps running on the router.
 */
public final class TraceSession implements ToolSession {

    private static final System.Logger LOG = System.getLogger(TraceSession.class.getName());

    /** The usual traceroute limit. */
    public static final int MAX_HOPS = 30;

    /** Probes per hop, for loss and best / average / worst. */
    public static final int PROBES = 3;

    /** What the session reports; called on its own thread, never the JavaFX thread. */
    public interface Listener {

        /** A hop changed: called after every probe, so a row fills in as its probes come back. */
        void hop(TraceHop hop);

        /**
         * @param hops    every hop tried, in order
         * @param reached the address itself answered
         * @param error   why it stopped early, when it did not just finish or get stopped
         */
        void finished(List<TraceHop> hops, boolean reached, Optional<Exception> error);
    }

    private final RouterClient router;
    private final String address;
    private final int maxHops;
    private final Listener listener;
    private final Object lock = new Object();
    private volatile boolean running;
    private Thread thread;

    /** @throws IllegalArgumentException when the address is not an IP address or host name */
    public TraceSession(RouterClient router, String address, Listener listener) {
        this(router, address, MAX_HOPS, listener);
    }

    TraceSession(RouterClient router, String address, int maxHops, Listener listener) {
        this.router = Objects.requireNonNull(router);
        this.address = PingReply.target(address);
        this.maxHops = Math.clamp(maxHops, 1, MAX_HOPS);
        this.listener = Objects.requireNonNull(listener);
    }

    public String address() {
        return address;
    }

    /** Starts walking the path in the background. */
    public TraceSession start() {
        synchronized (lock) {
            if (thread != null) {
                throw new IllegalStateException("A trace runs only once");
            }
            running = true;
            thread = new Thread(this::run, "netpilot-traceroute");
            thread.setDaemon(true);
            thread.start();
        }
        return this;
    }

    @Override
    public void stop() {
        synchronized (lock) {
            running = false;
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    @Override
    public boolean running() {
        return running;
    }

    private void run() {
        List<TraceHop> hops = new ArrayList<>();
        boolean reached = false;
        Optional<Exception> error = Optional.empty();
        try {
            for (int ttl = 1; running && !reached && ttl <= maxHops; ttl++) {
                TraceHop hop = TraceHop.start(ttl);
                hops.add(hop);
                for (int probe = 0; running && probe < PROBES; probe++) {
                    hop = hop.add(router.probe(address, ttl));
                    hops.set(hops.size() - 1, hop);
                    listener.hop(hop);
                }
                reached = hop.target();
            }
        } catch (RouterException | RuntimeException e) {
            if (running) {
                LOG.log(System.Logger.Level.WARNING, "Traceroute to {0} stopped: {1}", address, e.getMessage());
                error = Optional.of(e);
            }
        } finally {
            running = false;
            listener.finished(List.copyOf(hops), reached, error);
        }
    }
}
