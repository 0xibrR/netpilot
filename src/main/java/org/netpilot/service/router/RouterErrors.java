package org.netpilot.service.router;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLException;
import org.netpilot.i18n.Messages;
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
                            Messages.get("router.error.certificateFirstUse", endpoint.address()),
                            e, pin.presentedFingerprint())
                    : new RouterException(Kind.CERTIFICATE_CHANGED,
                            Messages.get("router.error.certificateChanged", endpoint.address()),
                            e, pin.presentedFingerprint());
        }
        if (e instanceof HttpConnectTimeoutException) {
            return new RouterException(Kind.TIMEOUT, Messages.get("router.error.noAnswer", endpoint.address()), e);
        }
        if (e instanceof HttpTimeoutException) {
            return new RouterException(Kind.TIMEOUT, Messages.get("router.error.slow"), e);
        }
        if (e instanceof SSLException) {
            return new RouterException(Kind.TLS_FAILURE, Messages.get("router.error.tls", endpoint.port()), e);
        }
        if (hasCause(e, ConnectException.class)) {
            return new RouterException(Kind.UNREACHABLE,
                    Messages.get("router.error.unreachable", endpoint.address(), serviceName(endpoint)), e);
        }
        return new RouterException(Kind.UNREACHABLE,
                Messages.get("router.error.connectionFailed", endpoint.address(), e.getMessage()), e);
    }

    /**
     * @return the parsed JSON body of a successful response
     */
    static JsonNode fromResponse(int status, byte[] body, String path, RouterEndpoint endpoint)
            throws RouterException {
        if (status >= 200 && status < 300) {
            if (body.length == 0) {
                return JSON.createObjectNode(); // e.g. 204 No Content after a delete
            }
            try {
                return JSON.readTree(body);
            } catch (JacksonException e) {
                throw new RouterException(Kind.UNEXPECTED_RESPONSE, Messages.get("router.error.unreadable", path), e);
            }
        }
        switch (status) {
            case 401 -> throw new RouterException(Kind.AUTHENTICATION, Messages.get("router.error.login"));
            case 403 -> throw new RouterException(Kind.PERMISSION,
                    Messages.get("router.error.permission", endpoint.username()));
            case 404 -> throw new RouterException(Kind.NOT_SUPPORTED, Messages.get("router.error.notFound", path));
            default -> {
                // fall through to the generic message below
            }
        }
        if (status >= 300 && status < 400) {
            throw new RouterException(Kind.UNEXPECTED_RESPONSE, Messages.get("router.error.redirect"));
        }
        String detail = errorDetail(body);
        // RouterOS: {"error":400,"message":"Bad Request","detail":"failure: already have user with this name"}
        if (status == 400 && detail.contains("already have")) {
            throw new RouterException(Kind.ALREADY_EXISTS, Messages.get("router.error.exists"));
        }
        throw new RouterException(Kind.UNEXPECTED_RESPONSE, Messages.get("router.error.http", status, detail));
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
