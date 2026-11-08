package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.LedgerEvent;
import org.netpilot.model.LedgerEvent.Kind;
import org.netpilot.model.Reseller;
import org.netpilot.model.UsageRecord;
import org.netpilot.repository.ResellerRepository;
import org.netpilot.service.ResellerAccounts.Card;
import org.netpilot.service.ResellerAccounts.Statement;

class ResellerAccountsTest {

    private static final String ROUTER = "10.0.0.1:80";
    private static final String BATCH = "NetPilot 2026-11-08 15:59 #13eb";
    private static final Clock NOV_10 = Clock.fixed(Instant.parse("2026-11-10T09:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private ResellerRepository repository;
    private ResellerAccounts accounts;
    private Reseller ali;
    private Reseller sami;

    @BeforeEach
    void setUp() throws Exception {
        repository = new ResellerRepository(dir.resolve("resellers.csv"), dir.resolve("reseller-ledger.csv"));
        AtomicInteger next = new AtomicInteger();
        accounts = new ResellerAccounts(repository, NOV_10, () -> "id" + next.incrementAndGet());
        ali = accounts.save(new Reseller("ali", "محل علي", "0944", Reseller.CommissionType.PERCENT,
                BigDecimal.TEN, Reseller.Billing.ON_SALE));
        sami = accounts.save(new Reseller("sami", "Sami", "", Reseller.CommissionType.PER_CARD,
                BigDecimal.valueOf(100), Reseller.Billing.ON_HANDOVER));
    }

    private static Card card(String name, String profile) {
        return new Card(ROUTER, name, profile);
    }

    private static UsageRecord used(String card, LocalDate day) {
        return new UsageRecord(ROUTER, card, "1M", BATCH, Optional.ofNullable(day));
    }

    private Statement statementOf(Reseller r) throws Exception {
        return accounts.statements().stream().filter(s -> s.reseller().id().equals(r.id())).findFirst()
                .orElseThrow();
    }

    @Test
    void handoverSaleAndPaymentAreSeparateFactsAndPartialPaymentsAddUp() throws Exception {
        Map<String, String> prices = Map.of("1M", "1000 ل.س");
        accounts.handOver(ali, List.of(card("a", "1M"), card("b", "1M"), card("c", "1M")), prices, false);

        Statement handedOver = statementOf(ali);
        assertEquals(3, handedOver.given());
        assertEquals(0, handedOver.sold());
        assertEquals(0, handedOver.owes().signum(), "on-sale billing: nothing owed for a handover alone");

        accounts.recordSales(ROUTER, List.of(used("a", LocalDate.of(2026, 11, 12)), used("b", null)));
        Statement afterSales = statementOf(ali);
        assertEquals(2, afterSales.sold());
        assertEquals(1, afterSales.left());
        assertEquals(new BigDecimal("2000"), afterSales.salesValue());
        assertEquals(new BigDecimal("200"), afterSales.commission(), "10% of each sold card");
        assertEquals(new BigDecimal("1800"), afterSales.owes());

        accounts.pay("p1", ali, new BigDecimal("500"), "ل.س", LocalDate.of(2026, 11, 10), "first part");
        accounts.pay("p2", ali, new BigDecimal("300"), "ل.س", LocalDate.of(2026, 11, 10), "");
        Statement afterPayments = statementOf(ali);
        assertEquals(new BigDecimal("800"), afterPayments.paid());
        assertEquals(new BigDecimal("1000"), afterPayments.owes(), "1800 - 500 - 300");
        assertEquals("1,000 ل.س", afterPayments.format(afterPayments.owes()));

        List<Kind> kinds = afterPayments.events().stream().map(LedgerEvent::kind).toList();
        assertEquals(List.of(Kind.HANDOVER, Kind.HANDOVER, Kind.HANDOVER, Kind.SALE, Kind.SALE, Kind.PAYMENT,
                Kind.PAYMENT), kinds);
        assertEquals(LocalDate.of(2026, 11, 12), afterPayments.events().get(3).at().toLocalDate(),
                "a sale is dated by the card's first use");
    }

    @Test
    void aSaleIsNeverDatedBeforeItsHandover() throws Exception {
        accounts.handOver(ali, List.of(card("a", "1M")), Map.of("1M", "1000"), false);
        accounts.recordSales(ROUTER, List.of(used("a", LocalDate.of(2026, 11, 1))));

        assertEquals(LocalDate.of(2026, 11, 10), statementOf(ali).events().get(1).at().toLocalDate(),
                "a first-use day before the handover (wrong clock) becomes the day it was seen");
    }

    @Test
    void payOnHandoverOwesForEveryCardReceivedAndCountsCommissionOnce() throws Exception {
        Reseller onReceipt = accounts.save(new Reseller("mohammad", "محمد", "", Reseller.CommissionType.PER_CARD,
                new BigDecimal("0.5"), Reseller.Billing.ON_HANDOVER));
        List<Card> five = List.of(card("c1", "1M"), card("c2", "1M"), card("c3", "1M"), card("c4", "1M"),
                card("c5", "1M"));
        accounts.handOver(onReceipt, five, Map.of("1M", "1"), false);

        Statement received = statementOf(onReceipt);
        assertEquals(5, received.given());
        assertEquals(0, received.sold());
        assertEquals(new BigDecimal("5"), received.chargedValue().stripTrailingZeros(),
                "all 5 received cards at 1 each");
        assertEquals(new BigDecimal("2.5"), received.commission(), "5 × 0.5");
        assertEquals(new BigDecimal("2.5"), received.due(), "5 - 2.5, before any payment");
        assertEquals(0, received.salesValue().signum(), "nothing used yet");

        accounts.pay("p1", onReceipt, new BigDecimal("1000"), "", LocalDate.of(2026, 11, 10), "");
        Statement paid = statementOf(onReceipt);
        assertEquals(BigDecimal.ZERO, paid.due(), "never a negative debt");
        assertEquals(new BigDecimal("997.5"), paid.credit(), "1000 - 2.5 is its advance credit");

        accounts.recordSales(ROUTER, List.of(used("c1", LocalDate.of(2026, 11, 12))));
        Statement oneSold = statementOf(onReceipt);
        assertEquals(1, oneSold.sold());
        assertEquals(new BigDecimal("2.5"), oneSold.commission(), "selling a card it paid for adds no commission");
        assertEquals(new BigDecimal("5"), oneSold.chargedValue().stripTrailingZeros(),
                "nor any value: it was charged at the handover");
        assertEquals(new BigDecimal("997.5"), oneSold.credit());
        assertEquals(BigDecimal.ONE, oneSold.salesValue(), "the sale still shows as a sale");
    }

    @Test
    void payingMoreThanOwedIsAnAdvanceCreditNotANegativeDebt() throws Exception {
        accounts.handOver(ali, List.of(card("a", "1M"), card("b", "1M")), Map.of("1M", "1000 ل.س"), false);
        accounts.pay("p1", ali, new BigDecimal("500"), "ل.س", LocalDate.of(2026, 11, 10), "in advance");

        Statement advance = statementOf(ali);
        assertEquals(BigDecimal.ZERO, advance.due(), "nothing is owed before any sale");
        assertEquals(new BigDecimal("500"), advance.paid());
        assertEquals(new BigDecimal("500"), advance.credit(), "the payment is the shop's credit");

        accounts.recordSales(ROUTER, List.of(used("a", LocalDate.of(2026, 11, 12))));
        Statement oneSale = statementOf(ali);
        assertEquals(new BigDecimal("400"), oneSale.due(), "900 for the sale minus the 500 credit");
        assertEquals(BigDecimal.ZERO, oneSale.credit(), "the credit is used up by the sale");
    }

    @Test
    void salesAndPaymentsAreNeverRecordedTwice() throws Exception {
        accounts.handOver(ali, List.of(card("a", "1M")), Map.of("1M", "1000"), false);
        List<UsageRecord> usage = List.of(used("a", LocalDate.of(2026, 11, 9)));

        assertEquals(1, accounts.recordSales(ROUTER, usage).size());
        assertEquals(0, accounts.recordSales(ROUTER, usage).size(), "the dashboard refreshes every few seconds");
        assertTrue(accounts.pay("p1", ali, BigDecimal.TEN, "", LocalDate.of(2026, 11, 10), "").isPresent());
        assertTrue(accounts.pay("p1", ali, BigDecimal.TEN, "", LocalDate.of(2026, 11, 10), "").isEmpty(),
                "Save pressed twice in the same payment window");
        assertEquals(0, accounts.handOver(ali, List.of(card("a", "1M")), Map.of("1M", "1000"), false).size(),
                "a card it already has is not handed over again");

        Statement s = statementOf(ali);
        assertEquals(1, s.sold());
        assertEquals(BigDecimal.TEN, s.paid());
        assertThrows(IllegalArgumentException.class,
                () -> accounts.pay("p2", ali, BigDecimal.ZERO, "", LocalDate.of(2026, 11, 10), ""));
    }

    @Test
    void laterPriceAndCommissionChangesNeverChangePastFigures() throws Exception {
        accounts.handOver(ali, List.of(card("a", "1M")), Map.of("1M", "1000"), false);
        // the price and the commission change afterwards
        Reseller richer = accounts.save(new Reseller("ali", "محل علي", "0944", Reseller.CommissionType.PERCENT,
                BigDecimal.valueOf(50), Reseller.Billing.ON_SALE));
        accounts.handOver(richer, List.of(card("b", "1M")), Map.of("1M", "2000"), false);
        accounts.recordSales(ROUTER, List.of(used("a", LocalDate.of(2026, 11, 9)), used("b", LocalDate.of(2026, 11,
                10))));

        Statement s = statementOf(richer);
        assertEquals(new BigDecimal("3000"), s.salesValue(), "1000 (old price) + 2000 (new price)");
        assertEquals(new BigDecimal("1100"), s.commission(), "10% of 1000 + 50% of 2000");
        assertEquals(new BigDecimal("1900"), s.owes());
    }

    @Test
    void transfersMoveOnlyUnsoldCardsAndOnlyWhenAsked() throws Exception {
        Map<String, String> prices = Map.of("1M", "1000");
        accounts.handOver(sami, List.of(card("a", "1M"), card("b", "1M")), prices, false);
        assertEquals(new BigDecimal("1800"), statementOf(sami).owes(), "on-handover billing: 2 × (1000 - 100)");
        accounts.recordSales(ROUTER, List.of(used("a", LocalDate.of(2026, 11, 10))));

        ResellerAccounts.Plan plan = accounts.plan("ali", List.of(card("a", "1M"), card("b", "1M"),
                card("c", "1M"), card("d", "5M")), prices);
        assertEquals(List.of(card("c", "1M")), plan.fresh());
        assertEquals(List.of(card("b", "1M")), plan.transfers());
        assertEquals(List.of(card("a", "1M")), plan.sold(), "a sold card never moves");
        assertEquals(List.of(card("d", "5M")), plan.noPrice(), "no price: it cannot be valued");

        accounts.handOver(ali, List.of(card("b", "1M"), card("c", "1M")), prices, false);
        assertEquals(1, statementOf(ali).given(), "b stays with Sami without the user's consent");

        accounts.handOver(ali, List.of(card("b", "1M")), prices, true);
        Statement samiNow = statementOf(sami);
        Statement aliNow = statementOf(ali);
        assertEquals(1, samiNow.given());
        assertEquals(new BigDecimal("900"), samiNow.owes(), "the moved card is no longer charged to Sami");
        assertEquals(2, aliNow.given());
        assertTrue(samiNow.events().stream().anyMatch(e -> e.kind() == Kind.RETURN && e.card().equals("b")),
                "the transfer stays in Sami's history");
    }

    @Test
    void recordsSurviveCardsBeingDeletedAndAMistakenPaymentIsVoidedNotErased() throws Exception {
        accounts.handOver(ali, List.of(card("a", "1M")), Map.of("1M", "1000"), false);
        accounts.recordSales(ROUTER, List.of(used("a", LocalDate.of(2026, 11, 9))));
        accounts.pay("p1", ali, new BigDecimal("900"), "", LocalDate.of(2026, 11, 10), "wrong amount");

        // the router deleted the card long ago; the ledger alone still holds everything
        ResellerAccounts reopened = new ResellerAccounts(repository, NOV_10);
        assertTrue(reopened.voidPayment("p1", "typo").isPresent());
        assertTrue(reopened.voidPayment("p1", "again").isEmpty(), "a payment is cancelled once");
        assertTrue(reopened.voidPayment("nope", "").isEmpty());

        Statement s = reopened.statements().stream().filter(x -> x.reseller().id().equals("ali")).findFirst()
                .orElseThrow();
        assertEquals(1, s.sold());
        assertEquals(BigDecimal.ZERO, s.paid());
        assertEquals(new BigDecimal("900"), s.owes());
        assertEquals(List.of(Kind.HANDOVER, Kind.SALE, Kind.PAYMENT, Kind.PAYMENT_VOID),
                s.events().stream().map(LedgerEvent::kind).toList(), "nothing is ever removed");

        String file = Files.readString(dir.resolve("reseller-ledger.csv"));
        assertEquals(5, file.lines().count(), "header + 4 events, written one after the other");
    }

    @Test
    void returnedCardsAreFreeAgainAndUnreadableLinesAreSkipped() throws Exception {
        accounts.handOver(ali, List.of(card("a", "1M")), Map.of("1M", "1000"), false);
        accounts.returnCards(List.of(card("a", "1M")), "came back");
        assertEquals(0, statementOf(ali).given());
        assertEquals(List.of(card("a", "1M")), accounts.plan("sami", List.of(card("a", "1M")),
                Map.of("1M", "1000")).fresh());
        assertEquals(Map.of(), accounts.holders());

        Files.writeString(dir.resolve("reseller-ledger.csv"), "broken,line\n", java.nio.file.StandardOpenOption.APPEND);
        assertEquals(2, repository.ledger().size(), "the bad line is skipped, the rest is read");
        assertThrows(IllegalArgumentException.class, () -> accounts.save(new Reseller("x", "", "",
                Reseller.CommissionType.PERCENT, BigDecimal.valueOf(120), null)));
    }
}
