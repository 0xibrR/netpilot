package org.netpilot.service.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.model.UsageRecord;
import org.netpilot.repository.UsageRepository;
import org.netpilot.service.ExpiredCleanup;
import org.netpilot.service.ReportService;
import org.netpilot.service.router.RouterUserActions.BulkResult;

class ExpiredCleanupTest {

    private static final String USER = "netpilot";
    private static final String PASSWORD = "secret";
    private static final String BATCH = "NetPilot 2026-11-06 21:56 #b062";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);

    @TempDir
    Path dir;

    private FakeRouter router;
    private RouterClient client;
    private UsageRepository usage;
    private ExpiredCleanup cleanup;

    @BeforeEach
    void connect() throws Exception {
        router = FakeRouter.http(USER, PASSWORD);
        client = new RouterConnector(Duration.ofSeconds(2), Duration.ofSeconds(2))
                .connect(new RouterEndpoint("127.0.0.1", router.port(), false, USER), PASSWORD.toCharArray(), null)
                .client();
        usage = new UsageRepository(dir.resolve("usage.csv"));
        cleanup = new ExpiredCleanup(new ReportService(), usage, new RouterUserActions());
    }

    @AfterEach
    void disconnect() {
        client.close();
        router.close();
    }

    @Test
    void deletesOnlyExpiredCardsAfterSavingTheirSale() throws Exception {
        // tracked once before, so newly seen used cards get today's date
        usage.saveAll(java.util.List.of(new UsageRecord(client.endpoint().address(), "old", "2M", BATCH,
                Optional.empty())));
        router.addUser("used-up", "p1", "2M", "1h", BATCH, "1h", 10, 10, false);
        router.addUser("time-left", "p2", "2M", "8h", BATCH, "1h", 10, 10, false);
        router.addUser("unlimited", "p3", "2M", "", BATCH, "9h", 10, 10, false);
        router.addUser("default-trial", "", "default", "1h", "", "2h", 10, 10, false);

        BulkResult result = cleanup.run(client, TODAY);

        assertEquals(new BulkResult(1, 1, java.util.List.of()), result);
        assertTrue(router.users().values().stream().noneMatch(u -> u.name().equals("used-up")));
        assertEquals(3, router.users().size(), "time left, unlimited and the trial user stay");
        assertTrue(usage.findAll().stream()
                .anyMatch(r -> r.username().equals("used-up") && r.firstUsed().equals(Optional.of(TODAY))),
                "the sale is kept for Earnings & sales");
    }

    @Test
    void nothingExpiredChangesNothing() throws Exception {
        router.addUser("time-left", "p2", "2M", "8h", BATCH, "1h", 10, 10, false);

        BulkResult result = cleanup.run(client, TODAY);

        assertEquals(new BulkResult(0, 0, java.util.List.of()), result);
        assertEquals(1, router.users().size());
        assertFalse(Files.exists(dir.resolve("usage.csv")), "no sales file written when nothing is deleted");
    }
}
