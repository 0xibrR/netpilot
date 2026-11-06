package org.netpilot.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.netpilot.model.HistoryRecord;
import org.netpilot.service.Csv;

/**
 * Append-only export history in a CSV file. Unreadable lines are skipped, never fatal.
 */
public class HistoryRepository {

    private static final System.Logger LOG = System.getLogger(HistoryRepository.class.getName());

    private final Path file;

    public HistoryRepository(Path file) {
        this.file = file;
    }

    public synchronized void append(HistoryRecord record) throws IOException {
        FileSupport.createParentDirectories(file);
        String line = Csv.line(
                record.createdAt().toString(),
                String.valueOf(record.userCount()),
                record.profile(),
                record.folder().toString()) + System.lineSeparator();
        Files.writeString(file, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * @return all records, newest first
     */
    public synchronized List<HistoryRecord> findAll() throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<HistoryRecord> records = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            try {
                List<String> f = Csv.parseLine(line);
                if (f.size() != 4) {
                    throw new IllegalArgumentException("expected 4 fields, got " + f.size());
                }
                records.add(new HistoryRecord(
                        LocalDateTime.parse(f.get(0)),
                        Integer.parseInt(f.get(1)),
                        f.get(2),
                        Path.of(f.get(3))));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Skipping unreadable history line " + lineNumber + ": " + e.getMessage());
            }
        }
        records.sort(Comparator.comparing(HistoryRecord::createdAt).reversed());
        return records;
    }

    public synchronized void clear() throws IOException {
        Files.deleteIfExists(file);
    }
}
