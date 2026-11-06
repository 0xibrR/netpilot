package org.netpilot.service.router;

import java.util.Objects;
import java.util.Optional;

/**
 * A router operation failed. {@link #getMessage()} is written for the user; it never contains
 * the password.
 */
public final class RouterException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Why the operation failed, so the UI can react (e.g. ask to trust a certificate). */
    public enum Kind {
        /** Wrong user name or password (HTTP 401). */
        AUTHENTICATION,
        /** The user exists but lacks a required policy (HTTP 403). */
        PERMISSION,
        /** Nothing answered at the address and port. */
        UNREACHABLE,
        /** The router did not answer in time. */
        TIMEOUT,
        /** First HTTPS connection: the certificate must be confirmed by the user. */
        CERTIFICATE_UNTRUSTED,
        /** The certificate differs from the one the user trusted before. */
        CERTIFICATE_CHANGED,
        /** HTTPS failed for another reason (e.g. no certificate on www-ssl). */
        TLS_FAILURE,
        /** The REST API or a menu is not available on this router. */
        NOT_SUPPORTED,
        /** An item with this name already exists (e.g. a hotspot user name). */
        ALREADY_EXISTS,
        /** The router answered with something we could not understand. */
        UNEXPECTED_RESPONSE
    }

    private final Kind kind;
    private final String presentedFingerprint;

    public RouterException(Kind kind, String message) {
        this(kind, message, null, null);
    }

    public RouterException(Kind kind, String message, Throwable cause) {
        this(kind, message, cause, null);
    }

    RouterException(Kind kind, String message, Throwable cause, String presentedFingerprint) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.presentedFingerprint = presentedFingerprint;
    }

    public Kind kind() {
        return kind;
    }

    /** SHA-256 fingerprint of the router's certificate, for certificate errors. */
    public Optional<String> presentedFingerprint() {
        return Optional.ofNullable(presentedFingerprint);
    }

    /**
     * Whether every following request would fail the same way (connection, login, permission,
     * HTTPS), so a batch operation should stop instead of trying the next item.
     */
    public boolean affectsEveryRequest() {
        return switch (kind) {
            case AUTHENTICATION, PERMISSION, UNREACHABLE, TIMEOUT, CERTIFICATE_UNTRUSTED, CERTIFICATE_CHANGED,
                    TLS_FAILURE ->
                true;
            case NOT_SUPPORTED, ALREADY_EXISTS, UNEXPECTED_RESPONSE -> false;
        };
    }
}
