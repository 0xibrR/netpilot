package org.netpilot.controller;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.model.DataSize;
import org.netpilot.platform.WindowsInstall;
import org.netpilot.repository.AppPaths;
import org.netpilot.service.update.Release;
import org.netpilot.service.update.UpdateChecker;
import org.netpilot.service.update.UpdatePreferences;

/**
 * Offering and installing an update: the release notes with Update now / Later / Skip, then the
 * download with progress, the checksum check, a backup, a last confirmation, and the installer.
 * Nothing is installed without the user's click, and NetPilot closes only once the installer runs.
 */
final class UpdateFlow {

    private static final System.Logger LOG = System.getLogger(UpdateFlow.class.getName());

    /** What the user chose in the offer. */
    enum Choice {
        UPDATE,
        LATER,
        SKIP
    }

    private UpdateFlow() {
    }

    /** Shows the release notes and asks what to do; Skip is remembered. */
    static void offer(Workspace workspace, Release release) {
        Choice choice = ask(workspace, release);
        switch (choice) {
            case UPDATE -> install(workspace, release);
            case SKIP -> savePreferences(UpdatePreferences.load(AppPaths.updateFile()).skip(release));
            case LATER -> {
                // asked again on the next daily check
            }
        }
    }

    static Choice ask(Workspace workspace, Release release) {
        TextArea notes = new TextArea(release.notes().isEmpty() ? Messages.get("update.noNotes") : release.notes());
        notes.setEditable(false);
        notes.setWrapText(true);
        notes.setPrefSize(520, 260);
        VBox content = new VBox(8, new Label(Messages.get("update.whatsNew")), notes);
        content.setPadding(new Insets(6, 0, 0, 0));

        Dialog<Choice> dialog = new Dialog<>();
        dialog.initOwner(workspace.window());
        dialog.setTitle(NetPilotApp.NAME);
        dialog.setHeaderText(Messages.get("update.available", release.version(), NetPilotApp.VERSION));
        ButtonType update = new ButtonType(Messages.get("update.now"), ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType(Messages.get("update.later"), ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType skip = new ButtonType(Messages.get("update.skip"), ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().setAll(update, later, skip);
        dialog.getDialogPane().setContent(content);
        Styling.apply(dialog.getDialogPane());
        dialog.setResultConverter(b -> b == update ? Choice.UPDATE : b == skip ? Choice.SKIP : Choice.LATER);
        return dialog.showAndWait().orElse(Choice.LATER);
    }

    /**
     * Downloads, checks and installs. The portable copy (or a run from the IDE) cannot be updated
     * by an installer, so it gets the release page instead.
     */
    static void install(Workspace workspace, Release release) {
        if (!installedCopy() || release.installer().isEmpty()) {
            Dialogs.info(workspace.window(), Messages.get("update.portable.title"),
                    Messages.get("update.portable.body"));
            workspace.context().hostServices().showDocument(release.page());
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(380);
        Label status = new Label(Messages.get("update.downloading", release.version()));
        VBox box = new VBox(10, status, bar);
        box.setPadding(new Insets(10, 4, 4, 4));
        Dialog<Void> progress = new Dialog<>();
        progress.initOwner(workspace.window());
        progress.setTitle(NetPilotApp.NAME);
        progress.setHeaderText(Messages.get("update.preparing"));
        ButtonType cancel = new ButtonType(Messages.get("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        progress.getDialogPane().getButtonTypes().setAll(cancel);
        progress.getDialogPane().setContent(box);
        Styling.apply(progress.getDialogPane());

        String backupFolder = workspace.settings().backup().folder();
        UpdateChecker checker = UpdateChecker.gitHub(NetPilotApp.VERSION);
        Task<Prepared> task = new Task<>() {

            @Override
            protected Prepared call() throws Exception {
                Path installer = checker.download(release, AppPaths.updateDownloads(), (done, total) -> {
                    updateProgress(done, total > 0 ? total : -1);
                    updateMessage(total > 0
                            ? Messages.get("update.progress", DataSize.format(done), DataSize.format(total))
                            : DataSize.format(done));
                }, cancelled::get);
                updateMessage(Messages.get("update.backingUp"));
                updateProgress(-1, 1);
                Optional<String> backupProblem = Optional.empty();
                try {
                    Path backup = workspace.context().backupService().createAutomatic(Path.of(backupFolder));
                    LOG.log(System.Logger.Level.INFO, "Backup before the update written to {0}", backup);
                } catch (IOException | RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "Backup before the update failed", e);
                    backupProblem = Optional.of(String.valueOf(e.getMessage()));
                }
                return new Prepared(installer, backupProblem);
            }
        };
        bar.progressProperty().bind(task.progressProperty());
        status.textProperty().bind(task.messageProperty());
        progress.setOnCloseRequest(e -> {
            if (task.isRunning()) {
                cancelled.set(true);
                task.cancel(false);
            }
        });
        // the outcome is only recorded here; the next window opens once this one has fully closed
        // (a window opened while another modal one is closing stayed blank on Windows)
        task.setOnSucceeded(e -> progress.close());
        task.setOnFailed(e -> progress.close());
        Thread thread = new Thread(task, "netpilot-update");
        thread.setDaemon(true);
        thread.start();
        progress.showAndWait();
        if (task.isRunning()) {
            cancelled.set(true);
            task.cancel(false);
            return;
        }
        Platform.runLater(() -> afterDownload(workspace, release, task));
    }

    private static void afterDownload(Workspace workspace, Release release, Task<Prepared> task) {
        if (task.getState() == javafx.concurrent.Worker.State.SUCCEEDED) {
            confirmAndRun(workspace, release, task.getValue());
            return;
        }
        Throwable error = task.getException();
        if (error == null || error instanceof CancellationException) {
            return;
        }
        LOG.log(System.Logger.Level.WARNING, "Update download failed", error);
        Dialogs.error(workspace.window(), Messages.get("update.failed"), String.valueOf(error.getMessage()));
    }

    private record Prepared(Path installer, Optional<String> backupProblem) {
    }

    private static void confirmAndRun(Workspace workspace, Release release, Prepared prepared) {
        String body = Messages.get("update.ready.body", release.version())
                + prepared.backupProblem().map(p -> "\n\n" + Messages.get("update.ready.noBackup", p)).orElse("");
        LOG.log(System.Logger.Level.INFO, "Update {0} ready; asking to install", release.version());
        if (!Dialogs.confirm(workspace.window(), Messages.get("update.ready.title"), body)) {
            LOG.log(System.Logger.Level.INFO, "Update {0} not installed: declined", release.version());
            return; // the checked installer stays in the updates folder; the next offer downloads again
        }
        try {
            new ProcessBuilder(prepared.installer().toString()).start();
            LOG.log(System.Logger.Level.INFO, "Installer for {0} started; NetPilot closes", release.version());
            Platform.exit();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot start the installer", e);
            Dialogs.error(workspace.window(), Messages.get("update.failed"), String.valueOf(e.getMessage()));
        }
    }

    /**
     * This NetPilot was installed by the installer, so the new installer replaces it in place. The
     * portable copy and runs from the IDE are not. See {@link WindowsInstall}.
     */
    static boolean installedCopy() {
        return WindowsInstall.installed(NetPilotApp.NAME, System.getProperty("jpackage.app-path"));
    }

    static void savePreferences(UpdatePreferences preferences) {
        try {
            preferences.save(AppPaths.updateFile());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot save the update settings", e);
        }
    }
}
