package org.netpilot.service.router;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.service.router.RouterException.Kind;

/** Small units of the router package that are clearer to test directly. */
class RouterUnitsTest {

    private static final RouterEndpoint ENDPOINT = new RouterEndpoint("10.0.0.1", 443, true, "netpilot");

    @Test
    void basicAuthorizationEncodesUtf8AndWipesBuffers() {
        char[] password = "pässword".toCharArray();

        String header = RouterConnector.basicAuthorization("netpilot", password);

        assertEquals(
                "Basic " + Base64.getEncoder().encodeToString("netpilot:pässword".getBytes(StandardCharsets.UTF_8)),
                header);
        assertArrayEquals("pässword".toCharArray(), password, "the caller owns and wipes its own array");
    }

    @Test
    void fingerprintsAreNormalizedAndReadable() {
        assertEquals("abcd1234", CertificateFingerprints.normalize("AB:CD 12:34"));
        assertEquals("", CertificateFingerprints.normalize(null));

        String readable = CertificateFingerprints.readable("a".repeat(64));
        assertEquals("aaaa aaaa aaaa aaaa aaaa aaaa aaaa aaaa\naaaa aaaa aaaa aaaa aaaa aaaa aaaa aaaa", readable);
    }

    @Test
    void connectTimeoutMeansRouterDidNotAnswer() {
        RouterException e = RouterErrors.fromIo(new HttpConnectTimeoutException("timed out"), ENDPOINT);

        assertEquals(Kind.TIMEOUT, e.kind());
        assertEquals(true, e.getMessage().contains("10.0.0.1:443"));
    }

    @Test
    void otherIoFailuresAreReportedAsUnreachable() {
        assertEquals(Kind.UNREACHABLE, RouterErrors.fromIo(new IOException("reset"), ENDPOINT).kind());
    }

    @Test
    void pinnedTrustManagerRejectsMissingChainAndClients() {
        PinnedTrustManager trust = new PinnedTrustManager("ab".repeat(32));

        assertThrows(CertificateException.class, () -> trust.checkServerTrusted(new X509Certificate[0], "EC"));
        assertThrows(CertificateException.class, () -> trust.checkClientTrusted(new X509Certificate[0], "EC"));
        assertEquals(0, trust.getAcceptedIssuers().length);
    }
}
