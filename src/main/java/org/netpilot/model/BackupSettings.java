package org.netpilot.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Automatic daily backup: whether it is on, where the backups go and when the last one was made.
 *
 * @param enabled whether NetPilot makes a backup by itself once a day
 * @param folder  where automatic backups go (e.g. a USB stick or a synced folder); empty for none chosen yet
 * @param lastRun when the last backup (automatic or by hand) was made; empty if never
 */
public record BackupSettings(boolean enabled, String folder, Optional<LocalDateTime> lastRun) {

    public BackupSettings {
        folder = folder == null ? "" : folder.strip();
        lastRun = lastRun == null ? Optional.empty() : lastRun;
    }

    public static BackupSettings defaults() {
        return new BackupSettings(false, "", Optional.empty());
    }

    /** An automatic backup is due once a day, when it is on and has a folder. */
    public boolean due(LocalDateTime now) {
        return enabled && !folder.isEmpty()
                && lastRun.map(last -> last.toLocalDate().isBefore(LocalDate.from(now))).orElse(true);
    }

    public BackupSettings ran(LocalDateTime when) {
        return new BackupSettings(enabled, folder, Optional.of(when.withNano(0)));
    }

    public BackupSettings withAutomatic(boolean on, String newFolder) {
        return new BackupSettings(on, newFolder, lastRun);
    }
}
