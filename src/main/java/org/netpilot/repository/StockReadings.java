package org.netpilot.repository;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.netpilot.model.Money;

/**
 * Each branch's last stock reading (unsold cards and their value), saved whenever its router was
 * read, so the all-branches report can show stock for branches that are not connected. Only a
 * reminder of the last reading: losing the file loses nothing that the next connection does not
 * read again.
 */
public final class StockReadings {

    private static final System.Logger LOG = System.getLogger(StockReadings.class.getName());

    /**
     * One reading.
     *
     * @param at     when the router was read
     * @param unused unsold NetPilot cards
     * @param value  their value at the current prices
     */
    public record Reading(LocalDateTime at, int unused, Money value) {
    }

    private final Path file;

    public StockReadings(Path file) {
        this.file = Objects.requireNonNull(file);
    }

    /** The last reading per router address. */
    public synchronized Map<String, Reading> all() {
        Map<String, Reading> out = new HashMap<>();
        if (!Files.exists(file)) {
            return out;
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "Stock readings unreadable: {0}", e.getMessage());
            return out;
        }
        for (String address : p.stringPropertyNames()) {
            String[] f = p.getProperty(address).split("\\|", -1);
            try {
                out.put(address, new Reading(LocalDateTime.parse(f[0]), Integer.parseInt(f[1]),
                        new Money(new BigDecimal(f[2]), f[3])));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "Skipping stock reading of {0}", address);
            }
        }
        return out;
    }

    /** Saves the reading of one router (replacing its previous one). */
    public synchronized void save(String address, Reading reading) throws IOException {
        Map<String, Reading> all = all();
        all.put(address, reading);
        Properties p = new Properties();
        all.forEach((a, r) -> p.setProperty(a, r.at() + "|" + r.unused() + "|" + r.value().amount().toPlainString()
                + "|" + r.value().currency()));
        Path folder = Objects.requireNonNullElse(file.toAbsolutePath().getParent(), Path.of("."));
        Files.createDirectories(folder);
        Path temp = Files.createTempFile(folder, ".stock-", ".tmp");
        try {
            try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                p.store(out, "NetPilot: last stock reading per branch");
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
