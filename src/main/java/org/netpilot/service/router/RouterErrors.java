package org.netpilot.service.router;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLException;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.service.router.RouterException.Kind;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns low-level failures into {@link RouterException}s with messages a user can act on.
 * Kept separate from the client so every rule is easy to find and test.
 */
final class RouterErrors {

    private static final JsonMapper JSON = JsonMapper.shared();

    private RouterErrors() {
    }

    static RouterException fromIo(IOException e, RouterEndpoint endpoint) {
        PinnedTrustManager.PinMismatchException pin = findPinMismatch(e);
        if (pin != null) {
            return pin.firstUse()
                    ? new RouterException(Kind.CERTIFICATE_UNTRUSTED,
                            "This is the first HTTPS connection to " + endpoint.address()
                                    + ". Confirm the router's certificate to continue.",
                            e, pin.presentedFingerprint())
                    : new RouterException(Kind.CERTIFICATE_CHANGED,
                            "The certificate of " + endpoint.address() + " is different from the one you"
                                    + " trusted before. Someone may be intercepting the connection. Only"
                                    + " continue if you replaced the router's certificate yourself.",
                            e, pin.presentedFingerprint());
        }
        if (e instanceof HttpConnectTimeoutException) {
            return new RouterException(Kind.TIMEOUT,
                    "The router at " + endpoint.address() + " did not answer. Check the address and"
                            + " that this computer can reach the router.",
                    e);
        }
        if (e instanceof HttpTimeoutException) {
            return new RouterException(Kind.TIMEOUT, "The router took too long to respond. Try again.", e);
        }
        if (e instanceof SSLException) {
            return new RouterException(Kind.TLS_FAILURE,
                    "The HTTPS connection failed. Make sure the www-ssl service has a certificate"
                            + " (see docs/ROUTER_SETUP.md) and that port " + endpoint.port()
                            + " is the HTTPS port.",
                    e);
        }
        if (hasCause(e, ConnectException.class)) {
            return new RouterException(Kind.UNREACHABLE,
                    "Could not connect to " + endpoint.address() + ". Check the address and port, and"
                            + " that the " + serviceName(endpoint) + " service is enabled in IP > Services.",
                    e);
        }
        return new RouterException(Kind.UNREACHABLE,
                "Connection to " + endpoint.address() + " failed: " + e.getMessage(), e);
    }

    /**
     * @return the parsed JSON body of a successful response
     */
    static JsonNode fromResponse(int status, byte[] body, String path, RouterEndpoint endpoint)
            throws RouterException {
        if (status >= 200 && status < 300) {
            try {
                return JSON.readTree(body);
            } catch (JacksonException e) {
                throw new RouterException(Kind.UNEXPECTED_RESPONSE,
                        "The router sent a response NetPilot could not read (" + path + ").", e);
            }
        }
        switch (status) {
            case 401 -> throw new RouterException(Kind.AUTHENTICATION, "Wrong user name or password.");
            case 403 -> throw new RouterException(Kind.PERMISSION,
                    "User '" + endpoint.username() + "' may not use the REST API. Give its group the"
                            + " 'read', 'api' and 'rest-api' policies (see docs/ROUTER_SETUP.md).");
            case 404 -> throw new RouterException(Kind.NOT_SUPPORTED,
                    "The router does not offer " + path + ". NetPilot needs RouterOS 7.1 or newer.");
            default -> {
                // fall through to the generic message below
            }
        }
        if (status >= 300 && status < 400) {
            throw new RouterException(Kind.UNEXPECTED_RESPONSE,
                    "The router redirected the request. Check that HTTPS/HTTP matches the port.");
        }
        throw new RouterException(Kind.UNEXPECTED_RESPONSE,
                "The router answered with HTTP " + status + errorDetail(body) + ".");
    }

    /** RouterOS errors look like {@code {"error":400,"message":"Bad Request","detail":"..."}}. */
    private static String errorDetail(byte[] body) {
        try {
            JsonNode node = JSON.readTree(body);
            String detail = node.path("detail").asString("");
            String message = node.path("message").asString("");
            String text = detail.isEmpty() ? message : detail;
            return text.isEmpty() ? "" : ": " + text;
        } catch (JacksonException e) {
            return "";
        }
    }

    private static String serviceName(RouterEndpoint endpoint) {
        return endpoint.https() ? "www-ssl" : "www";
    }

    private static PinnedTrustManager.PinMismatchException findPinMismatch(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PinnedTrustManager.PinMismatchException pin) {
                return pin;
            }
        }
        return null;
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
