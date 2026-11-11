package org.netpilot.model;

import java.time.Duration;
import java.util.Optional;

/**
 * One step on the way to an address in a traceroute: the router that answered probes sent with
 * this TTL, and how those probes went.
 *
 * @param hop      1 for the first router on the way, and so on
 * @param host     the router that answered; empty while none did
 * @param sent     probes sent with this TTL
 * @param received probes answered (by a router on the way or by the address itself)
 * @param last     time of the last answered probe
 * @param best     shortest time
 * @param worst    longest time
 * @param total    all answered times added up, for the average
 * @param target   the address itself answered: the trace ends here
 */
public record TraceHop(int hop, String host, int sent, int received, Optional<Duration> last, Optional<Duration> best,
        Optional<Duration> worst, Duration total, boolean target) {

    public TraceHop {
        host = host == null ? "" : host;
        last = last == null ? Optional.empty() : last;
        best = best == null ? Optional.empty() : best;
        worst = worst == null ? Optional.empty() : worst;
        total = total == null ? Duration.ZERO : total;
    }

    /** A hop before any probe went out. */
    public static TraceHop start(int hop) {
        return new TraceHop(hop, "", 0, 0, Optional.empty(), Optional.empty(), Optional.empty(), Duration.ZERO,
                false);
    }

    /** This hop with one more probe's result. */
    public TraceHop add(PingReply reply) {
        boolean replied = reply.answered() || reply.ttlExceeded();
        Optional<Duration> time = replied ? reply.time() : Optional.empty();
        if (time.isEmpty()) {
            return new TraceHop(hop, host, sent + 1, received, last, best, worst, total, target);
        }
        Duration t = time.get();
        return new TraceHop(hop, reply.host().isEmpty() ? host : reply.host(), sent + 1, received + 1, time,
                Optional.of(best.filter(b -> b.compareTo(t) <= 0).orElse(t)),
                Optional.of(worst.filter(w -> w.compareTo(t) >= 0).orElse(t)), total.plus(t),
                target || reply.answered());
    }

    /** Some router (or the address) answered at this step. */
    public boolean responded() {
        return received > 0;
    }

    public int lossPercent() {
        return sent == 0 ? 0 : (int) Math.round((sent - received) * 100.0 / sent);
    }

    public Optional<Duration> average() {
        return received == 0 ? Optional.empty() : Optional.of(total.dividedBy(received));
    }
}
