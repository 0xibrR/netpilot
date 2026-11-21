package org.netpilot.controller;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.concurrent.Task;
import org.netpilot.i18n.Messages;
import org.netpilot.model.BackupSettings;

/**
 * The encrypted copy to a cloud-synced folder (OneDrive, Google Drive for desktop, Dropbox): once
 * a day by itself, or now from Settings. Uses the backup password remembered on this computer;
 * without it nothing is written and Home says the copy is overdue.
 */
final class CloudBackup {

    private static final System.Logger LOG = System.getLogger(CloudBackup.class.getName());

    private final Workspace workspace;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private boolean running;
    private LocalDateTime nextAttempt = LocalDateTime.MIN;

    CloudBackup(Workspace workspace) {
        this.workspace = workspace;
    }

    /** Told after each copy (or failure), e.g. to refresh Settings and the Home warning. */
    void onChange(Runnable listener) {
        listeners.add(listener);
    }

    /** Called every minute: makes the day's copy when one is due. A failure waits an hour. */
    void runIfDue() {
        LocalDateTime now = LocalDateTime.now();
        BackupSettings backup = workspace.settings().backup();
        if (!running && !now.isBefore(nextAttempt) && backup.cloudDue(now)) {
            run(false);
        }
    }

    /** Makes a copy now. */
    void run(boolean byHand) {
        BackupSettings backup = workspace.settings().backup();
        Optional<char[]> password = workspace.context().passwordVault().loadBackupPassword();
        if (running || !backup.cloud() || password.isEmpty()) {
            if (byHand && password.isEmpty()) {
                Dialogs.error(workspace.window(), Messages.get("cloud.title"), Messages.get("cloud.noPassword"));
            }
            return;
        }
        running = true;
        Path folder = Path.of(backup.cloudFolder());
        char[] key = password.get();
        Task<Path> task = new Task<>() {

            @Override
            protected Path call() throws java.io.IOException {
                try {
                    return workspace.context().backupService().createCloud(folder, key);
                } finally {
                    Arrays.fill(key, '\0');
                }
            }
        };
        task.setOnSucceeded(e -> {
            running = false;
            workspace.updateSettings(s -> s.withBackup(s.backup().cloudRan(LocalDateTime.now())));
            LOG.log(System.Logger.Level.INFO, "Cloud backup written to {0}", task.getValue());
            if (byHand) {
                workspace.toast("✓ " + Messages.get("cloud.done"));
            }
            listeners.forEach(Runnable::run);
        });
        task.setOnFailed(e -> {
            running = false;
            nextAttempt = LocalDateTime.now().plusHours(1);
            LOG.log(System.Logger.Level.WARNING, "Cloud backup to " + folder + " failed", task.getException());
            if (byHand) {
                Dialogs.error(workspace.window(), Messages.get("cloud.title"),
                        Messages.get("cloud.failedBody", String.valueOf(task.getException().getMessage())));
            } else {
                workspace.toast("⚠ " + Messages.get("cloud.failed"));
            }
            listeners.forEach(Runnable::run);
        });
        Thread thread = new Thread(task, "netpilot-cloud-backup");
        thread.setDaemon(true);
        thread.start();
    }
}
