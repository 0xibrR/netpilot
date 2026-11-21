package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.LedgerEvent;
import org.netpilot.model.ReportPeriod;
import org.netpilot.model.Reseller;
import org.netpilot.model.RouterUser;
import org.netpilot.model.UsageRecord;
import org.netpilot.model.UserFilter;
import org.netpilot.model.VoucherTemplate;
import org.netpilot.repository.ResellerRepository;

/**
 * A busy shop, years in: 10,000 cards on the router and 50,000 lines in the ledger. What the pages
 * do every few seconds must stay quick. The limits are generous (a slow build machine passes);
 * they catch work that grows out of hand, not milliseconds.
 */
class LargeDataPerformanceTest {

    private static final int CARDS = 10_000;
    private static final int LEDGER_LINES = 50_000;
    private static final String ROUTER = "10.0.0.1:80";
    private static final Map<String, String> PRICES = Map.of("1M", "1 ₪", "2M", "1.5 ₪", "3M", "3 ₪", "5M", "4 ₪",
            "1W", "15 ₪");

    @TempDir
    Path dir;

    private static List<RouterUser> cards() {
        List<String> profiles = List.copyOf(PRICES.keySet());
        List<RouterUser> users = new ArrayList<>();
        for (int i = 0; i < CARDS; i++) {
            String batch = String.format(java.util.Locale.ROOT, "NetPilot 2026-%02d-%02d 10:00 #%04x", 1 + i % 11,
                    1 + i % 27, i / 200);
            boolean used = i % 5 < 2;
            users.add(new RouterUser("*" + Integer.toHexString(i + 1), "c" + (100_000 + i), profiles.get(i % 5), "all",
                    used ? Duration.ofMinutes(30 + i % 300) : Duration.ZERO, "8h", used ? 1_000_000L * (i % 90) : 0,
                    used ? 5_000_000L * (i % 90) : 0, i % 97 == 0, batch, 0));
        }
        return users;
    }

    private static long millis(Runnable work) {
        long start = System.nanoTime();
        work.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    void theLedgerIsParsedOnceAndThePagesFiguresStayQuick() throws Exception {
        ResellerRepository repository = new ResellerRepository(dir.resolve("r.csv"), dir.resolve("l.csv"));
        Clock clock = Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC);
        ResellerAccounts accounts = new ResellerAccounts(repository, clock);
        List<Reseller> shops = new ArrayList<>();
        for (int s = 0; s < 40; s++) {
            shops.add(accounts.save(new Reseller("shop" + s, "محل " + s, "0599" + s, Reseller.CommissionType.PERCENT,
                    BigDecimal.TEN, s % 2 == 0 ? Reseller.Billing.ON_SALE : Reseller.Billing.ON_HANDOVER)));
        }
        List<LedgerEvent> events = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2025, 1, 1, 9, 0);
        for (int i = 0; i < LEDGER_LINES; i++) {
            Reseller shop = shops.get(i % shops.size());
            LedgerEvent.Kind kind = i % 10 == 9
                    ? LedgerEvent.Kind.PAYMENT
                    : i % 10 < 5
                            ? LedgerEvent.Kind.HANDOVER
                            : i % 10 < 8
                                    ? LedgerEvent.Kind.SALE
                                    : LedgerEvent.Kind.DIRECT_SALE;
            boolean money = kind == LedgerEvent.Kind.PAYMENT || kind == LedgerEvent.Kind.DIRECT_SALE;
            events.add(new LedgerEvent("e" + i, kind, t.plusMinutes(i * 15L),
                    kind == LedgerEvent.Kind.DIRECT_SALE ? ResellerAccounts.COUNTER : shop.id(), ROUTER,
                    money && kind == LedgerEvent.Kind.PAYMENT ? "" : "c" + (100_000 + i % CARDS), "1M",
                    new BigDecimal("1"), new BigDecimal("0.1"), shop.billing(),
                    money ? new BigDecimal("12") : BigDecimal.ZERO, "₪", "", ""));
        }
        repository.append(events);

