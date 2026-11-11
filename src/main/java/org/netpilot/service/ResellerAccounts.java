package org.netpilot.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.netpilot.i18n.Messages;
import org.netpilot.model.LedgerEvent;
import org.netpilot.model.LedgerEvent.Kind;
import org.netpilot.model.Money;
import org.netpilot.model.Reseller;
import org.netpilot.model.UsageRecord;
import org.netpilot.repository.ResellerRepository;

/**
 * The points of sale's accounts. Every figure is computed from the ledger, which is only added
 * to, so it is the same whatever happened to the cards on the router or to the prices since.
 *
 * <p>Three different things are kept apart: a <b>handover</b> (the card is now with the point of
 * sale, at a price and commission frozen at that moment), a <b>sale</b> (the card's first use,
 * recorded once) and a <b>payment</b> (money the point of sale paid, any amount).
 */
public final class ResellerAccounts {

    private final ResellerRepository repository;
    private final Clock clock;
    private final Supplier<String> ids;

    private static final System.Logger LOG = System.getLogger(ResellerAccounts.class.getName());

    /** Every line written to the ledger also goes to the log, to trace what happened when. */
    private static List<LedgerEvent> logged(List<LedgerEvent> added) {
        for (LedgerEvent e : added) {
            LOG.log(System.Logger.Level.INFO, "Ledger {0}: point of sale {1}, card {2}, price {3}, amount {4}",
                    e.kind(), e.resellerId(), e.card().isEmpty() ? "-" : e.card(), e.price().toPlainString(),
                    e.amount().toPlainString());
        }
        return added;
    }

    public ResellerAccounts(ResellerRepository repository, Clock clock) {
        this(repository, clock, () -> UUID.randomUUID().toString());
    }

