package org.netpilot.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.netpilot.i18n.Messages;

/**
 * A hotspot user profile on the router ({@code /ip hotspot user profile}): the speed and how
 * many devices may share one card. The user decides every value; NetPilot suggests none.
 *
 * @param routerId    RouterOS id (e.g. {@code *1}), empty for a profile not created yet
 * @param name        profile name used when creating users, e.g. {@code 2M} or {@code Fiber-1G}
 * @param rateLimit   RouterOS rate limit ({@code upload/download}, e.g. {@code 10M/1000M}), empty = no limit
 * @param sharedUsers devices that may use one card at the same time (at least 1)
 */
public record HotspotProfile(String routerId, String name, String rateLimit, int sharedUsers) {

    public static final int MAX_NAME = 40;

    public HotspotProfile {
        Objects.requireNonNull(name, "name");
        routerId = routerId == null ? "" : routerId.strip();
        name = name.strip();
        rateLimit = rateLimit == null ? "" : rateLimit.strip();
        sharedUsers = Math.max(1, sharedUsers);
    }

    /** A profile as listed before ids were read; one device per card. */
    public HotspotProfile(String name, String rateLimit) {
        this("", name, rateLimit, 1);
    }

    /** The built-in profile; RouterOS does not allow deleting it. */
    public boolean builtIn() {
        return name.equals("default");
    }

    public RateLimit speed() {
        return RateLimit.parse(rateLimit);
    }

    /**
     * @return human-readable problems, empty when the profile can be sent to the router
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (name.isEmpty() || name.length() > MAX_NAME || !name.matches("[\\p{L}\\p{N} ._-]+")) {
            errors.add(Messages.get("profile.validation.name", MAX_NAME));
        }
        if (!RateLimit.valid(rateLimit)) {
            errors.add(Messages.get("profile.validation.speed"));
        }
        return errors;
    }
}