        ResellerRepository fresh = new ResellerRepository(dir.resolve("r.csv"), dir.resolve("l.csv"));
        long first = millis(() -> {
            try {
                assertEquals(LEDGER_LINES, fresh.ledger().size());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        List<LedgerEvent> once = fresh.ledger();
        long again = millis(() -> {
            try {
                assertSame(once, fresh.ledger(), "the file did not change: the same list, not parsed again");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        assertTrue(first < 5_000, "first read of 50,000 lines took " + first + " ms");
        assertTrue(again < 50, "a repeated read took " + again + " ms");

        fresh.append(List.of(new LedgerEvent("new", LedgerEvent.Kind.PAYMENT, t, shops.getFirst().id(), "", "", "",
                BigDecimal.ZERO, BigDecimal.ZERO, Reseller.Billing.ON_SALE, BigDecimal.ONE, "₪", "", "")));
        assertEquals(LEDGER_LINES + 1, fresh.ledger().size(), "an append is seen at once");
        assertEquals(LEDGER_LINES + 1, new ResellerRepository(dir.resolve("r.csv"), dir.resolve("l.csv")).ledger()
                .size(), "and is on disk");

        ResellerAccounts big = new ResellerAccounts(fresh, clock);
        big.ledger();
        long statements = millis(() -> {
            try {
                assertEquals(40, big.statements().size());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        long holders = millis(() -> {
            try {
                big.holders();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        long direct = millis(() -> {
            try {
                big.directSummary(LocalDate.of(2026, 11, 21));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        assertTrue(statements < 3_000, "points of sale statements took " + statements + " ms");
        assertTrue(holders < 2_000, "who holds which card took " + holders + " ms");
        assertTrue(direct < 2_000, "the counter summary took " + direct + " ms");

        ShiftBook shifts = new ShiftBook(dir.resolve("shifts.csv"), big, clock);
        shifts.open("CASHIER", BigDecimal.TEN, "₪");
        long shift = millis(() -> {
            try {
                shifts.figures(shifts.current().orElseThrow());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        assertTrue(shift < 1_000, "the open shift's expected cash took " + shift + " ms");
    }

    @Test
    void tenThousandCardsFilterReportAndExportQuickly() throws Exception {
        List<RouterUser> users = cards();
        Set<String> online = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            online.add("c" + (100_000 + i * 7));
        }

        long filter = millis(() -> {
            UserFilter text = new UserFilter("c1099", "", "", UserFilter.Status.ALL);
            UserFilter unused = new UserFilter("", "", "2M", UserFilter.Status.values()[1]);
            for (int round = 0; round < 20; round++) { // typing a search, letter by letter
                users.stream().filter(u -> text.matches(u, online)).count();
                users.stream().filter(u -> unused.matches(u, online)).count();
            }
        });
        assertTrue(filter < 3_000, "20 rounds of filtering 10,000 cards took " + filter + " ms");

        List<UsageRecord> history = new ArrayList<>();
        for (RouterUser u : users) {
            if (!u.neverUsed()) {
                history.add(new UsageRecord(ROUTER, u.name(), u.profile(), u.comment(),
                        Optional.of(LocalDate.of(2026, 11, 1 + Math.floorMod(u.name().hashCode(), 20)))));
            }
        }
        VoucherTemplate template = new VoucherTemplate("WiFi", "", VoucherTemplate.Language.ARABIC, "#1F5FA8", PRICES,
                true, true, true, true, null);
        long report = millis(() -> new ReportService().build(ROUTER, users, history, template,
                ReportPeriod.THIS_MONTH, LocalDate.of(2026, 11, 21)));
        assertTrue(report < 3_000, "the earnings report of 10,000 cards took " + report + " ms");

        long stock = millis(() -> QuickSale.stock(ROUTER, users, online, Map.of()));
        assertTrue(stock < 1_000, "quick sale stock took " + stock + " ms");

        Path xlsx = dir.resolve("cards.xlsx");
        long export = millis(() -> {
            try {
                CardListExport.write(ROUTER, users, online, Map.of(), Map.of(), xlsx);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        assertTrue(export < 10_000, "exporting 10,000 cards to Excel took " + export + " ms");
        assertTrue(java.nio.file.Files.size(xlsx) > 0);
    }
}
