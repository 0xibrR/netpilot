package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.ReportPeriod;
import org.netpilot.model.Reseller;
import org.netpilot.model.RouterUser;
import org.netpilot.model.SalesReport;
import org.netpilot.model.UsageRecord;
import org.netpilot.model.VoucherTemplate;
import org.netpilot.repository.ResellerRepository;
import org.netpilot.service.ResellerAccounts.Card;
import org.netpilot.service.ResellerAccounts.MonthStatement;
import org.openpdf.text.pdf.PdfReader;

class MonthlyReportTest {

    private static final String ROUTER = "10.0.0.1:80";
    private static final String BATCH_OCT = "NetPilot 2026-10-03 10:00 #aaaa";
    private static final String BATCH_NOV = "NetPilot 2026-11-02 12:00 #bbbb";
    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final YearMonth NOV = YearMonth.of(2026, 11);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 18, 0);
    private static final Map<String, String> PRICES = Map.of("1M", "1000 ل.س", "3M", "2500 ل.س");

    @TempDir
    Path dir;

    private ResellerRepository repository;
    private final AtomicInteger ids = new AtomicInteger();
    private Reseller ali;

    private ResellerAccounts accountsOn(String instant) {
        return new ResellerAccounts(repository, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC),
                () -> "id" + ids.incrementAndGet());
    }

    private ResellerAccounts accounts() {
        return accountsOn("2026-11-20T18:00:00Z");
    }

    private static UsageRecord sold(String card, String profile, String batch, LocalDate day) {
        return new UsageRecord(ROUTER, card, profile, batch, Optional.ofNullable(day));
    }

    private static RouterUser onRouter(String name, String profile, boolean used, String batch) {
        return new RouterUser("*" + Integer.toHexString(Math.abs(name.hashCode() % 4096)), name, profile, "all",
                used ? Duration.ofMinutes(30) : Duration.ZERO, "", used ? 1000 : 0, used ? 9000 : 0, false, batch);
    }

    private static VoucherTemplate template() {
        return new VoucherTemplate("شبكة الأمل", "", null, null, PRICES, true, true, true, true, null);
    }

    @BeforeEach
    void setUp() throws Exception {
        repository = new ResellerRepository(dir.resolve("resellers.csv"), dir.resolve("reseller-ledger.csv"));
        ali = accounts().save(new Reseller("ali", "محل علي", "", Reseller.CommissionType.PERCENT, BigDecimal.TEN,
                Reseller.Billing.ON_SALE));
    }

    /** Ali gets three cards in October, sells one in October and one in November, and pays twice. */
    private void aliOverTwoMonths() throws Exception {
        Map<String, String> prices = Map.of("1M", "1000 ل.س");
        accountsOn("2026-10-05T10:00:00Z").handOver(ali,
                List.of(new Card(ROUTER, "a", "1M"), new Card(ROUTER, "b", "1M"), new Card(ROUTER, "c", "1M")),
                prices, false);
        accounts().recordSales(ROUTER, List.of(sold("a", "1M", BATCH_OCT, LocalDate.of(2026, 10, 20))));
        accounts().pay("p1", ali, new BigDecimal("500"), "ل.س", LocalDate.of(2026, 10, 25), "");
        accounts().recordSales(ROUTER, List.of(sold("b", "1M", BATCH_OCT, LocalDate.of(2026, 11, 12))));
        accounts().pay("p2", ali, new BigDecimal("300"), "ل.س", LocalDate.of(2026, 11, 14), "");
        accounts().pay("p3", ali, new BigDecimal("9999"), "ل.س", LocalDate.of(2026, 10, 26), "typo");
        accounts().voidPayment("p3", "entered by mistake");
    }

    @Test
    void aPointOfSalesMonthCountsOnlyThatMonthAndItsBalanceAtTheEnd() throws Exception {
        aliOverTwoMonths();

        MonthStatement october = accounts().monthStatements(OCT).getFirst();
        assertEquals(3, october.month().given());
        assertEquals(1, october.month().sold());
        assertEquals(0, new BigDecimal("1000").compareTo(october.month().salesValue()));
        assertEquals(0, new BigDecimal("100").compareTo(october.month().commission()), "10% frozen at handover");
        assertEquals(0, new BigDecimal("500").compareTo(october.month().paid()), "the voided payment never counts");
        assertEquals(0, new BigDecimal("400").compareTo(october.atEnd().due()), "900 charged - 500 paid");

        MonthStatement november = accounts().monthStatements(NOV).getFirst();
        assertEquals(0, november.month().given(), "the handover was in October");
        assertEquals(1, november.month().sold());
        assertEquals(0, new BigDecimal("300").compareTo(november.month().paid()));
        assertEquals(0, new BigDecimal("1000").compareTo(november.atEnd().due()), "1800 charged - 800 paid");

        // the months add up to the account as the Points of sale page shows it
        var whole = accounts().statements().getFirst();
        assertEquals(0, whole.owes().compareTo(november.atEnd().owes()));
        assertEquals(whole.sold(), october.month().sold() + november.month().sold());
        assertTrue(accounts().monthStatements(YearMonth.of(2026, 9)).isEmpty(), "nothing yet in September");
        assertEquals(Set.of(OCT, NOV), accounts().ledgerMonths());
    }

    @Test
    void directSalesAreCountedPerMonth() throws Exception {
        ResellerAccounts october = accountsOn("2026-10-10T10:00:00Z");
        var reserve = october.reserveDirect(new Card(ROUTER, "x", "1M"),
                new org.netpilot.model.Money(new BigDecimal("1000"), "ل.س")).orElseThrow();
        october.completeDirect(reserve, "");

        assertEquals(1, accounts().directMonth(OCT).sold());
        assertEquals(0, new BigDecimal("1000").compareTo(accounts().directMonth(OCT).total()));
        assertEquals(0, accounts().directMonth(NOV).sold());
    }

    private MonthlyReport build(YearMonth month, Set<MonthlyReport.Section> sections) throws Exception {
        List<UsageRecord> history = List.of(
                sold("a", "1M", BATCH_OCT, LocalDate.of(2026, 10, 20)),
                sold("o2", "3M", BATCH_OCT, LocalDate.of(2026, 10, 21)),
                sold("b", "1M", BATCH_OCT, LocalDate.of(2026, 11, 12)),
                sold("n1", "3M", BATCH_NOV, LocalDate.of(2026, 11, 3)),
                sold("n2", "3M", BATCH_NOV, LocalDate.of(2026, 11, 18)));
        List<RouterUser> users = List.of(onRouter("c", "1M", false, BATCH_OCT), onRouter("n1", "3M", true, BATCH_NOV),
                onRouter("n2", "3M", true, BATCH_NOV), onRouter("n3", "3M", false, BATCH_NOV));
        return MonthlyReport.build(month, ROUTER, "hEX", users, history, template(), accounts(), sections,
                new ReportService(), NOW);
    }

    @Test
    void theReportUsesTheSameFiguresAsTheEarningsPage() throws Exception {
        aliOverTwoMonths();
        MonthlyReport report = build(OCT, EnumSet.allOf(MonthlyReport.Section.class));

        // the page, set to "Last month" on 20 November, shows October
        List<UsageRecord> history = List.of(sold("a", "1M", BATCH_OCT, LocalDate.of(2026, 10, 20)),
                sold("o2", "3M", BATCH_OCT, LocalDate.of(2026, 10, 21)),
                sold("b", "1M", BATCH_OCT, LocalDate.of(2026, 11, 12)),
                sold("n1", "3M", BATCH_NOV, LocalDate.of(2026, 11, 3)),
                sold("n2", "3M", BATCH_NOV, LocalDate.of(2026, 11, 18)));
        List<RouterUser> users = List.of(onRouter("c", "1M", false, BATCH_OCT), onRouter("n1", "3M", true, BATCH_NOV),
                onRouter("n2", "3M", true, BATCH_NOV), onRouter("n3", "3M", false, BATCH_NOV));
        SalesReport page = new ReportService().build(ROUTER, users, history, template(), ReportPeriod.LAST_MONTH,
                NOW.toLocalDate());

        assertEquals(page.revenue(), report.sales().revenue());
        assertEquals(page.sold(), report.sales().sold());
        assertEquals(page.created(), report.sales().created());
        assertEquals(page.dailyRevenue(), report.sales().dailyRevenue());
        assertEquals(2, report.sales().sold());
        assertEquals("شبكة الأمل", report.network());
        assertEquals(1, report.resellers().size());
        assertFalse(report.empty());

        MonthlyReport november = build(NOV, EnumSet.allOf(MonthlyReport.Section.class));
        assertEquals(3, november.sales().sold(), "so far this month");
        assertEquals(report.sales().sold(), november.previous().sold(), "compared with October");
        assertThrows(IllegalArgumentException.class, () -> build(YearMonth.of(2026, 12), Set.of()));
        assertTrue(build(YearMonth.of(2026, 8), Set.of()).empty(), "a month without anything");
    }

    @Test
    void changesAreRoundedPercentagesAndNothingComparesWithZero() {
        assertEquals(Optional.of(50), MonthlyReport.change(new BigDecimal("1500"), new BigDecimal("1000")));
        assertEquals(Optional.of(-25), MonthlyReport.change(3, 4));
        assertEquals(Optional.of(0), MonthlyReport.change(7, 7));
        assertEquals(Optional.empty(), MonthlyReport.change(5, 0), "from nothing: no percentage");
        assertEquals(Optional.empty(), MonthlyReport.change(null, BigDecimal.ONE));
    }

    @Test
    void theMonthsOfferedAreThoseWithRecordsNewestFirst() {
        List<UsageRecord> history = List.of(sold("a", "1M", BATCH_OCT, LocalDate.of(2026, 9, 30)),
                new UsageRecord("other-router", "z", "1M", BATCH_OCT, Optional.of(LocalDate.of(2025, 1, 1))));
        List<YearMonth> months = MonthlyReport.months(ROUTER, history, List.of(onRouter("n", "1M", false, BATCH_NOV)),
                List.of(YearMonth.of(2026, 7), YearMonth.of(2027, 1)), NOW.toLocalDate());

        assertEquals(List.of(NOV, OCT, YearMonth.of(2026, 9), YearMonth.of(2026, 7)), months,
                "current month first; other routers and future months left out");
    }

    @Test
    void writesACompletePdfInArabicAndInEnglish() throws Exception {
        aliOverTwoMonths();
        MonthlyReport report = build(NOV, EnumSet.allOf(MonthlyReport.Section.class));

        for (boolean rtl : new boolean[]{true, false}) {
            Path file = dir.resolve("report-" + rtl + ".pdf");
            new MonthlyReportPdf(PdfText.instance(), rtl, "1.1.0").write(report, file);

            byte[] bytes = Files.readAllBytes(file);
            assertEquals("%PDF", new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
            PdfReader reader = new PdfReader(bytes);
            try {
                assertTrue(reader.getNumberOfPages() >= 1);
            } finally {
                reader.close();
            }
        }
        try (var left = Files.list(dir)) {
            assertTrue(left.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "no half-written file");
        }

        Path summaryOnly = dir.resolve("summary.pdf");
        new MonthlyReportPdf(PdfText.instance(), true, "1.1.0")
                .write(build(OCT, EnumSet.of(MonthlyReport.Section.SUMMARY)), summaryOnly);
        assertTrue(Files.size(summaryOnly) > 0);
    }
}
