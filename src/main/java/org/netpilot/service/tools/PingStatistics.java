package org.netpilot.service.tools;

import java.time.Duration;
import java.util.Optional;
import org.netpilot.model.PingReply;

/**
 * Running totals of a ping test, like the summary line of {@code /ping} in WinBox.
 *
 * @param sent     probes sent
 * @param received replies received
 * @param min      fastest reply; empty until one arrives
 * @param max      slowest reply
 * @param total    sum of reply times, for the average
 */
public record PingStatistics(int sent, int received, Optional<Duration> min, Optional<Duration> max,
        Duration total) {

    public static final PingStatistics EMPTY = new PingStatistics(0, 0, Optional.empty(), Optional.empty(),
            Duration.ZERO);

    public PingStatistics {
        min = min == null ? Optional.empty() : min;
        max = max == null ? Optional.empty() : max;
        total = total == null ? Duration.ZERO : total;
    }

    /** These totals with one more probe. */
    public PingStatistics add(PingReply reply) {
        if (reply.time().isEmpty()) {
            return new PingStatistics(sent + 1, received, min, max, total);
        }
        Duration t = reply.time().get();
        return new PingStatistics(sent + 1, received + 1,
                Optional.of(min.map(m -> m.compareTo(t) <= 0 ? m : t).orElse(t)),
                Optional.of(max.map(m -> m.compareTo(t) >= 0 ? m : t).orElse(t)), total.plus(t));
    }

    /** Lost probes, 0 to 100 percent; 0 before anything was sent. */
    public int lossPercent() {
        return sent == 0 ? 0 : (int) Math.round(100.0 * (sent - received) / sent);
    }

    /** Average reply time; empty until a reply arrives. */
    public Optional<Duration> average() {
        return received == 0 ? Optional.empty() : Optional.of(total.dividedBy(received));
    }
}
