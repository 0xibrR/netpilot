package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.LedgerEvent;
import org.netpilot.model.LedgerEvent.Kind;
import org.netpilot.model.Reseller;
import org.netpilot.model.RouterUser;
import org.netpilot.model.UsageRecord;
import org.netpilot.repository.ResellerRepository;
import org.netpilot.service.ResellerAccounts.Card;
import org.netpilot.service.ResellerAccounts.DirectSummary;
import org.netpilot.service.ResellerAccounts.Statement;

class QuickSaleTest {

    private static final String ROUTER = "10.0.0.1:80";
    private static final String OLD = "NetPilot 2026-11-01 10:00 #aaaa";
    private static final String NEW = "NetPilot 2026-11-09 10:00 #bbbb";
    private static final Map<String, String> PRICES = Map.of("1M", "1000 ل.س", "2M", "2000 ل.س");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    @TempDir
    Path dir;

    private ResellerRepository repository;
    private ResellerAccounts accounts;
    private QuickSale sale;

    @BeforeEach
    void setUp() {
        repository = new ResellerRepository(dir.resolve("r.csv"), dir.resolve("l.csv"));
        accounts = new ResellerAccounts(repository, Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"),
                ZoneOffset.UTC));
        sale = new QuickSale(accounts);
    }

    private static RouterUser card(String name, String profile, String batch) {
        return new RouterUser("*1", name, profile, "all", Duration.ZERO, "8h", 0, 0, false, batch);
    }

    private static List<RouterUser> router() {
        return List.of(card("new-1", "1M", NEW), card("old-2", "1M", OLD), card("old-1", "1M", OLD),
                new RouterUser("*2", "used", "1M", "all", Duration.ofMinutes(5), "8h", 10, 10, false, OLD),
                card("online", "1M", OLD), card("two", "2M", NEW),
                card("hand-made", "1M", ""), new RouterUser("*9", "off", "1M", "all", Duration.ZERO, "", 0, 0, true,
                        OLD));
    }

    private RouterUser pick(String profile) throws Exception {
        return QuickSale.pick(ROUTER, router(), Set.of(), accounts.holders(), profile).orElseThrow();
    }

    @Test
    void picksTheOldestBatchFirstAndOnlyCardsReadyToSell() throws Exception {
        Map<String, String> holders = accounts.holders();

        assertEquals(Map.of("1M", 3, "2M", 1), QuickSale.stock(ROUTER, router(), Set.of("online"), holders),
                "not used, not disabled, not online, not hand-made, not with a point of sale");
        assertEquals("old-1", QuickSale.pick(ROUTER, router(), Set.of("online"), holders, "1M").orElseThrow().name());
        assertEquals(Optional.empty(), QuickSale.pick(ROUTER, router(), Set.of(), holders, "5M"));
    }

    @Test
    void aSoldCardIsNeverPickedAgainAndIsRecordedOnce() throws Exception {
        RouterUser first = pick("1M");
        QuickSale.Reservation reserved = sale.reserve(new Card(ROUTER, first.name(), "1M"), PRICES).orElseThrow();

        // the next press, before the first receipt is even printed
        assertNotEquals(first.name(), pick("1M").name(), "the reserved card is taken");
        assertEquals(Optional.empty(), sale.reserve(new Card(ROUTER, first.name(), "1M"), PRICES),
                "the same card cannot be reserved twice");

        sale.confirm(reserved);
        sale.confirm(reserved); // a retry records nothing more

        DirectSummary summary = sale.summary(TODAY);
        assertEquals(1, summary.sold());
        assertEquals(new BigDecimal("1000"), summary.totalToday());
        assertEquals(List.of(Kind.DIRECT_RESERVE, Kind.DIRECT_SALE),
                repository.ledger().stream().map(LedgerEvent::kind).toList());
    }

    @Test
    void directSalesNeverAppearInAnyPointOfSaleOrItsBalance() throws Exception {
        Reseller shop = accounts.save(new Reseller("shop", "محل علي", "", Reseller.CommissionType.PERCENT,
                BigDecimal.TEN, Reseller.Billing.ON_SALE));
        accounts.handOver(shop, List.of(new Card(ROUTER, "new-1", "1M")), PRICES, false);
        QuickSale.Reservation reserved = sale.reserve(new Card(ROUTER, "old-1", "1M"), PRICES).orElseThrow();
        sale.confirm(reserved);

        assertEquals(List.of(shop), accounts.resellers(), "no counter in the points of sale");
        Statement shopNow = accounts.statements().getFirst();
        assertEquals(1, accounts.statements().size());
        assertEquals(1, shopNow.given());
        assertEquals(BigDecimal.ZERO, shopNow.paid(), "the direct payment is not the shop's");
        assertTrue(shopNow.events().stream().noneMatch(e -> e.kind().name().startsWith("DIRECT")));
        assertEquals(List.of(new Card(ROUTER, "old-1", "1M")), accounts.plan("shop", List.of(new Card(ROUTER,
                "old-1", "1M")), PRICES).sold(), "a card sold at the counter never moves to a point of sale");
    }

    @Test
    void aFailedPrintPutsTheCardBackAndChargesNothing() throws Exception {
        QuickSale.Reservation reserved = sale.reserve(new Card(ROUTER, pick("2M").name(), "2M"), PRICES)
                .orElseThrow();

        sale.cancel(reserved);
        sale.confirm(reserved); // too late: a released sale cannot be completed

        assertEquals("two", pick("2M").name(), "back in stock");
        assertEquals(0, sale.summary(TODAY).sold());
        assertEquals(List.of(Kind.DIRECT_RESERVE, Kind.DIRECT_RELEASE),
                repository.ledger().stream().map(LedgerEvent::kind).toList(), "the attempt stays in the history");
    }

    @Test
    void aSalePrintedBeforeACrashStaysPendingAndIsFinishedOnce() throws Exception {
        QuickSale.Reservation reserved = sale.reserve(new Card(ROUTER, pick("1M").name(), "1M"), PRICES)
                .orElseThrow();
        // the receipt printed, then NetPilot stopped before recording the payment

        QuickSale reopened = new QuickSale(new ResellerAccounts(repository, Clock.systemUTC()));
        List<QuickSale.Reservation> pending = reopened.pending();
        assertEquals(1, pending.size());
        assertEquals("old-1", pending.getFirst().card().name());
        assertNotEquals("old-1", pick("1M").name(), "a pending card is never sold again");

        reopened.confirm(pending.getFirst());
        reopened.confirm(pending.getFirst());
        sale.confirm(reserved); // the old window's late retry
        assertEquals(List.of(), reopened.pending());
        assertEquals(1, reopened.summary(TODAY).sold(), "one sale, one amount, whatever retried");
    }

    @Test
    void aPendingSaleWhoseCardIsUsedCompletesByItself() throws Exception {
        sale.reserve(new Card(ROUTER, "old-1", "1M"), PRICES).orElseThrow();

        accounts.recordSales(ROUTER, List.of(new UsageRecord(ROUTER, "old-1", "1M", OLD, Optional.of(TODAY))));
        accounts.recordSales(ROUTER, List.of(new UsageRecord(ROUTER, "old-1", "1M", OLD, Optional.of(TODAY))));

        assertEquals(List.of(), sale.pending(), "its first use proves the receipt was handed over");
        assertEquals(1, sale.summary(TODAY).sold());
        assertEquals(new BigDecimal("1000"), sale.summary(TODAY).total());
    }

    @Test
    void aPackageWithoutAPriceCannotBeSold() throws Exception {
        assertEquals(Optional.empty(), sale.reserve(new Card(ROUTER, "old-1", "1M"), Map.of()));
        assertTrue(repository.ledger().isEmpty());
    }
}
