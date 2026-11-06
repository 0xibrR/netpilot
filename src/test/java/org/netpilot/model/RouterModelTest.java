package org.netpilot.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RouterModelTest {

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.1", "192.168.88.1", "router.lan", "hex-1", "fe80::1", "[fe80::1]"})
    void acceptsAddresses(String host) {
        assertTrue(new RouterEndpoint(host, 443, true, "netpilot").validate().isEmpty(), host);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "http://10.0.0.1", "10.0.0.1/rest", "a b", "-router"})
    void rejectsAddresses(String host) {
        assertFalse(new RouterEndpoint(host, 443, true, "netpilot").validate().isEmpty(), host);
    }

    @Test
    void rejectsBadPortAndUsername() {
        assertEquals(2, new RouterEndpoint("10.0.0.1", 70_000, true, "user:name").validate().size());
        assertEquals(1, new RouterEndpoint("10.0.0.1", 443, true, "").validate().size());
    }

    @Test
    void buildsRestUris() {
        assertEquals(URI.create("https://10.0.0.1:443/rest"),
                new RouterEndpoint(" 10.0.0.1 ", 443, true, "u").restBaseUri());
        assertEquals(URI.create("http://[fe80::1]:80/rest"),
                new RouterEndpoint("fe80::1", 80, false, "u").restBaseUri());
        assertEquals("[fe80::1]:8443", new RouterEndpoint("[fe80::1]", 8443, true, "u").address());
    }

    @Test
    void trustedFingerprintsAreKeptPerAddress() {
        RouterEndpoint hex = new RouterEndpoint("10.0.0.1", 443, true, "netpilot");
        RouterEndpoint other = new RouterEndpoint("10.0.0.2", 443, true, "netpilot");

        RouterSettings settings = RouterSettings.defaults().withTrustedFingerprint(hex, "AB".repeat(32));

        assertEquals(Optional.of("ab".repeat(32)), settings.trustedFingerprint(hex));
        assertEquals(Optional.empty(), settings.trustedFingerprint(other));
        assertEquals(Optional.empty(), settings.withoutTrustedFingerprint(hex).trustedFingerprint(hex));
        assertEquals(hex, settings.withLastEndpoint(hex).lastEndpoint());
    }

    @Test
    void routerInfoSummaryFallsBackToBoardName() {
        assertEquals("hEX · RouterOS 7.25.1", new RouterInfo("", "hEX", "7.25.1", null).summary());
    }
}
