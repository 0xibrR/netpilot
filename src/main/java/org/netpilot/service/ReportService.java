package org.netpilot.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.netpilot.model.BatchTag;
import org.netpilot.model.Money;
import org.netpilot.model.ReportPeriod;
import org.netpilot.model.RouterUser;
import org.netpilot.model.SalesReport;
import org.netpilot.model.UsageRecord;
import org.netpilot.model.VoucherTemplate;

/**
 * Builds sales reports from the router's users, the usage history and the voucher prices.
 *
 * <p>Pure calculation: no network, no files, no UI, so every rule here is unit-tested.
 * A card counts as sold on the first day NetPilot saw it used.
 */
public final class ReportService {

    static final String OTHER = "Other";
    private static final String MIXED = "mixed";

    /**
     * Records cards that have been used since the last time, with today's date.
     *
     * <p>The first time a router is tracked, cards that are already used get no date: the day they
     * were used is unknown, and dating them all "today" would show a fake spike in revenue.
     *
     * @return the whole history (all routers) including the new records
     */
    public List<UsageRecord> track(String router, List<RouterUser> users, List<UsageRecord> history,
            LocalDate today) {
        Set<String> known = history.stream()
                .filter(r -> r.router().equals(router))
                .map(UsageRecord::username)
                .collect(Collectors.toSet());
        boolean firstTime = known.isEmpty();

        List<UsageRecord> updated = new ArrayList<>(history);
        for (RouterUser user : users) {
            if (user.builtIn() || user.neverUsed() || known.contains(user.name())) {
                continue;
            }
            updated.add(new UsageRecord(router, user.name(), user.profile(), user.comment(),
                    firstTime ? Optional.empty() : Optional.of(today)));
        }
        return updated;
    }

    public SalesReport build(String router, List<RouterUser> users, List<UsageRecord> history,
            VoucherTemplate template, ReportPeriod period, LocalDate today) {
        List<String> warnings = new ArrayList<>();
        Prices prices = Prices.of(template, warnings);
        List<RouterUser> cards = users.stream().filter(u -> !u.builtIn()).toList();
        List<UsageRecord> usage = history.stream().filter(r -> r.router().equals(router)).toList();

        // sold in the period, by first-use day
        List<UsageRecord> sold = usage.stream()
                .filter(r -> r.firstUsed().map(day -> period.contains(day, today)).orElse(period == ReportPeriod.ALL))
                .toList();
        long undated = usage.stream().filter(r -> r.firstUsed().isEmpty()).count();
        if (undated > 0 && period != ReportPeriod.ALL) {
            warnings.add(undated + " cards were already used before NetPilot started tracking; they are counted in"
                    + " \"All time\" only.");
        }

        Money revenue = prices.total(sold.stream().map(UsageRecord::profile).toList());
        TreeMap<LocalDate, Money> daily = new TreeMap<>();
        for (UsageRecord r : sold) {
            if (r.firstUsed().isPresent()) {
                Money price = prices.of(r.profile()).orElse(prices.zero());
                daily.merge(r.firstUsed().get(), price, Money::plus);
            }
        }

        List<RouterUser> unusedCards = cards.stream().filter(RouterUser::neverUsed).toList();
        Money potential = prices.total(unusedCards.stream().map(RouterUser::profile).toList());

        List<SalesReport.BatchRow> batches = batches(cards, usage, prices, period, today);
        int created = batches.stream()
                .filter(b -> !b.batch().equals(OTHER))
                .mapToInt(SalesReport.BatchRow::cards)
                .sum();

        return new SalesReport(period, revenue, potential, sold.size(), unusedCards.size(), created, daily, batches,
                profiles(cards, sold, prices), warnings);
    }

