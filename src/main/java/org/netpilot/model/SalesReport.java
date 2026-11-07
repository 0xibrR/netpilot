package org.netpilot.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Sales for one period. A card counts as sold on the day it was first used.
 *
 * @param period       the period this report covers
 * @param revenue      sold cards × their profile's price
 * @param potential    unused cards × their profile's price (money still to come)
 * @param sold         cards first used in the period
 * @param unused       cards on the router that were never used (now, whatever the period)
 * @param created      NetPilot cards created in the period
 * @param dailyRevenue revenue per day, for the chart
 * @param batches      one row per NetPilot batch created in the period, plus "Other" for hand-made users
 * @param profiles     one row per profile
 * @param warnings     things the user should know (missing prices, mixed currencies, ...)
 */
public record SalesReport(
        ReportPeriod period,
        Money revenue,
        Money potential,
        int sold,
        int unused,
        int created,
        SortedMap<LocalDate, Money> dailyRevenue,
        List<BatchRow> batches,
        List<ProfileRow> profiles,
        List<String> warnings) {

    /**
     * @param batch   batch comment, or "Other" for users without a NetPilot comment
     * @param created when the batch was created; empty for "Other"
     * @param profile the batch's profile, or "mixed"
     * @param cards   cards of the batch that still exist on the router or were sold
     * @param used    of those, how many have been used (ever)
     * @param revenue used × price
     */
    public record BatchRow(String batch, Optional<LocalDateTime> created, String profile, int cards, int used,
            Money revenue) {

        public int unused() {
            return cards - used;
        }
    }

    /**
     * @param profile profile name
     * @param price   its price, if one is set and readable
     * @param sold    cards of this profile sold in the period
     * @param unused  cards of this profile never used
     * @param revenue sold × price
     * @param bytes   data used by this profile's users on the router
     */
    public record ProfileRow(String profile, Optional<Money> price, int sold, int unused, Money revenue,
            long bytes) {
    }

    public SalesReport {
        dailyRevenue = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(dailyRevenue));
        batches = List.copyOf(batches);
        profiles = List.copyOf(profiles);
        warnings = List.copyOf(warnings);
    }
}
