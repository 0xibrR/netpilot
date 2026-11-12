package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.Branch;
import org.netpilot.model.Money;
import org.netpilot.model.ReportPeriod;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.model.RouterSettings;
import org.netpilot.model.UsageRecord;
import org.netpilot.model.VoucherTemplate;
import org.netpilot.repository.SettingsRepository;
import org.netpilot.repository.StockReadings;

class BranchesTest {

    private static final RouterEndpoint SHOP = new RouterEndpoint("10.0.0.1", 80, false, "netpilot");
    private static final RouterEndpoint TOWN = new RouterEndpoint("10.20.0.1", 443, true, "admin");
    private static final Branch SHOP_BRANCH = new Branch("a", "المحل", SHOP, "#1fa37a");
    private static final Branch TOWN_BRANCH = new Branch("b", "فرع البلد", TOWN, "#e08a00");
    private static final String BATCH = "NetPilot 2026-11-02 12:00 #bbbb";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    @TempDir
    Path dir;

    private static UsageRecord sold(RouterEndpoint router, String card, String profile, LocalDate day) {
        return new UsageRecord(router.address(), card, profile, BATCH, Optional.of(day));
    }

    private static VoucherTemplate prices() {
        return new VoucherTemplate("WiFi", "", null, null, Map.of("1M", "1000 ل.س", "3M", "2500 ل.س"), true, true,
                true, true, null);
    }

    @Test
    void branchesAreAddedReplacedRemovedAndFoundByAddress() {
        RouterSettings settings = RouterSettings.defaults().withBranch(SHOP_BRANCH).withBranch(TOWN_BRANCH);
        assertEquals(List.of("a", "b"), settings.branches().stream().map(Branch::id).toList());

        RouterSettings renamed = settings.withBranch(SHOP_BRANCH.withName("الدكان"));
        assertEquals(List.of("الدكان", "فرع البلد"), renamed.branches().stream().map(Branch::name).toList(),
                "renaming keeps the place in the list");
        assertEquals("b", renamed.branchAt(TOWN.address()).orElseThrow().id());
        assertTrue(renamed.withoutBranch("a").branchAt(SHOP.address()).isEmpty());
        assertEquals(SHOP.host(), new Branch("x", " ", SHOP, "not-a-colour").name(), "no name: the address");
        assertEquals(Branch.COLORS.getFirst(), new Branch("x", "n", SHOP, "red").color());
    }

    @Test
    void branchesAreSavedWithTheSettingsButNeverTheirPasswords() throws Exception {
        Path file = dir.resolve("settings.properties");
        SettingsRepository repository = new SettingsRepository(file);
        repository.save(repository.load()
                .withRouter(RouterSettings.defaults().withBranch(SHOP_BRANCH).withBranch(TOWN_BRANCH)));

        List<Branch> back = new SettingsRepository(file).load().router().branches();
        assertEquals(List.of(SHOP_BRANCH, TOWN_BRANCH), back);
        // the card password settings (passwordStyle…) are fine; no router or branch line holds a password
        assertTrue(java.nio.file.Files.readAllLines(file).stream()
                .filter(line -> line.startsWith("router."))
                .noneMatch(line -> line.toLowerCase(java.util.Locale.ROOT).contains("password")),
                "no router password in the settings file");
    }

    @Test
    void eachBranchesLastStockReadingIsKept() throws Exception {
        StockReadings readings = new StockReadings(dir.resolve("branch-stock.properties"));
        assertTrue(readings.all().isEmpty());
        LocalDateTime at = LocalDateTime.of(2026, 11, 20, 14, 20);

        readings.save(SHOP.address(), new StockReadings.Reading(at, 12, new Money(new BigDecimal("12000"), "ل.س")));
        readings.save(TOWN.address(), new StockReadings.Reading(at, 3, new Money(new BigDecimal("7500"), "ل.س")));
        readings.save(SHOP.address(), new StockReadings.Reading(at.plusHours(1), 10,
                new Money(new BigDecimal("10000"), "ل.س")));

        Map<String, StockReadings.Reading> all = new StockReadings(dir.resolve("branch-stock.properties")).all();
        assertEquals(10, all.get(SHOP.address()).unused(), "the newest reading replaces the old one");
        assertEquals(at.plusHours(1), all.get(SHOP.address()).at());
        assertEquals("7,500 ل.س", all.get(TOWN.address()).value().format());
    }

