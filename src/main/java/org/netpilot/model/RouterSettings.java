package org.netpilot.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Persisted router preferences: the last endpoint, the HTTPS certificates the user trusted, and
 * the routers the user works with (branches).
 *
 * @param lastEndpoint        endpoint shown in the connection dialog
 * @param trustedFingerprints SHA-256 certificate fingerprint per {@link RouterEndpoint#address()}
 * @param autoConnect         connect to the last router at start-up (only with a remembered password)
 * @param branches            the routers to switch between, in the user's order
 */
public record RouterSettings(RouterEndpoint lastEndpoint, Map<String, String> trustedFingerprints,
        boolean autoConnect, List<Branch> branches) {

    public RouterSettings(RouterEndpoint lastEndpoint, Map<String, String> trustedFingerprints) {
        this(lastEndpoint, trustedFingerprints, false, List.of());
    }

    public RouterSettings(RouterEndpoint lastEndpoint, Map<String, String> trustedFingerprints,
            boolean autoConnect) {
        this(lastEndpoint, trustedFingerprints, autoConnect, List.of());
    }

    public RouterSettings {
        Objects.requireNonNull(lastEndpoint, "lastEndpoint");
        trustedFingerprints = Map.copyOf(trustedFingerprints == null ? Map.of() : trustedFingerprints);
        branches = List.copyOf(branches == null ? List.of() : branches);
    }

    public static RouterSettings defaults() {
        return new RouterSettings(RouterEndpoint.defaults(), Map.of());
    }

    public Optional<String> trustedFingerprint(RouterEndpoint endpoint) {
        return Optional.ofNullable(trustedFingerprints.get(endpoint.address()));
    }

    public RouterSettings withLastEndpoint(RouterEndpoint endpoint) {
        return new RouterSettings(endpoint, trustedFingerprints, autoConnect, branches);
    }

    public RouterSettings withAutoConnect(boolean enabled) {
        return new RouterSettings(lastEndpoint, trustedFingerprints, enabled, branches);
    }

    public RouterSettings withTrustedFingerprint(RouterEndpoint endpoint, String fingerprint) {
        Map<String, String> copy = new HashMap<>(trustedFingerprints);
        copy.put(endpoint.address(), fingerprint.toLowerCase(Locale.ROOT));
        return new RouterSettings(lastEndpoint, copy, autoConnect, branches);
    }

    public RouterSettings withoutTrustedFingerprint(RouterEndpoint endpoint) {
        Map<String, String> copy = new HashMap<>(trustedFingerprints);
        copy.remove(endpoint.address());
        return new RouterSettings(lastEndpoint, copy, autoConnect, branches);
    }

    /** Adds a branch, or replaces the one with the same id (keeping its place in the list). */
    public RouterSettings withBranch(Branch branch) {
        List<Branch> copy = new ArrayList<>(branches);
        int at = indexOf(branch.id());
        if (at >= 0) {
            copy.set(at, branch);
        } else {
            copy.add(branch);
        }
        return new RouterSettings(lastEndpoint, trustedFingerprints, autoConnect, copy);
    }

    public RouterSettings withoutBranch(String id) {
        List<Branch> copy = new ArrayList<>(branches);
        copy.removeIf(b -> b.id().equals(id));
        return new RouterSettings(lastEndpoint, trustedFingerprints, autoConnect, copy);
    }

    /** The branch whose router is at this address, if it is one. */
    public Optional<Branch> branchAt(String address) {
        return branches.stream().filter(b -> b.address().equals(address)).findFirst();
    }

    private int indexOf(String id) {
        for (int i = 0; i < branches.size(); i++) {
            if (branches.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
