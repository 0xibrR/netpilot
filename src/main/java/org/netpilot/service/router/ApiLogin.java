package org.netpilot.service.router;

import java.util.Arrays;
import java.util.Objects;

/**
 * The name and password of the router session, kept in memory for the live connection (and its
 * reconnects) while the router stays connected. Wiped when the session closes; never written to
 * disk or logged. The REST connection keeps the same secret in its Authorization header anyway.
 */
public final class ApiLogin implements AutoCloseable {

    private final String host;
    private final String username;
    private final char[] password;
    private final String trustedFingerprint;
    private volatile boolean wiped;

    ApiLogin(String host, String username, char[] password, String trustedFingerprint) {
        this.host = Objects.requireNonNull(host);
        this.username = Objects.requireNonNull(username);
        this.password = password.clone();
        this.trustedFingerprint = trustedFingerprint;
    }

    public String host() {
        return host;
    }

    public String username() {
        return username;
    }

    /** The certificate fingerprint trusted for this router (for api-ssl), or null. */
    public String trustedFingerprint() {
        return trustedFingerprint;
    }

    /**
     * Opens an API session with these credentials.
     *
     * @throws ApiConnection.ApiException also when the session was already closed
     */
    public ApiConnection open(int port, boolean tls, java.time.Duration timeout) throws ApiConnection.ApiException {
        if (wiped) {
            throw new ApiConnection.ApiException(ApiConnection.ApiException.Kind.BROKEN, "Session closed", null);
        }
        return ApiConnection.open(host, port, tls, trustedFingerprint, username, password, timeout);
    }

    @Override
    public void close() {
        wiped = true;
        Arrays.fill(password, '\0');
    }
}