    @Test
    void theAllBranchesReportAddsUpEachBranchFromTheRecordsOnThisComputer() {
        RouterEndpoint old = new RouterEndpoint("10.99.0.1", 80, false, "x");
        List<UsageRecord> history = List.of(
                sold(SHOP, "s1", "1M", LocalDate.of(2026, 11, 3)),
                sold(SHOP, "s2", "3M", LocalDate.of(2026, 11, 3)),
                sold(TOWN, "t1", "3M", LocalDate.of(2026, 11, 5)),
                sold(TOWN, "t2", "3M", LocalDate.of(2026, 10, 5)), // last month: not in this one
                sold(old, "o1", "1M", LocalDate.of(2026, 11, 9)));
        Map<String, StockReadings.Reading> stock = Map.of(
                SHOP.address(), new StockReadings.Reading(LocalDateTime.of(2026, 11, 20, 9, 0), 5,
                        new Money(new BigDecimal("5000"), "ل.س")),
                TOWN.address(), new StockReadings.Reading(LocalDateTime.of(2026, 11, 18, 9, 0), 2,
                        new Money(new BigDecimal("5000"), "ل.س")));

        BranchReport report = BranchReport.build(new ReportService(), history, List.of(SHOP_BRANCH, TOWN_BRANCH),
                stock, prices(), ReportPeriod.THIS_MONTH, TODAY);

        assertEquals(List.of("المحل", "فرع البلد", old.address()),
                report.lines().stream().map(BranchReport.Line::name).toList(),
                "branches in their order, then a router with sales that is no branch");
        assertEquals(2, report.lines().get(0).sold());
        assertEquals("3,500 ل.س", report.lines().get(0).revenue().format());
        assertEquals(1, report.lines().get(1).sold());
        assertEquals(4, report.total().sold());
        assertEquals("7,000 ل.س", report.total().revenue().format(), "3,500 + 2,500 + the old router's 1,000");
        assertEquals(7, report.total().stock().orElseThrow().unused());
        assertEquals("10,000 ل.س", report.total().stock().orElseThrow().value().format());
        assertEquals(LocalDateTime.of(2026, 11, 18, 9, 0), report.total().stock().orElseThrow().at(),
                "the total's reading is as old as its oldest branch");
        assertEquals(List.of("3M", "1M"), report.profiles().stream().map(p -> p.profile()).toList());
        assertEquals(2, report.profiles().get(0).sold(), "3M sold in two branches");
        assertEquals(0, new BigDecimal("3500").compareTo(report.daily().get(LocalDate.of(2026, 11, 3)).amount()),
                "1M + 3M sold in the shop on 3 November");
        assertTrue(report.lines().get(2).stock().isEmpty(), "never read");
    }

    @Test
    void theMonthlyReportShowsBranchesOnlyWhenThereAreSeveral() throws Exception {
        List<UsageRecord> history = List.of(sold(SHOP, "s1", "1M", LocalDate.of(2026, 11, 3)),
                sold(TOWN, "t1", "3M", LocalDate.of(2026, 11, 5)));
        ResellerAccounts accounts = new ResellerAccounts(new org.netpilot.repository.ResellerRepository(
                dir.resolve("r.csv"), dir.resolve("l.csv")), java.time.Clock.systemDefaultZone());

        MonthlyReport two = MonthlyReport.build(YearMonth.of(2026, 11), SHOP.address(), "hEX", List.of(), history,
                prices(), accounts, EnumSet.allOf(MonthlyReport.Section.class), new ReportService(),
                TODAY.atTime(18, 0), List.of(SHOP_BRANCH, TOWN_BRANCH), Map.of());
        assertTrue(two.showsBranches());
        assertEquals(2, two.branches().total().sold());
        assertEquals(1, two.sales().sold(), "the rest of the report is the connected branch's");

        MonthlyReport one = MonthlyReport.build(YearMonth.of(2026, 11), SHOP.address(), "hEX", List.of(),
                List.of(history.getFirst()), prices(), accounts, EnumSet.allOf(MonthlyReport.Section.class),
                new ReportService(), TODAY.atTime(18, 0), List.of(SHOP_BRANCH), Map.of());
        assertFalse(one.showsBranches(), "a single branch has nothing to compare");

        Path pdf = dir.resolve("two.pdf");
        new MonthlyReportPdf(PdfText.instance(), true, "1.2.5").write(two, pdf);
        assertTrue(java.nio.file.Files.size(pdf) > 0);
    }
}
