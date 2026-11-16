package org.netpilot.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.netpilot.model.Alert;
import org.netpilot.service.Csv;

/**
 * The alerts noticed, newest last, in {@code alerts.csv}; the oldest go beyond {@link #KEEP}, so the
 * file never grows without end. Rewritten whole through a temporary file on each change.
 */
public final class AlertLog {

    private static final System.Logger LOG = System.getLogger(AlertLog.class.getName());
    private static final String HEADER = "id,kind,address,branch,detail,started,resolved";

    /** Alerts kept. */
    public static final int KEEP = 500;

    private final Path file;

    public AlertLog(Path file) {
        this.file = Objects.requireNonNull(file);
    }

    /** Every alert kept, oldest first. */
    public synchronized List<Alert> all() {
        List<Alert> out = new ArrayList<>();
        if (!Files.exists(file)) {
            return out;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) {
                    continue;
                }
                List<String> f = Csv.parseLine(lines.get(i));
                try {
                    out.add(new Alert(f.get(0), Alert.Kind.valueOf(f.get(1)), f.get(2), f.get(3), f.get(4),
                            LocalDateTime.parse(f.get(5)),
                            f.size() > 6 && !f.get(6).isBlank()
                                    ? Optional.of(LocalDateTime.parse(f.get(6)))
                                    : Optional.empty()));
                } catch (IndexOutOfBoundsException | IllegalArgumentException | DateTimeParseException e) {
                    LOG.log(System.Logger.Level.WARNING, "Skipping unreadable alert line {0}", i + 1);
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Alerts unreadable: {0}", e.getMessage());
        }
        return out;
    }

    /** Adds a new alert, or records that one with the same id ended. */
    public synchronized void save(Alert alert) throws IOException {
        List<Alert> all = all();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(alert.id())) {
                all.set(i, alert);
                replaced = true;
            }
        }
        if (!replaced) {
            all.add(alert);
        }
        while (all.size() > KEEP) {
            all.removeFirst();
        }
        write(all);
    }

    /**
     * Alerts still open from an earlier run end when NetPilot starts: it cannot know whether they
     * lasted, and a new reading raises them again if they still do.
     */
    public synchronized void closeOpen(LocalDateTime now) throws IOException {
        List<Alert> all = all();
        if (all.stream().anyMatch(Alert::active)) {
            write(all.stream().map(a -> a.active() ? a.resolvedAt(now) : a).toList());
        }
    }

    private void write(List<Alert> all) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        for (Alert a : all) {
            lines.add(Csv.line(a.id(), a.kind().name(), a.address(), a.branch(), a.detail(), a.started().toString(),
                    a.resolved().map(LocalDateTime::toString).orElse("")));
        }
        Path folder = Objects.requireNonNullElse(file.toAbsolutePath().getParent(), Path.of("."));
        Files.createDirectories(folder);
        Path temp = Files.createTempFile(folder, ".alerts-", ".tmp");
        try {
            Files.write(temp, lines, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
