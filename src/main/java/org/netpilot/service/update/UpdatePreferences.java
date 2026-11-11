package org.netpilot.service.update;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * The update check's own small settings file: whether to check by itself, the last day it checked
 * and a version the user chose to skip. Kept apart from the main settings; losing it only means a
 * reminder comes again.
 *
 * @param automatic  check once a day by itself
 * @param lastCheck  the last day a check reached GitHub
 * @param skipped    a version not to remind about again ("Skip this version")
 */
public record UpdatePreferences(boolean automatic, Optional<LocalDate> lastCheck, Optional<String> skipped) {

    private static final System.Logger LOG = System.getLogger(UpdatePreferences.class.getName());

    public UpdatePreferences {
        lastCheck = lastCheck == null ? Optional.empty() : lastCheck;
        skipped = skipped == null ? Optional.empty() : skipped.filter(s -> !s.isBlank());
    }

    public static UpdatePreferences defaults() {
        return new UpdatePreferences(true, Optional.empty(), Optional.empty());
    }

    /** An automatic check is due: turned on and not done today. */
    public boolean due(LocalDate today) {
        return automatic && lastCheck.map(day -> day.isBefore(today)).orElse(true);
    }

    /** Whether to remind about this release: not the version the user skipped. */
    public boolean remind(Release release) {
        return skipped.map(s -> !s.equals(release.version().toString())).orElse(true);
    }

    public UpdatePreferences withAutomatic(boolean on) {
        return new UpdatePreferences(on, lastCheck, skipped);
    }

    public UpdatePreferences checked(LocalDate day) {
        return new UpdatePreferences(automatic, Optional.of(day), skipped);
    }

    public UpdatePreferences skip(Release release) {
        return new UpdatePreferences(automatic, lastCheck, Optional.of(release.version().toString()));
    }

    /** Reads the file; a missing or unreadable file gives the defaults. */
    public static UpdatePreferences load(Path file) {
        Objects.requireNonNull(file);
        if (!Files.exists(file)) {
            return defaults();
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "Update settings unreadable, using defaults: {0}", e.getMessage());
            return defaults();
        }
        Optional<LocalDate> last;
        try {
            last = Optional.ofNullable(p.getProperty("lastCheck")).filter(s -> !s.isBlank()).map(LocalDate::parse);
        } catch (DateTimeParseException e) {
            last = Optional.empty();
        }
        return new UpdatePreferences(!"false".equals(p.getProperty("automatic")), last,
                Optional.ofNullable(p.getProperty("skipped")));
    }

    /** Writes the file whole (temporary file, then moved into place). */
    public void save(Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("automatic", String.valueOf(automatic));
        lastCheck.ifPresent(d -> p.setProperty("lastCheck", d.toString()));
        skipped.ifPresent(s -> p.setProperty("skipped", s));
        Path folder = Objects.requireNonNullElse(file.toAbsolutePath().getParent(), Path.of("."));
        Files.createDirectories(folder);
        Path temp = Files.createTempFile(folder, ".update-", ".tmp");
        try {
            try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                p.store(out, "NetPilot update check");
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
