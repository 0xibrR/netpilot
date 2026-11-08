package org.netpilot.repository;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.netpilot.model.LedgerEvent;
import org.netpilot.model.Reseller;
import org.netpilot.service.Csv;

/**
 * Points of sale and their ledger, in NetPilot's data folder (never on the router, so deleting
 * cards from the router never touches them).
 *
 * <p>The points of sale file is rewritten when one is added or changed. The ledger file is only
 * appended to: every event is written as a new line and flushed to disk at once, and an event
 * whose id is already there is ignored, so a repeated click or a repeated refresh can never
 * record the same sale or payment twice.
 */
public class ResellerRepository {

    private static final System.Logger LOG = System.getLogger(ResellerRepository.class.getName());
    private static final String RESELLER_HEADER = "id,name,phone,commissionType,commissionValue,billing";
    private static final String LEDGER_HEADER = "id,kind,at,reseller,router,card,profile,price,commission,billing,amount,currency,ref,note";

    private final Path resellersFile;
    private final Path ledgerFile;

    public ResellerRepository(Path resellersFile, Path ledgerFile) {
        this.resellersFile = resellersFile;
        this.ledgerFile = ledgerFile;
    }

    public synchronized List<Reseller> resellers() throws IOException {
        List<Reseller> out = new ArrayList<>();
        for (List<String> f : rows(resellersFile, RESELLER_HEADER, 6)) {
            try {
                out.add(new Reseller(f.get(0), f.get(1), f.get(2), Reseller.CommissionType.valueOf(f.get(3)),
                        new BigDecimal(f.get(4)), Reseller.Billing.valueOf(f.get(5))));
            } catch (IllegalArgumentException e) {
                LOG.log(System.Logger.Level.WARNING, "Skipping unreadable point of sale {0}", f.get(0));
            }
        }
        return out;
    }

    /** Adds the point of sale, or replaces the one with the same id. */
    public synchronized void save(Reseller reseller) throws IOException {
        List<Reseller> all = new ArrayList<>(resellers());
        all.removeIf(r -> r.id().equals(reseller.id()));
        all.add(reseller);
        FileSupport.createParentDirectories(resellersFile);
        Path temp = resellersFile.resolveSibling(resellersFile.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            writer.write(RESELLER_HEADER);
            writer.write(System.lineSeparator());
            for (Reseller r : all) {
                writer.write(Csv.line(r.id(), r.name(), r.phone(), r.commissionType().name(),
                        r.commissionValue().toPlainString(), r.billing().name()));
                writer.write(System.lineSeparator());
            }
        }
        Files.move(temp, resellersFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The whole ledger in the order it was written. */
    public synchronized List<LedgerEvent> ledger() throws IOException {
        List<LedgerEvent> out = new ArrayList<>();
        for (List<String> f : rows(ledgerFile, LEDGER_HEADER, 14)) {
            try {
                out.add(new LedgerEvent(f.get(0), LedgerEvent.Kind.valueOf(f.get(1)), LocalDateTime.parse(f.get(2)),
                        f.get(3), f.get(4), f.get(5), f.get(6), new BigDecimal(f.get(7)), new BigDecimal(f.get(8)),
                        Reseller.Billing.valueOf(f.get(9)), new BigDecimal(f.get(10)), f.get(11), f.get(12),
                        f.get(13)));
            } catch (RuntimeException e) {
                // logged, never dropped silently: the line stays in the file for a later fix
                LOG.log(System.Logger.Level.WARNING, "Skipping unreadable ledger event {0}: {1}", f.get(0),
                        e.getMessage());
            }
        }
        return out;
    }

    /**
     * Appends the events whose ids are not in the ledger yet, and flushes them to disk.
     *
     * @return the events actually added
     */
    public synchronized List<LedgerEvent> append(Collection<LedgerEvent> events) throws IOException {
        Set<String> known = new HashSet<>();
        ledger().forEach(e -> known.add(e.id()));
        List<LedgerEvent> added = new ArrayList<>();
        StringBuilder lines = new StringBuilder();
        if (!Files.exists(ledgerFile)) {
            lines.append(LEDGER_HEADER).append(System.lineSeparator());
        }
        for (LedgerEvent e : events) {
            if (!known.add(e.id())) {
                continue; // already recorded: never twice
            }
            added.add(e);
            lines.append(Csv.line(e.id(), e.kind().name(), e.at().toString(), e.resellerId(), e.router(), e.card(),
                    e.profile(), e.price().toPlainString(), e.commission().toPlainString(), e.billing().name(),
                    e.amount().toPlainString(), e.currency(), e.ref(), e.note()))
                    .append(System.lineSeparator());
        }
        if (!added.isEmpty()) {
            FileSupport.createParentDirectories(ledgerFile);
            Files.writeString(ledgerFile, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
        }
        return added;
    }

    /** Parsed lines of a CSV file; unreadable lines are logged and skipped, never fatal. */
    private static List<List<String>> rows(Path file, String header, int fields) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<List<String>> rows = new ArrayList<>();
        int number = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            number++;
            if (line.isBlank() || line.equals(header)) {
                continue;
            }
            List<String> f = Csv.parseLine(line);
            if (f.size() != fields) {
                LOG.log(System.Logger.Level.WARNING, "Skipping unreadable line {0} of {1}", number, file);
                continue;
            }
            rows.add(f);
        }
        return rows;
    }
}
