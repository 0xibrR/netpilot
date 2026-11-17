package org.netpilot.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.netpilot.model.LedgerEvent;
import org.netpilot.model.Shift;

/**
 * Cashier shifts in {@code shifts.csv}: one line per opening, withdrawal and closing, only ever
 * added to. The money taken during a shift is not typed in: it comes from the ledger (counter sales
 * and payments from points of sale between the opening and now), so it always matches the sales
 * and payments recorded elsewhere. A closing keeps its figures, so a closed shift never changes.
 */
public final class ShiftBook {

    private static final System.Logger LOG = System.getLogger(ShiftBook.class.getName());
    private static final String HEADER = "kind,id,shift,at,by,amount,currency,note,sales,payments,expected";

    /** The money of a shift so far. */
    public record Figures(BigDecimal openingCash, BigDecimal sales, BigDecimal payments, BigDecimal withdrawn,
            String currency) {

        public BigDecimal expected() {
            return openingCash.add(sales).add(payments).subtract(withdrawn);
        }
    }

    private final Path file;
    private final ResellerAccounts accounts;
    private final Clock clock;

    public ShiftBook(Path file, ResellerAccounts accounts, Clock clock) {
        this.file = Objects.requireNonNull(file);
        this.accounts = Objects.requireNonNull(accounts);
        this.clock = Objects.requireNonNull(clock);
    }

    /** The shift open now, if any. */
    public synchronized Optional<Shift> current() {
        return all().stream().filter(Shift::open).findFirst();
    }

    /** Every shift, newest first. */
    public synchronized List<Shift> all() {
        Map<String, Shift> shifts = new LinkedHashMap<>();
        for (List<String> f : lines()) {
            try {
                switch (f.get(0)) {
                    case "OPEN" -> shifts.put(f.get(1), new Shift(f.get(1), LocalDateTime.parse(f.get(3)), f.get(4),
                            new BigDecimal(f.get(5)), f.get(6), List.of(), Optional.empty()));
                    case "WITHDRAW" -> shifts.computeIfPresent(f.get(2), (id, s) -> s.withWithdrawal(
                            new Shift.Withdrawal(f.get(1), LocalDateTime.parse(f.get(3)), f.get(4),
                                    new BigDecimal(f.get(5)), f.get(7))));
                    case "CLOSE" -> shifts.computeIfPresent(f.get(2), (id, s) -> s.open()
                            ? s.closedWith(new Shift.Closing(LocalDateTime.parse(f.get(3)), f.get(4),
                                    new BigDecimal(f.get(8)), new BigDecimal(f.get(9)), new BigDecimal(f.get(10)),
                                    new BigDecimal(f.get(5)), f.get(7)))
                            : s);
                    default -> LOG.log(System.Logger.Level.WARNING, "Unknown shift line kind {0}", f.get(0));
                }
            } catch (IndexOutOfBoundsException | NumberFormatException | DateTimeParseException e) {
                LOG.log(System.Logger.Level.WARNING, "Skipping an unreadable shift line");
            }
        }
        List<Shift> newestFirst = new ArrayList<>(shifts.values());
        java.util.Collections.reverse(newestFirst);
        return newestFirst;
    }

    /**
     * Opens a shift.
     *
     * @param by          who opens it ({@code OWNER} or {@code CASHIER})
     * @param openingCash the cash in the drawer now
     * @throws IllegalStateException when a shift is already open
     */
    public synchronized Shift open(String by, BigDecimal openingCash, String currency) throws IOException {
        if (current().isPresent()) {
            throw new IllegalStateException("shift-open");
        }
        requireAmount(openingCash, true);
        Shift shift = new Shift(UUID.randomUUID().toString(), now(), by, openingCash, currency, List.of(),
                Optional.empty());
        append(List.of("OPEN", shift.id(), "", shift.opened().toString(), by, plain(openingCash), currency, "", "",
                "", ""));
        LOG.log(System.Logger.Level.INFO, "Shift opened by the {0}", by);
        return shift;
    }

