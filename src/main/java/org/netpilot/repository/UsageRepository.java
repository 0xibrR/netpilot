package org.netpilot.repository;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.netpilot.model.UsageRecord;
import org.netpilot.service.Csv;

/**
 * Remembers which cards have been used and since when, in a CSV file. This is how reports know
 * the day a card was sold: RouterOS itself does not keep a first-login date.
 *
 * <p>Unreadable lines are skipped, never fatal. Writes go through a temporary file so a crash
 * cannot leave a half-written history.
 */
public class UsageRepository {

    private static final System.Logger LOG = System.getLogger(UsageRepository.class.getName());
    private static final String HEADER = "router,username,profile,batch,firstUsed";

    private final Path file;

    public UsageRepository(Path file) {
        this.file = file;
    }

    public boolean exists() {
        return Files.exists(file);
    }

    /**
     * @return every record, for all routers
     */
    public synchronized List<UsageRecord> findAll() throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<UsageRecord> records = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank() || line.equals(HEADER)) {
                continue;
            }
            try {
                List<String> f = Csv.parseLine(line);
                if (f.size() != 5) {
                    throw new IllegalArgumentException("expected 5 fields, got " + f.size());
                }
                Optional<LocalDate> firstUsed = f.get(4).isBlank()
                        ? Optional.empty()
                        : Optional.of(LocalDate.parse(f.get(4)));
                records.add(new UsageRecord(f.get(0), f.get(1), f.get(2), f.get(3), firstUsed));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Skipping unreadable usage line " + lineNumber + ": " + e.getMessage());
            }
        }
        return records;
    }

    /** Replaces the whole history. */
    public synchronized void saveAll(Collection<UsageRecord> records) throws IOException {
        FileSupport.createParentDirectories(file);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            writer.write(HEADER);
            writer.write(System.lineSeparator());
            for (UsageRecord r : records) {
                writer.write(Csv.line(r.router(), r.username(), r.profile(), r.batch(),
                        r.firstUsed().map(LocalDate::toString).orElse("")));
                writer.write(System.lineSeparator());
            }
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
