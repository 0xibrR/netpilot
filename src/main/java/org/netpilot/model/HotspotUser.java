package org.netpilot.model;

import java.util.Objects;

/**
 * A single MikroTik hotspot user ({@code /ip hotspot user}).
 *
 * @param username    login name
 * @param password    login password
 * @param profile     hotspot user profile (e.g. {@code 2M})
 * @param server      hotspot server name, or {@code all}
 * @param limitUptime RouterOS duration such as {@code 1h} or {@code 1d}
 */
public record HotspotUser(
        String username,
        String password,
        String profile,
        String server,
        String limitUptime) {

    public HotspotUser {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(limitUptime, "limitUptime");
    }
}
