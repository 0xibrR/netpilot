package org.netpilot.model;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One ping probe sent by the router ({@code /ping}), as RouterOS reports it.
 *
 * @param seq    probe number in this test, from 0
 * @param host   the address that answered (or was asked)
 * @param time   round-trip time; empty when no reply came back
 * @param ttl    time to live of the reply; 0 when none
 * @param size   packet size in bytes
 * @param status RouterOS status when there was no reply, e.g. {@code timeout}; empty on success
 */
public record PingReply(int seq, String host, Optional<Duration> time, int ttl, int size, String status) {

    private static final Pattern PART = Pattern.compile("(\\d+)(us|ms|s|m|h)");
    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:]{2,39}(%[A-Za-z0-9-]+)?$");
    private static final Pattern HOST = Pattern.compile(
            "^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)*[A-Za-z0-9]([A-Za-z0-9-]{0,61}"
                    + "[A-Za-z0-9])?$");

    public PingReply {
        host = host == null ? "" : host;
        time = time == null ? Optional.empty() : time;
        status = status == null ? "" : status;
    }

    /** Whether the address itself replied (a router on the way saying "TTL exceeded" does not count). */
    public boolean answered() {
        return time.isPresent() && !ttlExceeded();
    }

    /**
     * A router on the way dropped the probe because its TTL ran out and said so: that router is
     * {@link #host()}. Traceroute is built on this.
     */
    public boolean ttlExceeded() {
        String s = status.toLowerCase(Locale.ROOT);
        return s.contains("ttl exceeded") || s.contains("time exceeded");
    }

    /** An IP address rather than a name. */
    public static boolean isIpAddress(String value) {
        String v = value == null ? "" : value.strip();
        return IPV4.matcher(v).matches() || (v.contains(":") && IPV6.matcher(v).matches());
    }

    /**
     * RouterOS times: {@code 12ms}, {@code 11ms450us}, {@code 1s3ms}, {@code 450us}.
     *
     * @return empty for blank or unreadable text
     */
    public static Optional<Duration> parseTime(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String value = text.strip().toLowerCase(Locale.ROOT);
        Matcher m = PART.matcher(value);
        Duration total = Duration.ZERO;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) {
                return Optional.empty();
            }
            long n = Long.parseLong(m.group(1));
            total = total.plus(switch (m.group(2)) {
                case "us" -> Duration.ofNanos(n * 1_000);
                case "ms" -> Duration.ofMillis(n);
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                default -> Duration.ofHours(n);
            });
            end = m.end();
        }
        return end == value.length() && end > 0 ? Optional.of(total) : Optional.empty();
    }

    /**
     * An address the router can ping: IPv4, IPv6 or a host name. Anything else (spaces, quotes,
     * RouterOS syntax) is refused before it reaches the router.
     *
     * @return the address, trimmed
     * @throws IllegalArgumentException when it is not one
     */
    public static String target(String address) {
        String value = address == null ? "" : address.strip();
        boolean ok = IPV4.matcher(value).matches()
                || (value.contains(":") && IPV6.matcher(value).matches())
                || (HOST.matcher(value).matches() && !value.matches("[0-9.]+"));
        if (!ok) {
            throw new IllegalArgumentException(org.netpilot.i18n.Messages.get("ping.validation.address"));
        }
        return value;
    }
}
