package org.netpilot.model;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * The time range a report covers. "Sold" cards are counted by the day they were first used.
 */
public enum ReportPeriod {

    LAST_7_DAYS("Last 7 days"),
    LAST_30_DAYS("Last 30 days"),
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    ALL("All time");

    private final String label;

    ReportPeriod(String label) {
        this.label = label;
    }

    /** First day of the period; {@link LocalDate#MIN} for {@link #ALL}. */
    public LocalDate from(LocalDate today) {
        return switch (this) {
            case LAST_7_DAYS -> today.minusDays(6);
            case LAST_30_DAYS -> today.minusDays(29);
            case THIS_MONTH -> today.withDayOfMonth(1);
            case LAST_MONTH -> today.minusMonths(1).withDayOfMonth(1);
            case ALL -> LocalDate.MIN;
        };
    }

    /** Last day of the period (inclusive). */
    public LocalDate to(LocalDate today) {
        return this == LAST_MONTH ? today.minusMonths(1).with(TemporalAdjusters.lastDayOfMonth()) : today;
    }

    public boolean contains(LocalDate day, LocalDate today) {
        return !day.isBefore(from(today)) && !day.isAfter(to(today));
    }

    @Override
    public String toString() {
        return label;
    }
}