    /** NetPilot batches created in the period (newest first), then hand-made users as "Other". */
    private static List<SalesReport.BatchRow> batches(List<RouterUser> cards, List<UsageRecord> usage, Prices prices,
            ReportPeriod period, LocalDate today) {
        // every card ever seen: on the router now, or sold and deleted since
        Map<String, Card> all = new LinkedHashMap<>();
        for (UsageRecord r : usage) {
            all.put(r.username(), new Card(r.profile(), r.batch(), true));
        }
        for (RouterUser u : cards) {
            boolean used = !u.neverUsed() || all.containsKey(u.name());
            all.put(u.name(), new Card(u.profile(), u.comment(), used));
        }

        Map<String, List<Card>> byBatch = all.values().stream().collect(Collectors.groupingBy(
                c -> BatchTag.parse(c.batch()).map(BatchTag::comment).orElse(OTHER), LinkedHashMap::new,
                Collectors.toList()));

        List<SalesReport.BatchRow> rows = new ArrayList<>();
        byBatch.forEach((batch, members) -> {
            Optional<BatchTag> tag = BatchTag.parse(batch);
            boolean inPeriod = tag.map(t -> period.contains(t.created().toLocalDate(), today))
                    .orElse(period == ReportPeriod.ALL);
            if (!inPeriod) {
                return;
            }
            Set<String> profiles = members.stream().map(Card::profile).collect(Collectors.toSet());
            List<String> usedProfiles = members.stream().filter(Card::used).map(Card::profile).toList();
            rows.add(new SalesReport.BatchRow(batch, tag.map(BatchTag::created),
                    profiles.size() == 1 ? profiles.iterator().next() : MIXED, members.size(),
                    usedProfiles.size(), prices.total(usedProfiles)));
        });
        rows.sort(Comparator.comparing((SalesReport.BatchRow r) -> r.created().isEmpty())
                .thenComparing(r -> r.created().orElse(null), Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
    }

    private static List<SalesReport.ProfileRow> profiles(List<RouterUser> cards, List<UsageRecord> sold,
            Prices prices) {
        Map<String, Long> soldCount = sold.stream()
                .collect(Collectors.groupingBy(UsageRecord::profile, Collectors.counting()));
        Map<String, Long> unused = cards.stream().filter(RouterUser::neverUsed)
                .collect(Collectors.groupingBy(RouterUser::profile, Collectors.counting()));
        Map<String, Long> bytes = cards.stream()
                .collect(Collectors.groupingBy(RouterUser::profile, Collectors.summingLong(RouterUser::totalBytes)));

        Set<String> names = new java.util.TreeSet<>();
        names.addAll(soldCount.keySet());
        names.addAll(unused.keySet());
        names.addAll(bytes.keySet());

        List<SalesReport.ProfileRow> rows = new ArrayList<>();
        for (String profile : names) {
            int soldHere = soldCount.getOrDefault(profile, 0L).intValue();
            Optional<Money> price = prices.of(profile);
            rows.add(new SalesReport.ProfileRow(profile, price, soldHere, unused.getOrDefault(profile, 0L).intValue(),
                    price.map(p -> p.times(soldHere)).orElse(prices.zero()), bytes.getOrDefault(profile, 0L)));
        }
        rows.sort(Comparator.comparing((SalesReport.ProfileRow r) -> r.revenue().amount()).reversed()
                .thenComparing(SalesReport.ProfileRow::profile));
        return rows;
    }

    private record Card(String profile, String batch, boolean used) {
    }

    /**
     * Readable prices in one currency. Prices without a number, or in a second currency, are left
     * out with a warning: adding "1000 ل.س" and "5 $" would give a meaningless total.
     */
    private record Prices(Map<String, Money> byProfile, String currency) {

        static Prices of(VoucherTemplate template, List<String> warnings) {
            Map<String, Money> parsed = new HashMap<>();
            template.prices().forEach((profile, text) -> {
                Optional<Money> money = Money.parse(text);
                if (money.isEmpty()) {
                    warnings.add("The price of " + profile + " (\"" + text + "\") has no number and is not counted.");
                } else {
                    parsed.put(profile, money.get());
                }
            });
            if (parsed.isEmpty()) {
                warnings.add("No prices are set. Add them in Tools → Voucher Design to see revenue.");
                return new Prices(Map.of(), "");
            }
            // the currency most prices use is the report currency
            String currency = parsed.values().stream()
                    .collect(Collectors.groupingBy(m -> m.currency().toLowerCase(java.util.Locale.ROOT),
                            Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
            Map<String, Money> sameCurrency = new HashMap<>();
            parsed.forEach((profile, money) -> {
                if (money.currency().equalsIgnoreCase(currency)) {
                    sameCurrency.put(profile, money);
                } else {
                    warnings.add("The price of " + profile + " uses another currency (" + money.format()
                            + ") and is not counted.");
                }
            });
            String display = sameCurrency.values().iterator().next().currency();
            return new Prices(Map.copyOf(sameCurrency), display);
        }

        Optional<Money> of(String profile) {
            return Optional.ofNullable(byProfile.get(profile));
        }

        Money zero() {
            return Money.zero(currency);
        }

        Money total(List<String> profiles) {
            return profiles.stream()
                    .map(this::of)
                    .flatMap(Optional::stream)
                    .reduce(zero(), Money::plus);
        }
    }
}
