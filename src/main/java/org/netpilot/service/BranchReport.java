package org.netpilot.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import org.netpilot.model.Branch;
import org.netpilot.model.Money;
import org.netpilot.model.ReportPeriod;
import org.netpilot.model.SalesReport;
import org.netpilot.model.UsageRecord;
import org.netpilot.model.VoucherTemplate;
import org.netpilot.repository.StockReadings;

/**
 * Sales of every branch for one period, and their total. Each branch is worked out by
 * {@link ReportService#build}, the Earnings page's own calculation, from the sales records kept on
 * this computer, so no branch needs to be connected. Stock needs the router, so each branch shows
 * its last reading (when it was last connected).
 *
 * @param lines    one per branch, in the user's order; then routers with sales that are no branch
 * @param total    all branches together
 * @param profiles packages across all branches, highest revenue first
 * @param daily    revenue per day across all branches
 */
public record BranchReport(List<Line> lines, Line total, List<SalesReport.ProfileRow> profiles,
        SortedMap<LocalDate, Money> daily) {

    /**
     * One branch (or the total).
     *
     * @param address the router address its records are kept under; empty for the total
     * @param name    the branch name, or the address for a router that is no branch
     * @param sold    cards first used in the period
     * @param revenue their value
     * @param stock   the last stock reading, if the branch was ever read
     */
    public record Line(String address, String name, String color, int sold, Money revenue,
            Optional<StockReadings.Reading> stock) {
    }

    public BranchReport {
        lines = List.copyOf(lines);
        profiles = List.copyOf(profiles);
        daily = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(daily));
    }

    public static BranchReport build(ReportService reports, List<UsageRecord> history, List<Branch> branches,
            Map<String, StockReadings.Reading> stock, VoucherTemplate template, ReportPeriod period,
            LocalDate today) {
        Set<String> addresses = new LinkedHashSet<>();
        branches.forEach(b -> addresses.add(b.address()));
        history.stream().map(UsageRecord::router).sorted().forEach(addresses::add);

        List<Line> lines = new ArrayList<>();
        Map<String, SalesReport.ProfileRow> profiles = new LinkedHashMap<>();
        SortedMap<LocalDate, Money> daily = new TreeMap<>();
        int sold = 0;
        Money revenue = null;
        int unused = 0;
        Money stockValue = null;
        Optional<java.time.LocalDateTime> oldestReading = Optional.empty();
        for (String address : addresses) {
            SalesReport r = reports.build(address, List.of(), history, template, period, today);
            Optional<Branch> branch = branches.stream().filter(b -> b.address().equals(address)).findFirst();
            if (branch.isEmpty() && r.sold() == 0) {
                continue; // an old router without sales in the period: nothing to show
            }
            Optional<StockReadings.Reading> reading = Optional.ofNullable(stock.get(address));
            lines.add(new Line(address, branch.map(Branch::name).orElse(address),
                    branch.map(Branch::color).orElse(""), r.sold(), r.revenue(), reading));
            sold += r.sold();
            revenue = add(revenue, r.revenue());
            for (SalesReport.ProfileRow p : r.profiles()) {
                if (p.sold() > 0) {
                    profiles.merge(p.profile(), p, (a, b) -> new SalesReport.ProfileRow(a.profile(), a.price(),
                            a.sold() + b.sold(), 0, add(a.revenue(), b.revenue()), a.bytes() + b.bytes()));
                }
            }
            r.dailyRevenue().forEach((day, money) -> daily.merge(day, money, BranchReport::add));
            if (reading.isPresent()) {
                unused += reading.get().unused();
                stockValue = add(stockValue, reading.get().value());
                java.time.LocalDateTime at = reading.get().at();
                oldestReading = Optional.of(oldestReading.filter(o -> o.isBefore(at)).orElse(at));
            }
        }
        Money zero = Money.zero("");
        Optional<StockReadings.Reading> totalStock = stockValue == null
                ? Optional.empty()
                : Optional.of(new StockReadings.Reading(oldestReading.orElseThrow(), unused, stockValue));
        Line total = new Line("", "", "", sold, revenue == null ? zero : revenue, totalStock);
        List<SalesReport.ProfileRow> byRevenue = new ArrayList<>(profiles.values());
        byRevenue.sort(Comparator.comparing((SalesReport.ProfileRow p) -> p.revenue().amount()).reversed()
                .thenComparing(SalesReport.ProfileRow::profile));
        return new BranchReport(lines, total, byRevenue, daily);
    }

    /** Adds amounts; a zero without a currency takes the other's, and a second currency is left out. */
    static Money add(Money a, Money b) {
        if (a == null || a.amount().signum() == 0 && !a.sameCurrency(b)) {
            return b;
        }
        if (b == null || b.amount().signum() == 0 && !a.sameCurrency(b)) {
            return a;
        }
        return a.sameCurrency(b) ? a.plus(b) : a; // two currencies cannot be added; the report warns per branch
    }
}
