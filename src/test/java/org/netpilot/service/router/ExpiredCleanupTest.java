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
        accounts = new org.netpilot.service.ResellerAccounts(new org.netpilot.repository.ResellerRepository(
                dir.resolve("r.csv"), dir.resolve("ledger.csv")), java.time.Clock.systemUTC());
        cleanup = new ExpiredCleanup(new org.netpilot.service.UsageTracker(new ReportService(), usage, accounts),
                new RouterUserActions());
    }

    private org.netpilot.service.ResellerAccounts accounts;

    @Test
    void aPointOfSaleKeepsTheSaleOfACardTheCleanUpDeletes() throws Exception {
        usage.saveAll(java.util.List.of(new UsageRecord(client.endpoint().address(), "old", "2M", BATCH,
                Optional.empty())));
        router.addUser("sold-card", "p", "1M", "", BATCH, "0s", 0, 0, false);
        var shop = accounts.save(new org.netpilot.model.Reseller("shop", "Shop", "",
                org.netpilot.model.Reseller.CommissionType.PERCENT, java.math.BigDecimal.TEN,
                org.netpilot.model.Reseller.Billing.ON_SALE));
        accounts.handOver(shop, java.util.List.of(new org.netpilot.service.ResellerAccounts.Card(
                client.endpoint().address(), "sold-card", "1M")), java.util.Map.of("1M", "1000"), false);
        // the customer uses up the card while NetPilot is closed: one hour of a one-hour card
        router.users().values().stream().filter(u -> u.name().equals("sold-card")).findFirst().orElseThrow();
        String usedUp = router.addUser("sold-card-2", "p", "1M", "1h", BATCH, "1h", 10, 10, false);
        accounts.handOver(shop, java.util.List.of(new org.netpilot.service.ResellerAccounts.Card(
                client.endpoint().address(), "sold-card-2", "1M")), java.util.Map.of("1M", "1000"), false);

        BulkResult result = cleanup.run(client, TODAY);

        assertEquals(1, result.succeeded());
        assertTrue(router.users().values().stream().noneMatch(u -> u.id().equals(usedUp)), "deleted from the router");
        var statement = accounts.statements().getFirst();
        assertEquals(1, statement.sold(), "the deleted card is still the shop's sale");
        assertEquals(new java.math.BigDecimal("900"), statement.owes(), "1000 minus 10% commission");
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