    /**
     * Records cash taken out of the drawer.
     *
     * @throws IllegalStateException when no shift is open
     */
    public synchronized Shift.Withdrawal withdraw(String by, BigDecimal amount, String reason) throws IOException {
        Shift shift = current().orElseThrow(() -> new IllegalStateException("no-shift"));
        requireAmount(amount, false);
        Shift.Withdrawal w = new Shift.Withdrawal(UUID.randomUUID().toString(), now(), by, amount,
                reason == null ? "" : reason.strip());
        append(List.of("WITHDRAW", w.id(), shift.id(), w.at().toString(), by, plain(amount), shift.currency(),
                w.reason(), "", "", ""));
        return w;
    }

    /** The shift's money until now (or until its closing). */
    public synchronized Figures figures(Shift shift) throws IOException {
        if (shift.closing().isPresent()) {
            Shift.Closing c = shift.closing().get();
            return new Figures(shift.openingCash(), c.sales(), c.payments(), shift.withdrawn(), shift.currency());
        }
        return taken(shift, now());
    }

    /**
     * Closes the open shift with the cash counted.
     *
     * @return the closed shift with its figures
     * @throws IllegalStateException when no shift is open
     */
    public synchronized Shift close(String by, BigDecimal counted, String note) throws IOException {
        Shift shift = current().orElseThrow(() -> new IllegalStateException("no-shift"));
        requireAmount(counted, true);
        LocalDateTime at = now();
        Figures f = taken(shift, at);
        Shift.Closing closing = new Shift.Closing(at, by, f.sales(), f.payments(), f.expected(), counted,
                note == null ? "" : note.strip());
        append(List.of("CLOSE", UUID.randomUUID().toString(), shift.id(), at.toString(), by, plain(counted),
                shift.currency(), closing.note(), plain(f.sales()), plain(f.payments()), plain(f.expected())));
        LOG.log(System.Logger.Level.INFO, "Shift closed by the {0}; difference {1}", by, closing.difference());
        return shift.closedWith(closing);
    }

    /**
     * Counter sales and payments recorded from the opening until {@code until}. A payment cancelled
     * later never reached the drawer, so it does not count.
     */
    private Figures taken(Shift shift, LocalDateTime until) throws IOException {
        List<LedgerEvent> ledger = accounts.ledger();
        Set<String> voided = new HashSet<>();
        ledger.stream().filter(e -> e.kind() == LedgerEvent.Kind.PAYMENT_VOID).forEach(e -> voided.add(e.ref()));
        BigDecimal sales = BigDecimal.ZERO;
        BigDecimal payments = BigDecimal.ZERO;
        for (LedgerEvent e : ledger) {
            if (e.at().isBefore(shift.opened()) || e.at().isAfter(until)) {
                continue;
            }
            if (e.kind() == LedgerEvent.Kind.DIRECT_SALE) {
                sales = sales.add(e.amount());
            } else if (e.kind() == LedgerEvent.Kind.PAYMENT && !voided.contains(e.id())) {
                payments = payments.add(e.amount());
            }
        }
        return new Figures(shift.openingCash(), sales, payments, shift.withdrawn(), shift.currency());
    }

    private static void requireAmount(BigDecimal amount, boolean zeroAllowed) {
        if (amount == null || amount.signum() < 0 || (!zeroAllowed && amount.signum() == 0)) {
            throw new IllegalArgumentException("amount");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private List<List<String>> lines() {
        List<List<String>> out = new ArrayList<>();
        if (!Files.exists(file)) {
            return out;
        }
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 1; i < all.size(); i++) {
                if (!all.get(i).isBlank()) {
                    out.add(Csv.parseLine(all.get(i)));
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Shifts unreadable: {0}", e.getMessage());
        }
        return out;
    }

    /** Adds one line; the file is written whole through a temporary file, so it is never half written. */
    private void append(List<String> fields) throws IOException {
        List<String> all = Files.exists(file)
                ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                : new ArrayList<>(List.of(HEADER));
        if (all.isEmpty()) {
            all.add(HEADER);
        }
        all.add(Csv.line(fields.toArray(String[]::new)));
        Path folder = Objects.requireNonNullElse(file.toAbsolutePath().getParent(), Path.of("."));
        Files.createDirectories(folder);
        Path temp = Files.createTempFile(folder, ".shifts-", ".tmp");
        try {
            Files.write(temp, all, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
