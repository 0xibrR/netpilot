package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.ReportPeriod;
import org.netpilot.model.RouterUser;
import org.netpilot.model.SalesReport;
import org.netpilot.model.UsageRecord;
import org.netpilot.model.VoucherTemplate;
import org.netpilot.repository.UsageRepository;

class ReportServiceTest {

    private static final String ROUTER = "10.0.0.1:80";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);
    private static final String BATCH_NOV6 = "NetPilot 2026-11-06 21:56 #b062";
    private static final String BATCH_OCT = "NetPilot 2026-10-20 10:00 #aaaa";

    private final ReportService service = new ReportService();

    @TempDir
    Path dir;

    private static RouterUser card(String name, String profile, boolean used, String comment) {
        return new RouterUser("*" + Integer.toHexString(Math.abs(name.hashCode() % 4096)), name, profile, "all",
                used ? Duration.ofMinutes(30) : Duration.ZERO, "8h", used ? 1000 : 0, used ? 5000 : 0, false, comment);
    }

    private static VoucherTemplate prices(Map<String, String> prices) {
        return new VoucherTemplate("WiFi", "", null, null, prices, true, true, true, true, null);
    }

    @Test
    void firstTrackingDoesNotDateOldUsageAsToday() {
        List<RouterUser> users = List.of(card("a", "1M", true, BATCH_OCT), card("b", "1M", false, BATCH_OCT));

        List<UsageRecord> history = service.track(ROUTER, users, List.of(), TODAY);

        assertEquals(1, history.size());
        assertEquals(Optional.empty(), history.getFirst().firstUsed(), "used before tracking: day unknown");
    }

    @Test
    void laterTrackingDatesNewlyUsedCardsAndKeepsOldOnes() {
        List<UsageRecord> history = service.track(ROUTER, List.of(card("a", "1M", true, BATCH_OCT)), List.of(),
                TODAY.minusDays(1));

        List<RouterUser> now = List.of(card("a", "1M", true, BATCH_OCT), card("b", "2M", true, BATCH_NOV6),
                new RouterUser("*0", "default-trial", "", "all", Duration.ofHours(1), "", 9, 9, false, ""));
        history = service.track(ROUTER, now, history, TODAY);

        assertEquals(2, history.size(), "built-in trial user is never a sale");
        assertEquals(Optional.of(TODAY), history.get(1).firstUsed());
        assertEquals(BATCH_NOV6, history.get(1).batch());
        assertEquals(2, service.track(ROUTER, now, history, TODAY).size(), "no duplicates on repeat");
    }

    @Test
    void revenueCountsSoldCardsByFirstUseDayAndIncludesDeletedCards() {
        List<UsageRecord> history = List.of(
                new UsageRecord(ROUTER, "a", "1M", BATCH_NOV6, Optional.of(TODAY)),
                new UsageRecord(ROUTER, "b", "1M", BATCH_NOV6, Optional.of(TODAY.minusDays(2))),
                new UsageRecord(ROUTER, "gone", "2M", BATCH_NOV6, Optional.of(TODAY)), // deleted from router
                new UsageRecord(ROUTER, "old", "2M", BATCH_OCT, Optional.of(LocalDate.of(2026, 10, 25))),
                new UsageRecord("other-router:80", "x", "2M", "", Optional.of(TODAY)));
        List<RouterUser> users = List.of(card("a", "1M", true, BATCH_NOV6), card("b", "1M", true, BATCH_NOV6),
                card("new1", "1M", false, BATCH_NOV6), card("new2", "2M", false, BATCH_NOV6),
                card("old", "2M", true, BATCH_OCT));
        VoucherTemplate template = prices(Map.of("1M", "500 ل.س", "2M", "1000 ل.س"));

        SalesReport week = service.build(ROUTER, users, history, template, ReportPeriod.LAST_7_DAYS, TODAY);

        assertEquals(3, week.sold(), "a, b and the deleted card; other routers are ignored");
        assertEquals("2,000 ل.س", week.revenue().format());
        assertEquals("1,500 ل.س", week.potential().format(), "new1 (500) + new2 (1000)");
        assertEquals(2, week.unused());
        assertEquals("1,500 ل.س", week.dailyRevenue().get(TODAY).format());
        assertEquals("500 ل.س", week.dailyRevenue().get(TODAY.minusDays(2)).format());

        assertEquals(1, week.batches().size(), "only the batch created this week");
        SalesReport.BatchRow nov6 = week.batches().getFirst();
        assertEquals(BATCH_NOV6, nov6.batch());
        assertEquals(5, nov6.cards(), "a, b, new1, new2 and the deleted card");
        assertEquals(3, nov6.used());
        assertEquals(2, nov6.unused());
        assertEquals("mixed", nov6.profile());
        assertEquals(5, week.created());

        SalesReport all = service.build(ROUTER, users, history, template, ReportPeriod.ALL, TODAY);
        assertEquals(4, all.sold());
        assertEquals("3,000 ل.س", all.revenue().format());
        assertEquals(List.of(BATCH_NOV6, BATCH_OCT), all.batches().stream().map(SalesReport.BatchRow::batch).toList(),
                "newest batch first");
    }

    @Test
    void handMadeUsersAreGroupedAsOtherInAllTime() {
        List<RouterUser> users = List.of(card("ابو المعتصم", "1M", true, ""), card("x", "1M", false, "my note"));
        List<UsageRecord> history = List.of(new UsageRecord(ROUTER, "ابو المعتصم", "1M", "", Optional.empty()));

        SalesReport all = service.build(ROUTER, users, history, prices(Map.of("1M", "500")), ReportPeriod.ALL, TODAY);
        SalesReport week = service.build(ROUTER, users, history, prices(Map.of("1M", "500")),
                ReportPeriod.LAST_7_DAYS, TODAY);

        assertEquals(ReportService.OTHER, all.batches().getFirst().batch());
        assertEquals(2, all.batches().getFirst().cards());
        assertEquals("500", all.revenue().format());
        assertTrue(week.batches().isEmpty(), "\"Other\" has no creation date, so only All time shows it");
        assertTrue(week.warnings().stream().anyMatch(w -> w.contains("before NetPilot started recording first use")));
    }

    @Test
    void unreadableAndForeignCurrencyPricesAreWarnedAboutNotSummed() {
        List<UsageRecord> history = List.of(
                new UsageRecord(ROUTER, "a", "1M", "", Optional.of(TODAY)),
                new UsageRecord(ROUTER, "b", "2M", "", Optional.of(TODAY)),
                new UsageRecord(ROUTER, "c", "5M", "", Optional.of(TODAY)),
                new UsageRecord(ROUTER, "d", "10M", "", Optional.of(TODAY)));
        VoucherTemplate template = prices(Map.of("1M", "500 ل.س", "2M", "1000 ل.س", "5M", "3 $", "10M", "مجاني"));

        SalesReport report = service.build(ROUTER, List.of(), history, template, ReportPeriod.ALL, TODAY);

        assertEquals("1,500 ل.س", report.revenue().format());
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("5M") && w.contains("another currency")));
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("10M") && w.contains("no number")));
    }

    @Test
    void noPricesGivesZeroRevenueAndAHint() {
        SalesReport report = service.build(ROUTER, List.of(card("a", "1M", true, "")),
                List.of(new UsageRecord(ROUTER, "a", "1M", "", Optional.of(TODAY))), VoucherTemplate.defaults(),
                ReportPeriod.ALL, TODAY);

        assertEquals("0", report.revenue().format());
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("Voucher design page")));
    }

    @Test
    void profilesAreSortedByRevenueWithData() {
        List<RouterUser> users = List.of(card("a", "1M", true, ""), card("b", "2M", true, ""),
                card("c", "2M", false, ""));
        List<UsageRecord> history = List.of(new UsageRecord(ROUTER, "a", "1M", "", Optional.of(TODAY)),
                new UsageRecord(ROUTER, "b", "2M", "", Optional.of(TODAY)));

        SalesReport report = service.build(ROUTER, users, history, prices(Map.of("1M", "500", "2M", "1000")),
                ReportPeriod.ALL, TODAY);

        assertEquals(List.of("2M", "1M"), report.profiles().stream().map(SalesReport.ProfileRow::profile).toList());
        SalesReport.ProfileRow twoM = report.profiles().getFirst();
        assertEquals(1, twoM.sold());
        assertEquals(1, twoM.unused());
        assertEquals(6000, twoM.bytes());
    }

    @Test
    void dataColumnCountsOnlyCardsSoldInThePeriod() {
        List<RouterUser> users = List.of(card("old", "2M", true, ""), card("new", "2M", true, ""));
        List<UsageRecord> history = List.of(new UsageRecord(ROUTER, "old", "2M", "", Optional.empty()),
                new UsageRecord(ROUTER, "new", "2M", "", Optional.of(TODAY)));

        SalesReport week = service.build(ROUTER, users, history, prices(Map.of("2M", "1000")),
                ReportPeriod.LAST_7_DAYS, TODAY);

        SalesReport.ProfileRow twoM = week.profiles().getFirst();
        assertEquals(1, twoM.sold());
        assertEquals(6000, twoM.bytes(), "the card used before tracking started is not in this period");
    }

    @Test
    void usageHistoryRoundTripsAndSkipsBadLines() throws Exception {
        UsageRepository repo = new UsageRepository(dir.resolve("usage.csv"));
        List<UsageRecord> records = new ArrayList<>(List.of(
                new UsageRecord(ROUTER, "ابو المعتصم", "1M", "", Optional.empty()),
                new UsageRecord(ROUTER, "a,b", "2M", BATCH_NOV6, Optional.of(TODAY))));

        repo.saveAll(records);
        Files.writeString(dir.resolve("usage.csv"), "broken line\n10.0.0.1:80,x,1M,,2026-13-01\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        assertEquals(records, repo.findAll());
        assertTrue(new UsageRepository(dir.resolve("none.csv")).findAll().isEmpty());
    }

    @Test
    void reportExportsToCsv() throws Exception {
        SalesReport report = service.build(ROUTER, List.of(card("a", "1M", true, BATCH_NOV6)),
                List.of(new UsageRecord(ROUTER, "a", "1M", BATCH_NOV6, Optional.of(TODAY))),
                prices(Map.of("1M", "500 ل.س")), ReportPeriod.ALL, TODAY);
        Path file = dir.resolve("report.csv");

        new ReportExporter().export(report, file);

        String csv = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("﻿NetPilot sales report,All time"));
        assertTrue(csv.contains("Revenue,500 ل.س"));
        assertTrue(csv.contains(BATCH_NOV6 + ",2026-11-06 21:56,1M,1,1,0,500 ل.س"));
        assertTrue(csv.contains("2026-11-07,500 ل.س"));
    }
}
