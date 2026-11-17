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
 * @param live                live updates through the RouterOS API (falls back to reading every few seconds)
 * @param livePort            the API port; 0 for the usual one (8728, or 8729 with HTTPS)
 */
public record RouterSettings(RouterEndpoint lastEndpoint, Map<String, String> trustedFingerprints,
        boolean autoConnect, List<Branch> branches, boolean live, int livePort) {

    public RouterSettings(RouterEndpoint lastEndpoint, Map<String, String> trustedFingerprints, boolean autoConnect,
            List<Branch> branches) {
        this(lastEndpoint, trustedFingerprints, autoConnect, branches, true, 0);
    }

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
        livePort = livePort < 0 || livePort > 65_535 ? 0 : livePort;
    }

    public static RouterSettings defaults() {
        return new RouterSettings(RouterEndpoint.defaults(), Map.of());
    }

    public Optional<String> trustedFingerprint(RouterEndpoint endpoint) {
        return Optional.ofNullable(trustedFingerprints.get(endpoint.address()));
    }

    public RouterSettings withLastEndpoint(RouterEndpoint endpoint) {
        return new RouterSettings(endpoint, trustedFingerprints, autoConnect, branches, live, livePort);
    }

    public RouterSettings withLive(boolean enabled, int port) {
        return new RouterSettings(lastEndpoint, trustedFingerprints, autoConnect, branches, enabled, port);
    }

    /** The API port to use with this endpoint: the one set, else 8729 with HTTPS and 8728 without. */
    public int livePortFor(RouterEndpoint endpoint) {
        return livePort > 0 ? livePort : endpoint.https() ? 8729 : 8728;
    }

    public RouterSettings withAutoConnect(boolean enabled) {
        return new RouterSettings(lastEndpoint, trustedFingerprints, enabled, branches, live, livePort);
    }

    public RouterSettings withTrustedFingerprint(RouterEndpoint endpoint, String fingerprint) {
        Map<String, String> copy = new HashMap<>(trustedFingerprints);
        copy.put(endpoint.address(), fingerprint.toLowerCase(Locale.ROOT));
        return new RouterSettings(lastEndpoint, copy, autoConnect, branches, live, livePort);
    }

    public RouterSettings withoutTrustedFingerprint(RouterEndpoint endpoint) {
        Map<String, String> copy = new HashMap<>(trustedFingerprints);
        copy.remove(endpoint.address());
        return new RouterSettings(lastEndpoint, copy, autoConnect, branches, live, livePort);
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
        return new RouterSettings(lastEndpoint, trustedFingerprints, autoConnect, copy, live, livePort);
    }

    public RouterSettings withoutBranch(String id) {
        List<Branch> copy = new ArrayList<>(branches);
        copy.removeIf(b -> b.id().equals(id));
        return new RouterSettings(lastEndpoint, trustedFingerprints, autoConnect, copy, live, livePort);
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
