package org.netpilot.model;

import java.time.Duration;
import java.util.Objects;

/**
 * A user currently logged in to the hotspot ({@code /ip hotspot active}).
 *
 * @param routerId   RouterOS id of the session (not of the user)
 * @param user       user name
 * @param address    client IP address
 * @param macAddress client MAC address
 * @param uptime     length of this session
 */
public record ActiveSession(String routerId, String user, String address, String macAddress, Duration uptime) {

    public ActiveSession {
        Objects.requireNonNull(routerId, "routerId");
        Objects.requireNonNull(user, "user");
        address = address == null ? "" : address;
        macAddress = macAddress == null ? "" : macAddress;
        uptime = uptime == null ? Duration.ZERO : uptime;
    }
}
