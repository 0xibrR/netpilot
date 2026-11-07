package org.netpilot.model;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Persisted router preferences: the last endpoint and the HTTPS certificates the user trusted.
 *
 * @param lastEndpoint        endpoint shown in the connection dialog
 * @param trustedFingerprints SHA-256 certificate fingerprint per {@link RouterEndpoint#address()}
 * @param autoConnect         connect to the last router at start-up (only with a remembered password)
 */
public record RouterSettings(RouterEndpoint lastEndpoint, Map<String, String> trustedFingerprints,
        boolean autoConnect) {

    public RouterSettings(RouterEndpoint lastEndpoint, Map<String, String> trustedFingerprints) {
        this(lastEndpoint, trustedFingerprints, false);
    }

    public RouterSettings {
        Objects.requireNonNull(lastEndpoint, "lastEndpoint");
        trustedFingerprints = Map.copyOf(trustedFingerprints == null ? Map.of() : trustedFingerprints);
    }

    public static RouterSettings defaults() {
        return new RouterSettings(RouterEndpoint.defaults(), Map.of());
    }

    public Optional<String> trustedFingerprint(RouterEndpoint endpoint) {
        return Optional.ofNullable(trustedFingerprints.get(endpoint.address()));
    }

    public RouterSettings withLastEndpoint(RouterEndpoint endpoint) {
        return new RouterSettings(endpoint, trustedFingerprints, autoConnect);
    }

    public RouterSettings withAutoConnect(boolean enabled) {
        return new RouterSettings(lastEndpoint, trustedFingerprints, enabled);
    }

    public RouterSettings withTrustedFingerprint(RouterEndpoint endpoint, String fingerprint) {
        Map<String, String> copy = new HashMap<>(trustedFingerprints);
        copy.put(endpoint.address(), fingerprint.toLowerCase(Locale.ROOT));
        return new RouterSettings(lastEndpoint, copy, autoConnect);
    }

    public RouterSettings withoutTrustedFingerprint(RouterEndpoint endpoint) {
        Map<String, String> copy = new HashMap<>(trustedFingerprints);
        copy.remove(endpoint.address());
        return new RouterSettings(lastEndpoint, copy, autoConnect);
    }
}
