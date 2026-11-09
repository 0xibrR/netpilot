package org.netpilot.service.tools;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.netpilot.model.PingReply;
import org.netpilot.service.router.RouterClient;
import org.netpilot.service.router.RouterException;

/**
 * A ping test run by the connected router: one probe at a time, on a background thread, until
 * the count is reached or {@link #stop()} is called. Each probe is a separate request, so stopping
 * takes effect within one probe and the router keeps no long-running command behind.
 */
public final class PingSession implements ToolSession {

    private static final System.Logger LOG = System.getLogger(PingSession.class.getName());

    /** What the session reports; called on its own thread, never the JavaFX thread. */
    public interface Listener {

        void reply(PingReply reply, PingStatistics totals);

        /** @param error why it stopped early, when it did not just finish or get stopped */
        void finished(PingStatistics totals, Optional<Exception> error);
    }

    private final RouterClient router;
    private final String address;
    private final int count;
    private final Duration interval;
    private final Listener listener;
    private final Object lock = new Object();
    private volatile boolean running;
    private Thread thread;

    /**
     * @param count    probes to send; 0 sends until stopped
     * @param interval pause between probes
     * @throws IllegalArgumentException when the address is not an IP address or host name
     */
    public PingSession(RouterClient router, String address, int count, Duration interval, Listener listener) {
        this.router = Objects.requireNonNull(router);
        this.address = PingReply.target(address);
        this.count = Math.max(0, count);
        this.interval = Objects.requireNonNull(interval);
        this.listener = Objects.requireNonNull(listener);
    }

    /** Starts sending probes in the background. */
    public PingSession start() {
        synchronized (lock) {
            if (thread != null) {
                throw new IllegalStateException("A ping session runs only once");
            }
            running = true;
            thread = new Thread(this::run, "netpilot-ping");
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
                thread.interrupt(); // ends the pause between probes at once
            }
        }
    }

    @Override
    public boolean running() {
        return running;
    }

    private void run() {
        PingStatistics totals = PingStatistics.EMPTY;
        Optional<Exception> error = Optional.empty();
        try {
            for (int seq = 0; running && (count == 0 || seq < count); seq++) {
                PingReply reply = router.ping(address, seq);
                totals = totals.add(reply);
                listener.reply(reply, totals);
                if (running && (count == 0 || seq + 1 < count)) {
                    Thread.sleep(interval);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // stopped during the pause
        } catch (RouterException | RuntimeException e) {
            if (running) {
                LOG.log(System.Logger.Level.WARNING, "Ping to {0} stopped: {1}", address, e.getMessage());
                error = Optional.of(e);
            }
        } finally {
            running = false;
            listener.finished(totals, error);
        }
    }
}
