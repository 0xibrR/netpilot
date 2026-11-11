package org.netpilot.service.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.netpilot.model.DnsAnswer;
import org.netpilot.model.DnsSettings;
import org.netpilot.model.LogEntry;
import org.netpilot.model.PingReply;
import org.netpilot.model.RouterEndpoint;

/** Traceroute probes, DNS and the log, read through the REST client from a fake router. */
class NetworkToolsTest {

    private static final String USER = "netpilot";
    private static final String PASSWORD = "secret";

    private FakeRouter router;
    private RouterClient client;

    @BeforeEach
    void connect() throws Exception {
        router = FakeRouter.http(USER, PASSWORD);
        client = new RouterConnector(Duration.ofSeconds(2), Duration.ofSeconds(2))
                .connect(new RouterEndpoint("127.0.0.1", router.port(), false, USER), PASSWORD.toCharArray(), null)
                .client();
    }

    @AfterEach
    void disconnect() {
        client.close();
        router.close();
    }

    @Test
    void aProbeWithAShortTtlNamesTheRouterOnTheWay() throws Exception {
        router.route("/rest/ping", 200, "[{\"seq\":\"0\",\"host\":\"10.0.0.1\",\"size\":\"84\",\"ttl\":\"64\","
                + "\"time\":\"2ms300us\",\"status\":\"TTL exceeded\",\"sent\":\"1\",\"received\":\"0\"}]");

        PingReply hop = client.probe("8.8.8.8", 1);

        assertTrue(hop.ttlExceeded());
        assertFalse(hop.answered(), "a router on the way is not the address");
        assertEquals("10.0.0.1", hop.host());
        assertEquals(Duration.ofNanos(2_300_000), hop.time().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> client.probe("8.8.8.8", 0));
        assertThrows(IllegalArgumentException.class, () -> client.probe("8.8.8.8; /system reboot", 3));
    }

    @Test
    void readsTheDnsSettings() throws Exception {
        router.route("/rest/ip/dns", 200, "{\"servers\":\"8.8.8.8,1.1.1.1\",\"dynamic-servers\":\"192.168.1.1\","
                + "\"allow-remote-requests\":\"true\"}");

        DnsSettings dns = client.dnsSettings();

        assertEquals(List.of("8.8.8.8", "1.1.1.1"), dns.servers());
        assertEquals(List.of("192.168.1.1"), dns.dynamicServers());
        assertTrue(dns.remoteRequests());
        assertFalse(dns.noServer());
        assertTrue(new DnsSettings(List.of(), List.of(), false).noServer());
    }

    @Test
    void aLookupReadsTheRoutersCacheAndFollowsCnames() throws Exception {
        router.route("/rest/ping", 200,
                "[{\"host\":\"93.184.216.34\",\"sent\":\"1\",\"received\":\"1\",\"time\":\"9ms\"}]");
        router.route("/rest/ip/dns/cache/all", 200, "[{\"name\":\"www.example.com\",\"type\":\"CNAME\","
                + "\"data\":\"edge.example.net\",\"ttl\":\"5m\"},{\"name\":\"edge.example.net\",\"type\":\"A\","
                + "\"data\":\"93.184.216.34\",\"ttl\":\"1m30s\"}]");

        DnsAnswer answer = client.resolve("www.example.com");

        assertEquals(2, answer.records().size(), "the same cache lines read twice count once");
        assertEquals(List.of("93.184.216.34"), answer.addresses());
        assertEquals(Duration.ofSeconds(90), answer.records().get(1).ttl().orElseThrow());
        assertTrue(router.lastQuery("/rest/ip/dns/cache/all").contains("name=edge.example.net"),
                "the CNAME was followed");
    }

    @Test
    void withoutTheCacheTheLookupStillGivesTheAddressThePingUsed() throws Exception {
        router.route("/rest/ping", 200, "[{\"host\":\"142.250.74.46\",\"sent\":\"1\",\"received\":\"0\","
                + "\"status\":\"timeout\"}]");
        router.route("/rest/ip/dns/cache/all", 400,
                "{\"error\":400,\"message\":\"Bad Request\",\"detail\":\"no such command\"}");
        router.route("/rest/ip/dns/cache", 403, "{\"error\":403,\"message\":\"Forbidden\"}");

        DnsAnswer answer = client.resolve("google.com");

        assertEquals(List.of("142.250.74.46"), answer.addresses());
        assertEquals("A", answer.records().getFirst().type());
    }

    @Test
    void aLookupTakesOnlyHostNames() {
        assertEquals("google.com", DnsAnswer.validName(" google.com "));
        for (String bad : List.of("", "8.8.8.8", "2001:4860::8888", "a b", "x\";/system reboot")) {
            assertThrows(IllegalArgumentException.class, () -> DnsAnswer.validName(bad), bad);
        }
    }

    @Test
    void readsTheLog() throws Exception {
        router.route("/rest/log", 200, "[{\".id\":\"*A\",\"time\":\"10:00:01\",\"topics\":\"hotspot,info,debug\","
                + "\"message\":\"card1 (10.5.50.7): logged in\"},{\".id\":\"*B\",\"time\":\"10:00:05\","
                + "\"topics\":\"system,error,critical\",\"message\":\"login failure for user admin\"},"
                + "{\"time\":\"no id\",\"message\":\"skipped\"}]");

        List<LogEntry> log = client.log();

        assertEquals(2, log.size());
        assertEquals(List.of("hotspot", "info", "debug"), log.getFirst().topics());
        assertEquals(LogEntry.Severity.ERROR, log.get(1).severity());
    }
}
