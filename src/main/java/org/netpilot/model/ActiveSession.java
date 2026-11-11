package org.netpilot.model;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A user currently logged in to the hotspot ({@code /ip hotspot active}).
 *
 * @param routerId   RouterOS id of the session (not of the user)
 * @param user       user name
 * @param address    client IP address
 * @param macAddress client MAC address
 * @param uptime     length of this session
 * @param bytesIn    bytes the user uploaded in this session
 * @param bytesOut   bytes the user downloaded in this session
 * @param timeLeft   time left before the router ends the session; empty when the card has no time limit
 */
public record ActiveSession(String routerId, String user, String address, String macAddress, Duration uptime,
        long bytesIn, long bytesOut, Optional<Duration> timeLeft) {

    public ActiveSession {
        Objects.requireNonNull(routerId, "routerId");
        Objects.requireNonNull(user, "user");
        address = address == null ? "" : address;
        macAddress = macAddress == null ? "" : macAddress;
        uptime = uptime == null ? Duration.ZERO : uptime;
        bytesIn = Math.max(0, bytesIn);
        bytesOut = Math.max(0, bytesOut);
        timeLeft = timeLeft == null ? Optional.empty() : timeLeft;
    }

    /** Search by user name, IP address or MAC address, ignoring case; blank matches everything. */
    public boolean matches(String query) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return q.isEmpty() || user.toLowerCase(Locale.ROOT).contains(q) || address.toLowerCase(Locale.ROOT).contains(q)
                || macAddress.toLowerCase(Locale.ROOT).contains(q);
    }

    /** A session without traffic figures. */
    public ActiveSession(String routerId, String user, String address, String macAddress, Duration uptime) {
        this(routerId, user, address, macAddress, uptime, 0, 0, Optional.empty());
    }
}
