package org.netpilot.service.router;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.HotspotProfile;
import org.netpilot.model.HotspotServer;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.model.RouterInfo;
import org.netpilot.service.router.RouterException.Kind;

class RouterConnectorTest {

    private static final String USER = "netpilot";
    private static final String PASSWORD = "s3cret:pass";

    // generous request timeout: an HTTPS handshake on a busy machine can take over half a second
    private final RouterConnector connector = new RouterConnector(Duration.ofSeconds(3), Duration.ofSeconds(3));

    private static RouterEndpoint http(int port) {
        return new RouterEndpoint("127.0.0.1", port, false, USER);
    }

    private static RouterEndpoint https(int port) {
        return new RouterEndpoint("127.0.0.1", port, true, USER);
    }

    private RouterException failure(RouterEndpoint endpoint, String password, String pin) {
        return assertThrows(RouterException.class, () -> connector.connect(endpoint, password.toCharArray(), pin));
    }

    @Nested
    class OverHttp {

        @Test
        void logsInAndReadsRouterInfo() throws Exception {
            try (FakeRouter router = FakeRouter.http(USER, PASSWORD)) {
                char[] password = PASSWORD.toCharArray();

                RouterConnector.Connection connection = connector.connect(http(router.port()), password, null);

                assertEquals(new RouterInfo("hEX", "hEX", "7.25.1 (stable)", "14h10m53s"), connection.info());
                assertEquals("hEX · RouterOS 7.25.1 (stable)", connection.info().summary());
                assertArrayEquals(new char[PASSWORD.length()], password, "password must be wiped");
                assertEquals(".proplist=board-name,version,uptime", router.lastQuery("/rest/system/resource"));
                connection.client().close();
            }
        }

        @Test
        void readsHotspotProfilesAndServers() throws Exception {
            try (FakeRouter router = FakeRouter.http(USER, PASSWORD);
                    RouterClient client = connector.connect(http(router.port()), PASSWORD.toCharArray(), null)
                            .client()) {

                assertEquals(List.of(new HotspotProfile("default", ""), new HotspotProfile("2M", "2M/2M")),
                        client.hotspotProfiles());
                assertEquals(List.of(new HotspotServer("hotspot1", "bridge_hotspot", false),
                        new HotspotServer("old", "ether5", true)), client.hotspotServers());
            }
        }

        @Test
        void wrongPassword() throws Exception {
            try (FakeRouter router = FakeRouter.http(USER, PASSWORD)) {
                RouterException e = failure(http(router.port()), "wrong", null);

                assertEquals(Kind.AUTHENTICATION, e.kind());
                assertFalse(e.getMessage().contains(PASSWORD), "never echo the password");
            }
        }

        @Test
        void missingPolicy() throws Exception {
            try (FakeRouter router = FakeRouter.http(USER, PASSWORD)) {
                router.route("/rest/system/identity", 403, "{\"error\":403,\"message\":\"Forbidden\"}");

                RouterException e = failure(http(router.port()), PASSWORD, null);

                assertEquals(Kind.PERMISSION, e.kind());
                assertTrue(e.getMessage().contains("rest-api"));
            }
        }

        @Test
        void restApiMissing() throws Exception {
            try (FakeRouter router = FakeRouter.http(USER, PASSWORD)) {
                router.route("/rest/system/identity", 404, "");

                assertEquals(Kind.NOT_SUPPORTED, failure(http(router.port()), PASSWORD, null).kind());
            }
        }

        @Test
        void routerErrorDetailIsShown() throws Exception {
            try (FakeRouter router = FakeRouter.http(USER, PASSWORD);
                    RouterClient client = connector.connect(http(router.port()), PASSWORD.toCharArray(), null)
                            .client()) {
                router.route("/rest/ip/hotspot", 400,
                        "{\"error\":400,\"message\":\"Bad Request\",\"detail\":\"no such command\"}");

                RouterException e = assertThrows(RouterException.class, client::hotspotServers);

                assertEquals(Kind.UNEXPECTED_RESPONSE, e.kind());
                assertTrue(e.getMessage().contains("HTTP 400: no such command"), e.getMessage());
            }
        }

