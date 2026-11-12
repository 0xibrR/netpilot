package org.netpilot.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.netpilot.model.Customer;
import org.netpilot.service.Csv;

/**
 * Customers' phone numbers, one per card, in {@code customers.csv} (router, card, phone, linked,
 * reminded). Rewritten whole through a temporary file, so a failure never leaves half a file.
 * The numbers never leave this computer except in a WhatsApp message the user chooses to send.
 */
public final class CustomerRepository {

    private static final System.Logger LOG = System.getLogger(CustomerRepository.class.getName());
    private static final String HEADER = "router,card,phone,linked,reminded";

    private final Path file;

    public CustomerRepository(Path file) {
        this.file = Objects.requireNonNull(file);
    }

    /** Every linked number, by card key ({@link Customer#key()}). */
    public synchronized Map<String, Customer> all() throws IOException {
        Map<String, Customer> out = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return out;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) { // the first line is the header
            if (lines.get(i).isBlank()) {
                continue;
            }
            List<String> f = Csv.parseLine(lines.get(i));
            try {
                Customer c = new Customer(f.get(0), f.get(1), f.get(2), LocalDateTime.parse(f.get(3)),
                        f.size() > 4 && !f.get(4).isBlank()
                                ? Optional.of(LocalDateTime.parse(f.get(4)))
                                : Optional.empty());
                out.put(c.key(), c);
            } catch (IndexOutOfBoundsException | DateTimeParseException e) {
                LOG.log(System.Logger.Level.WARNING, "Skipping unreadable customer line {0}", i + 1);
            }
        }
        return out;
    }

    public Optional<Customer> find(String router, String card) throws IOException {
        return Optional.ofNullable(all().get(Customer.key(router, card)));
    }

    /** Links (or replaces) the number of one card. */
    public synchronized void save(Customer customer) throws IOException {
        Map<String, Customer> all = all();
        all.put(customer.key(), customer);
        write(all);
    }

    public synchronized void remove(String router, String card) throws IOException {
        Map<String, Customer> all = all();
        if (all.remove(Customer.key(router, card)) != null) {
            write(all);
        }
    }

    private void write(Map<String, Customer> all) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        for (Customer c : all.values()) {
            lines.add(Csv.line(c.router(), c.card(), c.phone(), c.linked().toString(),
                    c.reminded().map(LocalDateTime::toString).orElse("")));
        }
        Path folder = Objects.requireNonNullElse(file.toAbsolutePath().getParent(), Path.of("."));
        Files.createDirectories(folder);
        Path temp = Files.createTempFile(folder, ".customers-", ".tmp");
        try {
            Files.write(temp, lines, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
