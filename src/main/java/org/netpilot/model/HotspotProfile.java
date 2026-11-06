package org.netpilot.model;

import java.util.Objects;

/**
 * A hotspot user profile on the router ({@code /ip hotspot user profile}).
 *
 * @param name      profile name used when creating users, e.g. {@code 2M}
 * @param rateLimit RouterOS rate limit such as {@code 2M/2M}, or empty when unlimited
 */
public record HotspotProfile(String name, String rateLimit) {

    public HotspotProfile {
        Objects.requireNonNull(name, "name");
        rateLimit = rateLimit == null ? "" : rateLimit;
    }
}
