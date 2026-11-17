package org.netpilot.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One cashier shift: the cash in the drawer when it opened, cash taken out during it, and its
 * closing with the figures as they were then, so a closed shift never changes afterwards.
 *
 * @param openedBy    {@code OWNER} or {@code CASHIER}
 * @param openingCash cash in the drawer at the start (the float)
 */
public record Shift(String id, LocalDateTime opened, String openedBy, BigDecimal openingCash, String currency,
        List<Withdrawal> withdrawals, Optional<Closing> closing) {

    public Shift {
        Objects.requireNonNull(id);
        Objects.requireNonNull(opened);
        openedBy = openedBy == null ? "" : openedBy;
        openingCash = openingCash == null ? BigDecimal.ZERO : openingCash;
        currency = currency == null ? "" : currency;
        withdrawals = List.copyOf(withdrawals);
        closing = closing == null ? Optional.empty() : closing;
    }

    /** Cash taken out of the drawer: an expense, or money handed to the owner. */
    public record Withdrawal(String id, LocalDateTime at, String by, BigDecimal amount, String reason) {
    }

    /**
     * How the shift ended.
     *
     * @param sales    counter sales taken during the shift
     * @param payments payments from points of sale (less any cancelled)
     * @param expected opening cash + sales + payments − withdrawals
     * @param counted  the cash counted in the drawer
     */
    public record Closing(LocalDateTime at, String by, BigDecimal sales, BigDecimal payments, BigDecimal expected,
            BigDecimal counted, String note) {

        /** Counted − expected: below zero is cash missing, above zero cash over. */
        public BigDecimal difference() {
            return counted.subtract(expected);
        }
    }

    public boolean open() {
        return closing.isEmpty();
    }

    public BigDecimal withdrawn() {
        return withdrawals.stream().map(Withdrawal::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Shift withWithdrawal(Withdrawal w) {
        List<Withdrawal> all = new java.util.ArrayList<>(withdrawals);
        all.add(w);
        return new Shift(id, opened, openedBy, openingCash, currency, all, closing);
    }

    public Shift closedWith(Closing c) {
        return new Shift(id, opened, openedBy, openingCash, currency, withdrawals, Optional.of(c));
    }
}
