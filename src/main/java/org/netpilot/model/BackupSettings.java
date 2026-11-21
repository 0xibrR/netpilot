package org.netpilot.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Automatic daily backup: whether it is on, where the backups go and when the last one was made;
 * and the encrypted copy to a cloud-synced folder.
 *
 * @param enabled     whether NetPilot makes a backup by itself once a day
 * @param folder      where automatic backups go (e.g. a USB stick or a synced folder); empty for none chosen yet
 * @param lastRun     when the last backup (automatic or by hand) was made; empty if never
 * @param cloudFolder a folder OneDrive, Google Drive or Dropbox keeps in sync; empty for no cloud copy
 * @param lastCloud   when the last cloud copy was written; empty if never
 */
public record BackupSettings(boolean enabled, String folder, Optional<LocalDateTime> lastRun, String cloudFolder,
        Optional<LocalDateTime> lastCloud) {

    /** Days without a cloud copy before Home warns. */
    public static final int CLOUD_OVERDUE_DAYS = 2;

    public BackupSettings {
        folder = folder == null ? "" : folder.strip();
        lastRun = lastRun == null ? Optional.empty() : lastRun;
        cloudFolder = cloudFolder == null ? "" : cloudFolder.strip();
        lastCloud = lastCloud == null ? Optional.empty() : lastCloud;
    }

    public BackupSettings(boolean enabled, String folder, Optional<LocalDateTime> lastRun) {
        this(enabled, folder, lastRun, "", Optional.empty());
    }

    public static BackupSettings defaults() {
        return new BackupSettings(false, "", Optional.empty());
    }

    /** An automatic backup is due once a day, when it is on and has a folder. */
    public boolean due(LocalDateTime now) {
        return enabled && !folder.isEmpty()
                && lastRun.map(last -> last.toLocalDate().isBefore(LocalDate.from(now))).orElse(true);
    }

    public boolean cloud() {
        return !cloudFolder.isEmpty();
    }

    /** A cloud copy is due once a day, when a cloud folder is chosen. */
    public boolean cloudDue(LocalDateTime now) {
        return cloud() && lastCloud.map(last -> last.toLocalDate().isBefore(LocalDate.from(now))).orElse(true);
    }

    /** The cloud copy is on but none was written for {@link #CLOUD_OVERDUE_DAYS} days. */
    public boolean cloudOverdue(LocalDateTime now) {
        return cloud() && lastCloud.map(last -> last.isBefore(now.minusDays(CLOUD_OVERDUE_DAYS))).orElse(true);
    }

    public BackupSettings ran(LocalDateTime when) {
        return new BackupSettings(enabled, folder, Optional.of(when.withNano(0)), cloudFolder, lastCloud);
    }

    public BackupSettings cloudRan(LocalDateTime when) {
        return new BackupSettings(enabled, folder, lastRun, cloudFolder, Optional.of(when.withNano(0)));
    }

    public BackupSettings withAutomatic(boolean on, String newFolder) {
        return new BackupSettings(on, newFolder, lastRun, cloudFolder, lastCloud);
    }

    /** Chooses (or with an empty folder, stops) the cloud copy. */
    public BackupSettings withCloud(String newFolder) {
        return new BackupSettings(enabled, folder, lastRun, newFolder, lastCloud);
    }
}
