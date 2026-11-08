package org.netpilot.model;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A hotspot user as it exists on the router. Never holds the password: passwords are fetched
 * only when vouchers are printed.
 *
 * @param routerId    RouterOS id, e.g. {@code *1A}
 * @param name        login name
 * @param profile     user profile
 * @param server      hotspot server or {@code all}
 * @param uptime      time already used
 * @param limitUptime time limit as RouterOS text (e.g. {@code 8h}), empty when unlimited
 * @param bytesIn     bytes the user uploaded
 * @param bytesOut    bytes the user downloaded
 * @param disabled    whether the user is disabled
 * @param comment     comment, e.g. a NetPilot batch tag
 * @param limitBytes  data limit in bytes ({@code limit-bytes-total}), 0 when unlimited
 */
public record RouterUser(
        String routerId,
        String name,
        String profile,
        String server,
        Duration uptime,
        String limitUptime,
        long bytesIn,
        long bytesOut,
        boolean disabled,
        String comment,
        long limitBytes) {

    public RouterUser {
        Objects.requireNonNull(routerId, "routerId");
        Objects.requireNonNull(name, "name");
        profile = profile == null ? "" : profile;
        server = server == null ? "" : server;
        uptime = uptime == null ? Duration.ZERO : uptime;
        limitUptime = limitUptime == null ? "" : limitUptime;
        comment = comment == null ? "" : comment;
        limitBytes = Math.max(0, limitBytes);
    }

    /** A user without a data limit. */
    public RouterUser(String routerId, String name, String profile, String server, Duration uptime,
            String limitUptime, long bytesIn, long bytesOut, boolean disabled, String comment) {
        this(routerId, name, profile, server, uptime, limitUptime, bytesIn, bytesOut, disabled, comment, 0);
    }

    public Optional<Duration> limit() {
        return RouterDuration.parse(limitUptime).filter(d -> !d.isZero());
    }

    /**
     * Used up all of its time limit or all of its data. Users without either limit never expire;
     * with both, whichever runs out first ends the card.
     */
    public boolean expired() {
        boolean timeUsed = limit().map(limit -> uptime.compareTo(limit) >= 0).orElse(false);
        return timeUsed || (limitBytes > 0 && totalBytes() >= limitBytes);
    }

    /** Never logged in. */
    public boolean neverUsed() {
        return uptime.isZero() && bytesIn == 0 && bytesOut == 0;
    }

    public long totalBytes() {
        return bytesIn + bytesOut;
    }

    /**
     * RouterOS's own {@code default-trial} user, which holds the counters for hotspot trial logins.
     * Deleting or disabling it breaks trial access, so NetPilot never touches it in bulk.
     */
    public boolean builtIn() {
        return BUILT_IN_TRIAL.equals(name);
    }

    private static final String BUILT_IN_TRIAL = "default-trial";
}
