package org.netpilot.model;

import java.util.Objects;
import java.util.Optional;

/**
 * A single MikroTik hotspot user ({@code /ip hotspot user}).
 *
 * @param username    login name
 * @param password    login password
 * @param profile     hotspot user profile (e.g. {@code 2M})
 * @param server      hotspot server name, or {@code all}
 * @param limitUptime RouterOS duration such as {@code 1h} or {@code 1d}
 * @param limitBytes  data limit in bytes ({@code limit-bytes-total}); 0 for no limit
 */
public record HotspotUser(
        String username,
        String password,
        String profile,
        String server,
        String limitUptime,
        long limitBytes) {

    public HotspotUser {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(limitUptime, "limitUptime");
        limitBytes = Math.max(0, limitBytes);
    }

    /** A user without a data limit. */
    public HotspotUser(String username, String password, String profile, String server, String limitUptime) {
        this(username, password, profile, server, limitUptime, 0);
    }

    /** The data limit as typed, e.g. 1 GB; empty when there is none. */
    public Optional<DataLimit> dataLimit() {
        return DataLimit.ofBytes(limitBytes);
    }
}
