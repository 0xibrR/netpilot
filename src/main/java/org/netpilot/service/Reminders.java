package org.netpilot.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.netpilot.model.Customer;
import org.netpilot.model.RouterUser;

/**
 * Which customers to remind that their card ends soon: cards with a linked number, still working,
 * not reminded yet, and either their validity ends within the hours set in Settings or less than
 * a tenth of their time or data is left. Read from the router's own figures.
 */
public final class Reminders {

    /** "Little left": this share of the time or data limit, or less. */
    static final double LOW_SHARE = 0.10;

    private static final DateTimeFormatter ROUTER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Reminders() {
    }

    /** Why a card is due. */
    public enum Reason {
        /** The validity in days ends soon. */
        ENDS_SOON,
        /** Little of the time limit is left. */
        TIME_LOW,
        /** Little of the data limit is left. */
        DATA_LOW
    }

    /**
     * One customer to remind.
     *
     * @param endsAt    when the validity ends, if the card has one
     * @param timeLeft  time left of the time limit, if it has one
     * @param bytesLeft data left of the data limit, if it has one
     */
    public record Due(Customer customer, RouterUser card, Reason reason, Optional<LocalDateTime> endsAt,
            Optional<Duration> timeLeft, OptionalLong bytesLeft) {
    }

    /**
     * @param router    the connected router's key, as stored with each customer
     * @param users     the router's hotspot users
     * @param expiries  each card's validity end as RouterOS shows it ({@code 2026-11-15 10:00:00})
     * @param customers the linked numbers
     * @param hours     remind when the validity ends within this many hours
     * @return the customers to remind, soonest first
     */
    public static List<Due> due(String router, Collection<RouterUser> users, Map<String, String> expiries,
            Map<String, Customer> customers, LocalDateTime now, int hours) {
        Map<String, RouterUser> byName = new java.util.HashMap<>();
        users.forEach(u -> byName.put(u.name(), u));
        List<Due> out = new ArrayList<>();
        for (Customer c : customers.values()) {
            RouterUser card = byName.get(c.card());
            if (!c.router().equals(router) || c.reminded().isPresent() || card == null || card.disabled()
                    || card.expired()) {
                continue; // another router, already reminded, gone, or already over
            }
            Optional<LocalDateTime> ends = parse(expiries.get(c.card())).filter(t -> t.isAfter(now));
            Optional<Duration> timeLeft = card.limit().map(limit -> limit.minus(card.uptime()));
            OptionalLong bytesLeft = card.limitBytes() > 0
                    ? OptionalLong.of(card.limitBytes() - card.totalBytes())
                    : OptionalLong.empty();
            Reason reason = null;
            if (ends.isPresent() && !ends.get().isAfter(now.plusHours(hours))) {
                reason = Reason.ENDS_SOON;
            } else if (!card.neverUsed() && card.limit().isPresent()
                    && timeLeft.get().toSeconds() <= card.limit().get().toSeconds() * LOW_SHARE) {
                reason = Reason.TIME_LOW;
            } else if (!card.neverUsed() && bytesLeft.isPresent()
                    && bytesLeft.getAsLong() <= card.limitBytes() * LOW_SHARE) {
                reason = Reason.DATA_LOW;
            }
            if (reason != null) {
                out.add(new Due(c, card, reason, ends, timeLeft, bytesLeft));
            }
        }
        out.sort(Comparator.comparing((Due d) -> d.endsAt().orElse(LocalDateTime.MAX))
                .thenComparing(d -> d.customer().card()));
        return out;
    }

    /** Before RouterOS 7.10 the scheduler wrote dates as {@code nov/15/2026}. */
    private static final DateTimeFormatter OLD_ROUTER_TIME = new java.time.format.DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("MMM/dd/yyyy HH:mm:ss").toFormatter(java.util.Locale.ENGLISH);

    /** RouterOS's {@code 2026-11-15 10:00:00} (or the older {@code nov/15/2026 10:00:00}); else unknown. */
    static Optional<LocalDateTime> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (DateTimeFormatter format : List.of(ROUTER_TIME, OLD_ROUTER_TIME)) {
            try {
                return Optional.of(LocalDateTime.parse(text.strip(), format));
            } catch (DateTimeParseException e) {
                // try the next format
            }
        }
        return Optional.empty();
    }
}
