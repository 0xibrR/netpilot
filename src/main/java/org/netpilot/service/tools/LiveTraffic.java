package org.netpilot.service.tools;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.netpilot.model.ActiveSession;

/**
 * Turns successive readings of the online sessions into speeds. RouterOS gives each session's byte
 * counters, not its speed, so the speed is the change between two readings divided by the time
 * between them. Needs nothing extra on the router (no queues, no Torch).
 *
 * <p>A speed is only given when it can be trusted: a session seen for the first time, a counter
 * that went down (a new session that got the same id) or readings too close together have no speed
 * yet, rather than a wrong or negative one. Not tied to JavaFX; safe to call from any thread.
 */
public final class LiveTraffic {

    /** Readings closer than this are too noisy to divide by. */
    static final Duration MIN_GAP = Duration.ofMillis(500);

    /** How much history the chart keeps. */
    public static final Duration WINDOW = Duration.ofMinutes(5);

    /** A speed in bits per second, as network speeds are usually given. */
    public record Rate(long downloadBps, long uploadBps) {

        public static final Rate ZERO = new Rate(0, 0);

        public Rate {
            downloadBps = Math.max(0, downloadBps);
            uploadBps = Math.max(0, uploadBps);
        }

        Rate plus(Rate other) {
            return new Rate(downloadBps + other.downloadBps, uploadBps + other.uploadBps);
        }
    }

    /** The total speed at one moment, for the chart. */
    public record Point(Instant at, Rate total) {
    }

    /**
     * One reading worked out.
     *
     * @param perSession speed per session id; sessions without a trusted speed yet are missing
     * @param total      the sum of the known speeds
     * @param history    totals of the last {@link #WINDOW}, oldest first
     */
    public record Snapshot(Map<String, Rate> perSession, Rate total, List<Point> history) {

        public Snapshot {
            perSession = Map.copyOf(perSession);
            history = List.copyOf(history);
        }

        public Optional<Rate> rateOf(String sessionId) {
            return Optional.ofNullable(perSession.get(sessionId));
        }
    }

    private record Sample(String user, long bytesIn, long bytesOut) {
    }

    private final Object lock = new Object();
    private Map<String, Sample> last = Map.of();
    private Instant lastAt;
    private final Deque<Point> history = new ArrayDeque<>();

    /** Works out the speeds since the previous reading and remembers this one. */
    public Snapshot update(List<ActiveSession> sessions, Instant now) {
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(now, "now");
        synchronized (lock) {
            Map<String, Sample> current = new HashMap<>();
            Map<String, Rate> rates = new HashMap<>();
            Rate total = Rate.ZERO;
            Duration gap = lastAt == null ? Duration.ZERO : Duration.between(lastAt, now);
            boolean comparable = lastAt != null && gap.compareTo(MIN_GAP) >= 0;
            for (ActiveSession s : sessions) {
                Sample sample = new Sample(s.user(), s.bytesIn(), s.bytesOut());
                current.put(s.routerId(), sample);
                Sample before = last.get(s.routerId());
                if (comparable && before != null && before.user().equals(sample.user())
                        && sample.bytesIn() >= before.bytesIn() && sample.bytesOut() >= before.bytesOut()) {
                    Rate rate = new Rate(bitsPerSecond(sample.bytesOut() - before.bytesOut(), gap),
                            bitsPerSecond(sample.bytesIn() - before.bytesIn(), gap));
                    rates.put(s.routerId(), rate);
                    total = total.plus(rate);
                }
            }
            if (lastAt == null || comparable) {
                last = current;
                lastAt = now;
            }
            if (comparable) {
                history.addLast(new Point(now, total));
            }
            Instant oldest = now.minus(WINDOW);
            while (!history.isEmpty() && history.peekFirst().at().isBefore(oldest)) {
                history.removeFirst();
            }
            return new Snapshot(rates, total, List.copyOf(history));
        }
    }

    /** Forgets everything, e.g. after connecting to another router. */
    public void reset() {
        synchronized (lock) {
            last = Map.of();
            lastAt = null;
            history.clear();
        }
    }

    private static long bitsPerSecond(long bytes, Duration gap) {
        return bytes * 8 * 1000 / Math.max(1, gap.toMillis());
    }

    /** {@code 1250000} → {@code 1.3 Mbps}; speeds use powers of 1000 like RouterOS does. */
    public static String format(long bitsPerSecond) {
        String[] units = {"bps", "kbps", "Mbps", "Gbps"};
        double value = Math.max(0, bitsPerSecond);
        int unit = 0;
        while (value >= 1000 && unit < units.length - 1) {
            value /= 1000;
            unit++;
        }
        return unit == 0
                ? String.format(java.util.Locale.ROOT, "%d %s", (long) value, units[unit])
                : String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