    ResellerAccounts(ResellerRepository repository, Clock clock, Supplier<String> ids) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
        this.ids = Objects.requireNonNull(ids);
    }

    /** A card to hand over: which router it is on, its name and package. */
    public record Card(String router, String name, String profile) {
    }

    /**
     * What a handover would do, before it is done.
     *
     * @param fresh      cards no point of sale has
     * @param transfers  unsold cards another point of sale has; they move only if the user agrees
     * @param already    cards this point of sale already has (nothing to do)
     * @param sold       cards already sold through a point of sale: they never move
     * @param noPrice    cards whose package has no readable price: they cannot be valued, so not handed over
     */
    public record Plan(List<Card> fresh, List<Card> transfers, List<Card> already, List<Card> sold,
            List<Card> noPrice) {

        public Plan {
            fresh = List.copyOf(fresh);
            transfers = List.copyOf(transfers);
            already = List.copyOf(already);
            sold = List.copyOf(sold);
            noPrice = List.copyOf(noPrice);
        }
    }

    /**
     * A point of sale's figures, recomputed from its ledger.
     *
     * @param owes the balance: charges minus payments; below zero when it paid in advance. Show
     *             {@link #due()} and {@link #credit()} rather than this signed number.
     */
    public record Statement(Reseller reseller, int given, int sold, int left, BigDecimal salesValue,
            BigDecimal commission, BigDecimal charges, BigDecimal paid, BigDecimal owes, String currency,
            List<LedgerEvent> events) {

        public Statement {
            events = List.copyOf(events);
        }

        /**
         * Value of the cards the point of sale owes for, before commission: every card it received
         * when it pays on handover, only sold cards when it pays on sale. Each card counts once,
         * at the price frozen in its handover, so selling a card it paid for on handover adds
         * nothing. Charged value − commission = {@link #charges()}; charges − paid = {@link #owes()}.
         */
        public BigDecimal chargedValue() {
            return charges.add(commission);
        }

        /** What the point of sale still has to pay; never below zero. */
        public BigDecimal due() {
            return owes.max(BigDecimal.ZERO);
        }

        /**
         * Advance credit in the point of sale's favour: what it paid beyond what it owes. It is
         * used up by its next sales (or handovers, when it pays on handover).
         */
        public BigDecimal credit() {
            return owes.negate().max(BigDecimal.ZERO);
        }

        public String format(BigDecimal value) {
            return new Money(value, currency).format();
        }
    }

    /** The points of sale; never the counter, whose direct sales are kept apart. */
    public List<Reseller> resellers() throws IOException {
        return repository.resellers().stream().filter(r -> !r.id().equals(COUNTER)).toList();
    }

    /** Who direct sales belong to in the ledger; not a point of sale, never in their lists or balances. */
    public static final String COUNTER = "counter";

    // ------------------------------------------------------------------ direct sales

    /** Direct sales so far: how many and how much, overall and today. */
    public record DirectSummary(int sold, BigDecimal total, int soldToday, BigDecimal totalToday, String currency) {
    }

    /**
     * Reserves a card for a direct sale (before its receipt is printed). The card must be free:
     * not with a point of sale and not already reserved or sold.
     *
     * @return the reservation, or empty when the card is taken
     */
    public Optional<LedgerEvent> reserveDirect(Card card, Money price) throws IOException {
        Holding h = holdings(repository.ledger()).get(key(card.router(), card.name()));
        if (h != null && (h.sold() || !h.returned())) {
            return Optional.empty();
        }
        LedgerEvent reserve = new LedgerEvent(ids.get(), Kind.DIRECT_RESERVE, LocalDateTime.now(clock), COUNTER,
                card.router(), card.name(), card.profile(), price.amount(), BigDecimal.ZERO,
                Reseller.Billing.ON_HANDOVER, BigDecimal.ZERO, price.currency(), "", "");
        return logged(repository.append(List.of(reserve))).stream().findFirst();
    }

    /**
     * Completes a direct sale: the receipt was handed over and the price received. The id comes
     * from the reservation, so repeating this (a retry after a crash) never records it twice.
     *
     * @return the sale, or empty when it was already completed or released
     */
    public Optional<LedgerEvent> completeDirect(LedgerEvent reserve, String note) throws IOException {
        if (directOutcome(reserve).isPresent()) {
            return Optional.empty();
        }
        LedgerEvent sale = new LedgerEvent("direct-sale-" + reserve.id(), Kind.DIRECT_SALE, LocalDateTime.now(clock),
                COUNTER, reserve.router(), reserve.card(), reserve.profile(), reserve.price(), BigDecimal.ZERO,
                reserve.billing(), reserve.price(), reserve.currency(), reserve.id(), note);
        return logged(repository.append(List.of(sale))).stream().findFirst();
    }

    /** The direct sale did not happen: the card goes back to stock. Once per reservation. */
    public Optional<LedgerEvent> releaseDirect(LedgerEvent reserve, String note) throws IOException {
        if (directOutcome(reserve).isPresent()) {
            return Optional.empty();
        }
        LedgerEvent release = new LedgerEvent("direct-release-" + reserve.id(), Kind.DIRECT_RELEASE,
                LocalDateTime.now(clock), COUNTER, reserve.router(), reserve.card(), reserve.profile(), reserve.price(),
                BigDecimal.ZERO, reserve.billing(), BigDecimal.ZERO, reserve.currency(), reserve.id(), note);
        return logged(repository.append(List.of(release))).stream().findFirst();
    }

    /**
     * Reservations that were neither completed nor released: the app stopped (or recording
     * failed) after the receipt may have been printed. Their cards stay reserved until the user
     * says whether the receipt was handed over.
     */
    public List<LedgerEvent> pendingDirect() throws IOException {
        List<LedgerEvent> ledger = repository.ledger();
        Set<String> settled = new HashSet<>();
        ledger.stream().filter(e -> e.kind() == Kind.DIRECT_SALE || e.kind() == Kind.DIRECT_RELEASE)
                .forEach(e -> settled.add(e.ref()));
        return ledger.stream().filter(e -> e.kind() == Kind.DIRECT_RESERVE && !settled.contains(e.id())).toList();
    }

    public DirectSummary directSummary(LocalDate today) throws IOException {
        int sold = 0;
        int soldToday = 0;
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal totalToday = BigDecimal.ZERO;
        String currency = "";
        for (LedgerEvent e : repository.ledger()) {
            if (e.kind() != Kind.DIRECT_SALE) {
                continue;
            }
            sold++;
            total = total.add(e.amount());
            if (e.at().toLocalDate().equals(today)) {
                soldToday++;
                totalToday = totalToday.add(e.amount());
            }
            if (currency.isEmpty()) {
                currency = e.currency();
            }
        }
        return new DirectSummary(sold, total, soldToday, totalToday, currency);
    }

    private Optional<LedgerEvent> directOutcome(LedgerEvent reserve) throws IOException {
        return repository.ledger().stream()
                .filter(e -> (e.kind() == Kind.DIRECT_SALE || e.kind() == Kind.DIRECT_RELEASE)
                        && e.ref().equals(reserve.id()))
                .findFirst();
    }

    /** Adds a point of sale, or saves new settings for one (they apply to later handovers only). */
    public Reseller save(Reseller reseller) throws IOException {
        List<String> problems = reseller.validate();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", problems));
        }
        repository.save(reseller);
        return reseller;
    }

    /** A new point of sale's id. */
    public String newId() {
        return ids.get();
    }

    /** @param prices price text per package from Card design, e.g. {@code 2M -> 1000 ل.س} */
    public Plan plan(String resellerId, List<Card> cards, Map<String, String> prices) throws IOException {
        Map<String, Holding> holdings = holdings(repository.ledger());
        List<Card> fresh = new ArrayList<>();
        List<Card> transfers = new ArrayList<>();
        List<Card> already = new ArrayList<>();
        List<Card> sold = new ArrayList<>();
        List<Card> noPrice = new ArrayList<>();
        for (Card card : cards) {
            Holding h = holdings.get(key(card.router(), card.name()));
            boolean direct = h != null && h.handover().kind() == Kind.DIRECT_RESERVE && !h.returned();
            if (h != null && (h.sold() || direct)) {
                sold.add(card); // sold, or reserved for a direct sale: it never moves to a point of sale
            } else if (Money.parse(prices.getOrDefault(card.profile(), "")).isEmpty()) {
                noPrice.add(card);
            } else if (h == null || h.returned()) {
                fresh.add(card);
            } else if (h.handover().resellerId().equals(resellerId)) {
                already.add(card);
            } else {
                transfers.add(card);
            }
        }
        return new Plan(fresh, transfers, already, sold, noPrice);
    }

    /**
     * Hands the cards to the point of sale at today's price and commission, frozen in the ledger.
     *
     * @param moveFromOthers also move unsold cards another point of sale has (a return there, a
     *                       handover here); otherwise they are left where they are
     * @return the events written
     */
    public List<LedgerEvent> handOver(Reseller reseller, List<Card> cards, Map<String, String> prices,
            boolean moveFromOthers) throws IOException {
        Plan plan = plan(reseller.id(), cards, prices);
        Map<String, Holding> holdings = holdings(repository.ledger());
        LocalDateTime now = LocalDateTime.now(clock);
        List<LedgerEvent> events = new ArrayList<>();
        List<Card> give = new ArrayList<>(plan.fresh());
        if (moveFromOthers) {
            for (Card card : plan.transfers()) {
                LedgerEvent from = holdings.get(key(card.router(), card.name())).handover();
                events.add(new LedgerEvent(ids.get(), Kind.RETURN, now, from.resellerId(), card.router(), card.name(),
                        from.profile(), from.price(), from.commission(), from.billing(), BigDecimal.ZERO,
                        from.currency(), from.id(), Messages.get("ledger.note.transfer", reseller.name())));
                give.add(card);
            }
        }
        for (Card card : give) {
            Money price = Money.parse(prices.get(card.profile())).orElseThrow();
            events.add(new LedgerEvent(ids.get(), Kind.HANDOVER, now, reseller.id(), card.router(), card.name(),
                    card.profile(), price.amount(), reseller.commissionOn(price.amount()), reseller.billing(),
                    BigDecimal.ZERO, price.currency(), "", ""));
        }
        return logged(repository.append(events));
    }

    /** Takes back unsold cards from whoever has them (they can then be handed over again). */
    public List<LedgerEvent> returnCards(List<Card> cards, String note) throws IOException {
        Map<String, Holding> holdings = holdings(repository.ledger());
        LocalDateTime now = LocalDateTime.now(clock);
        List<LedgerEvent> events = new ArrayList<>();
        for (Card card : cards) {
            Holding h = holdings.get(key(card.router(), card.name()));
            if (h != null && !h.returned() && !h.sold()) {
                LedgerEvent from = h.handover();
                events.add(new LedgerEvent(ids.get(), Kind.RETURN, now, from.resellerId(), card.router(), card.name(),
                        from.profile(), from.price(), from.commission(), from.billing(), BigDecimal.ZERO,
                        from.currency(), from.id(), note));
            }
        }
        return logged(repository.append(events));
    }

    /**
     * Records the sale of every handed-over card that has been used, once per handover (the sale's
     * id is made from its handover, so it can never be written twice). Called wherever NetPilot
     * records first use, so it also happens before cards are deleted from the router.
     *
     * @param usage first-use records of one router
     * @return the new sales
     */
    public List<LedgerEvent> recordSales(String router, Collection<UsageRecord> usage) throws IOException {
        Map<String, Holding> holdings = holdings(repository.ledger());
        List<LedgerEvent> sales = new ArrayList<>();
        for (UsageRecord used : usage) {
            if (!used.router().equals(router)) {
                continue;
            }
            Holding h = holdings.get(key(router, used.username()));
            if (h == null || h.returned() || h.sold()) {
                continue;
            }
            LedgerEvent from = h.handover();
            if (from.kind() == Kind.DIRECT_RESERVE) {
                // a pending direct sale whose card is in use: the receipt was clearly handed over
                sales.add(new LedgerEvent("direct-sale-" + from.id(), Kind.DIRECT_SALE, LocalDateTime.now(clock),
                        COUNTER, router, used.username(), from.profile(), from.price(), BigDecimal.ZERO,
                        from.billing(), from.price(), from.currency(), from.id(),
                        Messages.get("quickSale.autoCompleted")));
                continue;
            }
            LocalDateTime when = used.firstUsed()
                    .map(LocalDate::atStartOfDay)
                    .filter(day -> !day.isBefore(from.at().toLocalDate().atStartOfDay()))
                    .orElse(LocalDateTime.now(clock));
            sales.add(new LedgerEvent("sale-" + from.id(), Kind.SALE, when, from.resellerId(), router, used.username(),
                    from.profile(), from.price(), from.commission(), from.billing(), BigDecimal.ZERO, from.currency(),
                    from.id(), ""));
        }
        return repository.append(sales);
    }

    /**
     * Records money the point of sale paid. Partial payments are normal; paying more than owed
     * leaves a credit.
     *
     * @param id made when the payment window opens: pressing Save twice records it once
     * @throws IllegalArgumentException when the amount is not more than zero
     */
    public Optional<LedgerEvent> pay(String id, Reseller reseller, BigDecimal amount, String currency, LocalDate day,
            String note) throws IOException {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(Messages.get("payment.validation.amount"));
        }
        LedgerEvent payment = new LedgerEvent(id, Kind.PAYMENT, day.atTime(LocalDateTime.now(clock).toLocalTime()),
                reseller.id(), "", "", "", BigDecimal.ZERO, BigDecimal.ZERO, reseller.billing(), amount, currency, "",
                note);
        return logged(repository.append(List.of(payment))).stream().findFirst();
    }

    /**
     * Cancels a payment entered by mistake; the payment stays in the ledger.
     *
     * @return the void, or empty when the payment does not exist or was already cancelled
     */
    public Optional<LedgerEvent> voidPayment(String paymentId, String note) throws IOException {
        List<LedgerEvent> ledger = repository.ledger();
        Optional<LedgerEvent> payment = ledger.stream()
                .filter(e -> e.kind() == Kind.PAYMENT && e.id().equals(paymentId))
                .findFirst();
        if (payment.isEmpty()) {
            return Optional.empty();
        }
        LedgerEvent p = payment.get();
        LedgerEvent voided = new LedgerEvent("void-" + p.id(), Kind.PAYMENT_VOID, LocalDateTime.now(clock),
                p.resellerId(), "", "", "", BigDecimal.ZERO, BigDecimal.ZERO, p.billing(), p.amount(), p.currency(),
                p.id(), note);
        return logged(repository.append(List.of(voided))).stream().findFirst();
    }

    /** Figures of every point of sale, by name. */
    public List<Statement> statements() throws IOException {
        List<LedgerEvent> ledger = repository.ledger();
        List<Statement> out = new ArrayList<>();
        for (Reseller r : resellers()) {
            out.add(statement(r, ledger));
        }
        out.sort(java.util.Comparator.comparing(s -> s.reseller().name().toLowerCase(java.util.Locale.ROOT)));
        return out;
    }

    /**
     * One point of sale in one month.
     *
     * @param month what happened in the month: cards given and sold, sales value, commission,
     *              charges and payments of that month only
     * @param atEnd the account as it stood at the end of the month: what it owed, or its credit
     */
    public record MonthStatement(Reseller reseller, Statement month, Statement atEnd) {

        /** Nothing happened in the month and nothing was owed: not worth a line in a report. */
        public boolean quiet() {
            return month.events().isEmpty() && atEnd.owes().signum() == 0;
        }
    }

    /** Direct sales at the counter in one month. */
    public record DirectMonth(int sold, BigDecimal total, String currency) {

        public String format() {
            return new Money(total, currency).format();
        }
    }

    /**
     * Every point of sale's figures for one month, by name. Built from the same ledger and the same
     * calculation as {@link #statements()}: the account up to the end of the month minus the account
     * up to its start. Prices and commissions are the ones frozen in each handover, so later
     * changes do not alter past months. A payment cancelled later is left out of every month,
     * since it was entered by mistake.
     */
    public List<MonthStatement> monthStatements(YearMonth month) throws IOException {
        List<LedgerEvent> ledger = withoutVoidedPayments(repository.ledger());
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.plusMonths(1).atDay(1).atStartOfDay();
        List<MonthStatement> out = new ArrayList<>();
        for (Reseller r : resellers()) {
            Statement before = statement(r, until(ledger, start));
            Statement atEnd = statement(r, until(ledger, end));
            List<LedgerEvent> inMonth = atEnd.events().stream()
                    .filter(e -> !e.at().isBefore(start) && e.at().isBefore(end))
                    .toList();
            MonthStatement line = new MonthStatement(r, difference(atEnd, before, inMonth), atEnd);
            if (!line.quiet()) {
                out.add(line);
            }
        }
        out.sort(java.util.Comparator.comparing(s -> s.reseller().name().toLowerCase(java.util.Locale.ROOT)));
        return out;
    }

    /** Direct sales completed in one month. */
    public DirectMonth directMonth(YearMonth month) throws IOException {
        int sold = 0;
        BigDecimal total = BigDecimal.ZERO;
        String currency = "";
        for (LedgerEvent e : repository.ledger()) {
            if (e.kind() == Kind.DIRECT_SALE && YearMonth.from(e.at()).equals(month)) {
                sold++;
                total = total.add(e.amount());
                if (currency.isEmpty()) {
                    currency = e.currency();
                }
            }
        }
        return new DirectMonth(sold, total, currency);
    }

    /** The months that have anything in the ledger (handovers, sales, payments, direct sales). */
    public Set<YearMonth> ledgerMonths() throws IOException {
        Set<YearMonth> months = new java.util.TreeSet<>();
        repository.ledger().forEach(e -> months.add(YearMonth.from(e.at())));
        return months;
    }

    /** Payments cancelled at any time, and their cancellations, are taken out. */
    private static List<LedgerEvent> withoutVoidedPayments(List<LedgerEvent> ledger) {
        Set<String> voided = new HashSet<>();
        ledger.stream().filter(e -> e.kind() == Kind.PAYMENT_VOID).forEach(e -> voided.add(e.ref()));
        return ledger.stream()
                .filter(e -> e.kind() != Kind.PAYMENT_VOID)
                .filter(e -> !(e.kind() == Kind.PAYMENT && voided.contains(e.id())))
                .toList();
    }

    private static List<LedgerEvent> until(List<LedgerEvent> ledger, LocalDateTime end) {
        return ledger.stream().filter(e -> e.at().isBefore(end)).toList();
    }

    private static Statement difference(Statement after, Statement before, List<LedgerEvent> events) {
        BigDecimal charges = after.charges().subtract(before.charges());
        BigDecimal paid = after.paid().subtract(before.paid());
        int given = after.given() - before.given();
        int sold = after.sold() - before.sold();
        return new Statement(after.reseller(), given, sold, given - sold,
                after.salesValue().subtract(before.salesValue()), after.commission().subtract(before.commission()),
                charges, paid, charges.subtract(paid), after.currency(), events);
    }

    /** Which point of sale has each card now (unsold or sold), by router and card name. */
    public Map<String, String> holders() throws IOException {
        Map<String, String> out = new HashMap<>();
        holdings(repository.ledger()).forEach((key, h) -> {
            if (!h.returned()) {
                out.put(key, h.handover().resellerId());
            }
        });
        return out;
    }

    public static String key(String router, String card) {
        return router + "|" + card;
    }

    static Statement statement(Reseller reseller, List<LedgerEvent> ledger) {
        List<LedgerEvent> own = ledger.stream().filter(e -> e.resellerId().equals(reseller.id())).toList();
        Set<String> returned = new HashSet<>();
        Set<String> soldHandovers = new HashSet<>();
        Set<String> voided = new HashSet<>();
        for (LedgerEvent e : own) {
            switch (e.kind()) {
                case RETURN -> returned.add(e.ref());
                case SALE -> soldHandovers.add(e.ref());
                case PAYMENT_VOID -> voided.add(e.ref());
                default -> {
                }
            }
        }
        int given = 0;
        int sold = 0;
        BigDecimal salesValue = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        BigDecimal charges = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        String currency = "";
        for (LedgerEvent e : own) {
            if (currency.isEmpty() && !e.currency().isEmpty()) {
                currency = e.currency();
            }
            if (e.kind() == Kind.HANDOVER && !returned.contains(e.id())) {
                given++;
                boolean isSold = soldHandovers.contains(e.id());
                if (isSold) {
                    sold++;
                    salesValue = salesValue.add(e.price());
                }
                // the billing frozen in each handover decides when it is owed
                if (isSold || e.billing() == Reseller.Billing.ON_HANDOVER) {
                    charges = charges.add(e.net());
                    commission = commission.add(e.commission());
                }
            } else if (e.kind() == Kind.PAYMENT && !voided.contains(e.id())) {
                paid = paid.add(e.amount());
            }
        }
        return new Statement(reseller, given, sold, given - sold, salesValue, commission, charges, paid,
                charges.subtract(paid), currency, own);
    }

    /** Each card's latest handover and what became of it. */
    private record Holding(LedgerEvent handover, boolean returned, boolean sold) {
    }

    private static Map<String, Holding> holdings(List<LedgerEvent> ledger) {
        Map<String, Holding> byCard = new LinkedHashMap<>();
        Map<String, String> cardOfHandover = new HashMap<>();
        for (LedgerEvent e : ledger) {
            String card = key(e.router(), e.card());
            switch (e.kind()) {
                case HANDOVER, DIRECT_RESERVE -> {
                    byCard.put(card, new Holding(e, false, false));
                    cardOfHandover.put(e.id(), card);
                }
                case RETURN, SALE, DIRECT_RELEASE, DIRECT_SALE -> {
                    Holding h = byCard.get(cardOfHandover.getOrDefault(e.ref(), ""));
                    if (h != null && h.handover().id().equals(e.ref())) {
                        boolean back = e.kind() == Kind.RETURN || e.kind() == Kind.DIRECT_RELEASE;
                        byCard.put(card, back
                                ? new Holding(h.handover(), true, h.sold())
                                : new Holding(h.handover(), h.returned(), true));
                    }
                }
                default -> {
                }
            }
        }
        return byCard;
    }
}
