package org.netpilot.service.router;

import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.model.RouterInfo;

/**
 * Opens authenticated sessions with routers.
 *
 * <p>Security rules applied here:
 * <ul>
 * <li>HTTPS trusts only the pinned certificate ({@link PinnedTrustManager}).</li>
 * <li>The password is used once to build the auth header, then the caller's array is wiped.</li>
 * <li>Redirects are never followed, so credentials cannot be sent to another host.</li>
 * </ul>
 */
public final class RouterConnector {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final Duration connectTimeout;
    private final Duration requestTimeout;

    public RouterConnector() {
        this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT);
    }

    /** For tests: shorter timeouts. */
    RouterConnector(Duration connectTimeout, Duration requestTimeout) {
        this.connectTimeout = Objects.requireNonNull(connectTimeout);
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
    }

    /** An open session plus what the router reported while logging in. */
    public record Connection(RouterClient client, RouterInfo info) {
    }

    /**
     * Logs in and reads the router's identity, which proves the address, certificate and
     * credentials are all correct.
     *
     * @param password           cleared (filled with zeros) before this method returns
     * @param trustedFingerprint SHA-256 fingerprint the user trusted for this router, or
     *                           {@code null} if none yet (HTTPS then fails with
     *                           {@link RouterException.Kind#CERTIFICATE_UNTRUSTED})
     * @throws IllegalArgumentException if the endpoint is invalid
     */
    public Connection connect(RouterEndpoint endpoint, char[] password, String trustedFingerprint)
            throws RouterException {
        try {
            List<String> errors = endpoint.validate();
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException(String.join("\n", errors));
            }
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .version(HttpClient.Version.HTTP_1_1);
            if (endpoint.https()) {
                builder.sslContext(pinnedContext(trustedFingerprint));
            }

            RestRouterClient client = new RestRouterClient(endpoint, builder.build(),
                    basicAuthorization(endpoint.username(), password), requestTimeout);
            try {
                return new Connection(client, client.info());
            } catch (RouterException | RuntimeException e) {
                client.close();
                throw e;
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static SSLContext pinnedContext(String trustedFingerprint) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new PinnedTrustManager(trustedFingerprint)}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS is not available in this Java runtime", e);
        }
    }

    /** {@code Basic base64(user:password)}, building the bytes without an intermediate password String. */
    static String basicAuthorization(String username, char[] password) {
        CharBuffer chars = CharBuffer.allocate(username.length() + 1 + password.length);
        chars.put(username).put(':').put(password).flip();
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(chars);
        byte[] raw = new byte[bytes.remaining()];
        bytes.get(raw);
        try {
            return "Basic " + Base64.getEncoder().encodeToString(raw);
        } finally {
            Arrays.fill(raw, (byte) 0);
            Arrays.fill(chars.array(), '\0');
            if (bytes.hasArray()) {
                Arrays.fill(bytes.array(), (byte) 0);
            }
        }
    }
}