        @Test
        void unreadableResponses() throws Exception {
            try (FakeRouter router = FakeRouter.http(USER, PASSWORD);
                    RouterClient client = connector.connect(http(router.port()), PASSWORD.toCharArray(), null)
                            .client()) {
                router.route("/rest/ip/hotspot", 200, "not json");
                router.route("/rest/ip/hotspot/user/profile", 200, "{\"name\":\"not a list\"}");

                assertEquals(Kind.UNEXPECTED_RESPONSE,
                        assertThrows(RouterException.class, client::hotspotServers).kind());
                assertEquals(Kind.UNEXPECTED_RESPONSE,
                        assertThrows(RouterException.class, client::hotspotProfiles).kind());
            }
        }

        @Test
        void redirectsAreNotFollowed() throws Exception {
            try (FakeRouter router = FakeRouter.http(USER, PASSWORD)) {
                router.redirect("/rest/system/identity", "http://evil.example/steal");

                assertEquals(Kind.UNEXPECTED_RESPONSE, failure(http(router.port()), PASSWORD, null).kind());
            }
        }

        @Test
        void slowRouterTimesOut() throws Exception {
            try (FakeRouter router = FakeRouter.http(USER, PASSWORD)) {
                router.delayReplies(Duration.ofSeconds(5)); // longer than the 3 s request timeout

                assertEquals(Kind.TIMEOUT, failure(http(router.port()), PASSWORD, null).kind());
            }
        }

        @Test
        void nothingListening() throws Exception {
            int closedPort;
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                closedPort = socket.getLocalPort();
            }

            RouterException e = failure(http(closedPort), PASSWORD, null);

            assertEquals(Kind.UNREACHABLE, e.kind());
            assertTrue(e.getMessage().contains("www service"), e.getMessage());
        }

        @Test
        void invalidEndpointIsRejectedAndPasswordStillWiped() {
            char[] password = PASSWORD.toCharArray();

            assertThrows(IllegalArgumentException.class,
                    () -> connector.connect(new RouterEndpoint("", 0, false, "a b"), password, null));
            assertArrayEquals(new char[PASSWORD.length()], password);
        }
    }

    @Nested
    class OverHttps {

        @TempDir
        static Path dir;

        static TestCertificate certificate;

        @BeforeAll
        static void createCertificate() throws Exception {
            certificate = TestCertificate.create(dir);
        }

        @Test
        void firstConnectionAsksToTrustTheCertificate() throws Exception {
            try (FakeRouter router = FakeRouter.https(certificate.serverContext(), USER, PASSWORD)) {
                RouterException e = failure(https(router.port()), PASSWORD, null);

                assertEquals(Kind.CERTIFICATE_UNTRUSTED, e.kind());
                assertEquals(certificate.fingerprint(), e.presentedFingerprint().orElseThrow());
            }
        }

        /** The certificate says CN=router while we connect to 127.0.0.1: the pin alone decides. */
        @Test
        void trustedCertificateConnectsWithoutHostNameMatch() throws Exception {
            try (FakeRouter router = FakeRouter.https(certificate.serverContext(), USER, PASSWORD)) {
                String pastedWithColons = certificate.fingerprint().toUpperCase().replaceAll("(..)(?!$)", "$1:");

                RouterConnector.Connection connection = connector.connect(https(router.port()), PASSWORD.toCharArray(),
                        pastedWithColons);

                assertEquals("hEX", connection.info().identity());
                connection.client().close();
            }
        }

        @Test
        void changedCertificateIsRefused() throws Exception {
            try (FakeRouter router = FakeRouter.https(certificate.serverContext(), USER, PASSWORD)) {
                RouterException e = failure(https(router.port()), PASSWORD, "0".repeat(64));

                assertEquals(Kind.CERTIFICATE_CHANGED, e.kind());
                assertEquals(certificate.fingerprint(), e.presentedFingerprint().orElseThrow());
            }
        }

        @Test
        void httpsAgainstPlainHttpPort() throws IOException {
            try (FakeRouter router = FakeRouter.http(USER, PASSWORD)) {
                assertEquals(Kind.TLS_FAILURE, failure(https(router.port()), PASSWORD, null).kind());
            }
        }
    }
}
