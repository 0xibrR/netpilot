package org.netpilot.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Collection;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.netpilot.model.BatchTag;
import org.netpilot.model.ReportPeriod;
import org.netpilot.model.RouterUser;
import org.netpilot.model.SalesReport;
import org.netpilot.model.UsageRecord;
import org.netpilot.model.VoucherTemplate;

/**
 * Everything the monthly PDF report shows for one month. The sales figures are built by
 * {@link ReportService#build}, the same calculation the Earnings page uses, so the report and the
 * page always agree; the points of sale come from {@link ResellerAccounts#monthStatements}.
 *
 * @param month     the month reported
 * @param network   the network name from the card design
 * @param router    the router's name
 * @param created   when the report was made
 * @param sales     the month's sales
 * @param previous  the month before, for the comparison
 * @param resellers each point of sale with activity in the month (or money owed at its end)
 * @param direct    direct sales at the counter in the month
 * @param sections  the parts to include
 */
public record MonthlyReport(YearMonth month, String network, String router, LocalDateTime created, SalesReport sales,
        SalesReport previous, List<ResellerAccounts.MonthStatement> resellers, ResellerAccounts.DirectMonth direct,
        Set<Section> sections, BranchReport branches) {

    /** The parts of the report; each can be left out. */
    public enum Section {
        SUMMARY,
        CHART,
        PACKAGES,
        RESELLERS,
        BATCHES,
        BRANCHES,
        NOTES
    }

    public MonthlyReport {
        Objects.requireNonNull(month, "month");
        network = network == null ? "" : network.strip();
        router = router == null ? "" : router.strip();
        resellers = List.copyOf(resellers);
        sections = Set.copyOf(sections);
        branches = branches == null
                ? new BranchReport(List.of(), new BranchReport.Line("", "", "", 0, sales.revenue(), Optional.empty()),
                        List.of(), new java.util.TreeMap<>())
                : branches;
    }

    /** A report without the branches part. */
    public MonthlyReport(YearMonth month, String network, String router, LocalDateTime created, SalesReport sales,
            SalesReport previous, List<ResellerAccounts.MonthStatement> resellers, ResellerAccounts.DirectMonth direct,
            Set<Section> sections) {
        this(month, network, router, created, sales, previous, resellers, direct, sections, null);
    }

    /** The branches part is shown only when there are two or more branches to compare. */
    public boolean showsBranches() {
        return includes(Section.BRANCHES) && branches.lines().size() > 1;
    }

    public boolean includes(Section section) {
        return sections.contains(section);
    }

    /** Nothing was sold, created, handed over or paid in the month: a report would be empty. */
    public boolean empty() {
        return sales.sold() == 0 && sales.created() == 0 && resellers.isEmpty() && direct.sold() == 0;
    }

    /**
     * Change from the previous month in percent, rounded; empty when the previous month had
     * nothing to compare with (a change from zero has no meaningful percentage).
     */
    public static Optional<Integer> change(BigDecimal now, BigDecimal before) {
        if (before == null || before.signum() == 0 || now == null) {
            return Optional.empty();
        }
        return Optional.of(now.subtract(before).multiply(BigDecimal.valueOf(100))
                .divide(before.abs(), 0, RoundingMode.HALF_UP).intValue());
    }

    public static Optional<Integer> change(long now, long before) {
        return change(BigDecimal.valueOf(now), BigDecimal.valueOf(before));
    }

    /**
     * Builds the report for a month. The current month runs up to today; a past month covers all
     * its days; a month in the future cannot be reported.
     *
     * @throws IllegalArgumentException for a month after the current one
     */
    public static MonthlyReport build(YearMonth month, String routerKey, String routerName, List<RouterUser> users,
            List<UsageRecord> history, VoucherTemplate template, ResellerAccounts accounts, Set<Section> sections,
            ReportService reports, LocalDateTime now) throws IOException {
        LocalDate today = now.toLocalDate();
        if (month.isAfter(YearMonth.from(today))) {
            throw new IllegalArgumentException("A month in the future has no report: " + month);
        }
        SalesReport sales = reports.build(routerKey, users, history, template, ReportPeriod.THIS_MONTH,
                lastDay(month, today));
        YearMonth before = month.minusMonths(1);
        SalesReport previous = reports.build(routerKey, users, history, template, ReportPeriod.THIS_MONTH,
                before.atEndOfMonth());
        return new MonthlyReport(month, template.networkName(), routerName, now, sales, previous,
                accounts.monthStatements(month), accounts.directMonth(month), sections);
    }

    /**
     * The report with every branch's month side by side as well (the branches part): each branch
     * from the sales kept on this computer, its stock from its last reading.
     */
    public static MonthlyReport build(YearMonth month, String routerKey, String routerName, List<RouterUser> users,
            List<UsageRecord> history, VoucherTemplate template, ResellerAccounts accounts, Set<Section> sections,
            ReportService reports, LocalDateTime now, List<org.netpilot.model.Branch> branchList,
            java.util.Map<String, org.netpilot.repository.StockReadings.Reading> stock) throws IOException {
        MonthlyReport one = build(month, routerKey, routerName, users, history, template, accounts, sections, reports,
                now);
        BranchReport branches = BranchReport.build(reports, history, branchList, stock, template,
                ReportPeriod.THIS_MONTH, lastDay(month, now.toLocalDate()));
        return new MonthlyReport(one.month(), one.network(), one.router(), one.created(), one.sales(), one.previous(),
                one.resellers(), one.direct(), one.sections(), branches);
    }

    /**
     * "This month" as the Earnings page counts it, seen from the month's last day (or today for the
     * current month): exactly the days of that month.
     */
    static LocalDate lastDay(YearMonth month, LocalDate today) {
        return month.equals(YearMonth.from(today)) ? today : month.atEndOfMonth();
    }

    /**
     * The months worth offering, newest first: the current month, and every month in which a card
     * of this router was first used, a batch was created, or the points-of-sale ledger has entries.
     */
    public static List<YearMonth> months(String routerKey, Collection<UsageRecord> history,
            Collection<RouterUser> users, Collection<YearMonth> ledgerMonths, LocalDate today) {
        TreeSet<YearMonth> months = new TreeSet<>(java.util.Comparator.reverseOrder());
        YearMonth current = YearMonth.from(today);
        months.add(current);
        for (UsageRecord r : history) {
            if (r.router().equals(routerKey)) {
                r.firstUsed().ifPresent(day -> months.add(YearMonth.from(day)));
                BatchTag.parse(r.batch()).ifPresent(tag -> months.add(YearMonth.from(tag.created())));
            }
        }
        for (RouterUser u : users) {
            BatchTag.parse(u.comment()).ifPresent(tag -> months.add(YearMonth.from(tag.created())));
        }
        months.addAll(ledgerMonths);
        months.removeIf(m -> m.isAfter(current));
        return List.copyOf(months);
    }
}
