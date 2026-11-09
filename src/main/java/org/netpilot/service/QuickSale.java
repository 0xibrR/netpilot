package org.netpilot.service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.netpilot.i18n.Messages;
import org.netpilot.model.BatchTag;
import org.netpilot.model.LedgerEvent;
import org.netpilot.model.Money;
import org.netpilot.model.RouterUser;
import org.netpilot.service.ResellerAccounts.Card;
import org.netpilot.service.ResellerAccounts.DirectSummary;

/**
 * Selling one card at the counter, kept apart from the points of sale: its own ledger entries
 * ({@code DIRECT_RESERVE}, {@code DIRECT_SALE}, {@code DIRECT_RELEASE}) that no point of sale's
 * balance or list ever includes.
 * <ol>
 * <li>{@link #reserve}: the card is reserved before anything is printed, so no other sale can
 * take it, however quickly sales follow each other;</li>
 * <li>the receipt is printed (or shown);</li>
 * <li>{@link #confirm} records the sale and the price received, or {@link #cancel} puts the card
 * back when printing failed.</li>
 * </ol>
 * If NetPilot stops between printing and recording, the reservation stays {@link #pending}: its
 * card cannot be sold again, and the sale is finished later (by the user, or automatically when
 * the card turns out used). Finishing uses an id made from the reservation, so it is recorded
 * once, however often it is retried.
 */
public final class QuickSale {

    private final ResellerAccounts accounts;

    public QuickSale(ResellerAccounts accounts) {
        this.accounts = Objects.requireNonNull(accounts);
    }

    /** A card reserved for a sale that is not finished yet. */
    public record Reservation(Card card, LedgerEvent reserve) {
    }

    /**
     * Unused cards ready to sell, per package: NetPilot cards, never used, not disabled, not
     * logged in now, not with any point of sale and not reserved or sold at the counter.
     */
    public static Map<String, Integer> stock(String router, List<RouterUser> users, Set<String> online,
            Map<String, String> holders) {
        Map<String, Integer> count = new TreeMap<>();
        for (RouterUser u : users) {
            if (sellable(router, u, online, holders)) {
                count.merge(u.profile(), 1, Integer::sum);
            }
        }
        return count;
    }

    /** The card to sell for this package: the oldest batch first, so old cards do not stay behind. */
    public static Optional<RouterUser> pick(String router, List<RouterUser> users, Set<String> online,
            Map<String, String> holders, String profile) {
        return users.stream()
                .filter(u -> u.profile().equals(profile) && sellable(router, u, online, holders))
                .min(Comparator.comparing((RouterUser u) -> BatchTag.parse(u.comment()).map(BatchTag::created)
                        .orElse(LocalDateTime.MAX)).thenComparing(RouterUser::name));
    }

    private static boolean sellable(String router, RouterUser u, Set<String> online, Map<String, String> holders) {
        return !u.builtIn() && u.neverUsed() && !u.disabled() && !online.contains(u.name())
                && BatchTag.parse(u.comment()).isPresent()
                && !holders.containsKey(ResellerAccounts.key(router, u.name()));
    }

    /**
     * Reserves the card for the counter at its package's current price.
     *
     * @return the reservation, or empty when the card was taken meanwhile or its package has no price
     */
    public Optional<Reservation> reserve(Card card, Map<String, String> prices) throws IOException {
        Optional<Money> price = Money.parse(prices.getOrDefault(card.profile(), ""));
        if (price.isEmpty()) {
            return Optional.empty();
        }
        return accounts.reserveDirect(card, price.get()).map(reserve -> new Reservation(card, reserve));
    }

    /** The receipt was handed over and paid. Recorded once, however often it is called. */
    public void confirm(Reservation sale) throws IOException {
        accounts.completeDirect(sale.reserve(), "");
    }

    /** The sale did not happen (printing failed, or cancelled): the card goes back to stock. */
    public void cancel(Reservation sale) throws IOException {
        accounts.releaseDirect(sale.reserve(), Messages.get("quickSale.cancelled"));
    }

    /** Sales left unfinished (NetPilot stopped, or recording failed, after the reservation). */
    public List<Reservation> pending() throws IOException {
        return accounts.pendingDirect().stream()
                .map(r -> new Reservation(new Card(r.router(), r.card(), r.profile()), r))
                .toList();
    }

    public DirectSummary summary(LocalDate today) throws IOException {
        return accounts.directSummary(today);
    }
}
