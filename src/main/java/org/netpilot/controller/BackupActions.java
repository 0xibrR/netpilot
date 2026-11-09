package org.netpilot.controller;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.repository.AppPaths;
import org.netpilot.service.BackupService;
import org.netpilot.service.BackupService.RestorePlan;

/**
 * The backup buttons of the Settings page: back up now (optionally encrypted with a password) and
 * restore, with the full check and the summary shown before anything changes.
 */
final class BackupActions {

    private static final System.Logger LOG = System.getLogger(BackupActions.class.getName());
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    /** Short passwords fall quickly to guessing, even with a slow key derivation. */
    static final int MIN_PASSWORD = 8;

    private final Workspace workspace;
    private final Runnable changed;

    /** @param changed runs after a backup was made, so the page can show the new date */
    BackupActions(Workspace workspace, Runnable changed) {
        this.workspace = workspace;
        this.changed = changed;
    }

    private BackupService service() {
        return workspace.context().backupService();
    }

    private Window window() {
        return workspace.window();
    }

    /** Asks whether to encrypt, then where to save, then makes and checks the backup. */
    void backUpNow() {
        CheckBox encrypt = new CheckBox(Messages.get("backup.encrypt"));
        PasswordField password = new PasswordField();
        PasswordField again = new PasswordField();
        password.setPromptText(Messages.get("backup.password.prompt", MIN_PASSWORD));
        again.setPromptText(Messages.get("backup.password.again"));
        password.disableProperty().bind(encrypt.selectedProperty().not());
        again.disableProperty().bind(encrypt.selectedProperty().not());
        Label hint = muted(Messages.get("backup.encrypt.hint"));
        Label problems = new Label();
        problems.getStyleClass().add("status-error");
        problems.setWrapText(true);
        GridPane form = form();
        form.add(encrypt, 0, 0, 2, 1);
        form.addRow(1, new Label(Messages.get("backup.password")), password);
        form.addRow(2, new Label(Messages.get("backup.password.again")), again);
        form.add(hint, 0, 3, 2, 1);
        form.add(problems, 0, 4, 2, 1);

        Optional<char[]> secret = show(Messages.get("backup.create"), form, problems, () -> {
            if (!encrypt.isSelected()) {
                return new char[0];
            }
            if (password.getText().length() < MIN_PASSWORD) {
                throw new IllegalArgumentException(Messages.get("backup.password.short", MIN_PASSWORD));
            }
            if (!password.getText().equals(again.getText())) {
                throw new IllegalArgumentException(Messages.get("backup.password.mismatch"));
            }
            return password.getText().toCharArray();
        });
        if (secret.isEmpty()) {
            return;
        }
        char[] key = secret.get();
        boolean encrypted = key.length > 0;
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("backup.create"));
        chooser.setInitialFileName("NetPilot-backup-" + LocalDate.now() + (encrypted ? ".npbackup" : ".zip"));
        chooser.getExtensionFilters().add(encrypted
                ? new FileChooser.ExtensionFilter(Messages.get("backup.file.encrypted"), "*.npbackup")
                : new FileChooser.ExtensionFilter(Messages.get("backup.file.plain"), "*.zip"));
        File file = chooser.showSaveDialog(window());
        if (file == null) {
            Arrays.fill(key, '\0');
            return;
        }
        run(() -> {
            try {
                return service().create(file.toPath(), key);
            } finally {
                Arrays.fill(key, '\0');
            }
        }, made -> {
            workspace.updateSettings(s -> s.withBackup(s.backup().ran(LocalDateTime.now())));
            changed.run();
            Dialogs.info(window(), Messages.get("backup.done.title"), Messages.get("backup.done.body",
                    file.getName(), made.files().size(), made.resellers(), made.ledgerLines(), made.usageRecords()));
        });
    }

    /** Picks a backup, checks it completely, shows what it holds, and restores only on a clear yes. */
    void restore() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("backup.restore"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(Messages.get("backup.file.any"),
                "*.zip", "*.npbackup"));
        File file = chooser.showOpenDialog(window());
        if (file == null) {
            return;
        }
        char[] key = new char[0];
        try {
            if (BackupService.encrypted(file.toPath())) {
                Optional<char[]> typed = askPassword();
                if (typed.isEmpty()) {
                    return;
                }
                key = typed.get();
            }
        } catch (IOException e) {
            Dialogs.error(window(), Messages.get("backup.restore"), e.getMessage());
            return;
        }
        char[] password = key;
        run(() -> service().check(file.toPath(), password), plan -> confirmAndRestore(plan, password));
    }

    private void confirmAndRestore(RestorePlan plan, char[] password) {
        BackupService.Summary b = plan.backup();
        StringBuilder body = new StringBuilder(Messages.get("backup.summary", b.created().format(WHEN),
                b.appVersion(), b.resellers(), b.ledgerLines(), b.usageRecords(), b.printBatches()));
        if (plan.losesNewerData()) {
            body.append("\n\n").append(Messages.get("backup.older", plan.newerLedger(), plan.newerUsage()));
        }
        body.append("\n\n").append(Messages.get("backup.restore.safety"));
        if (!Dialogs.confirm(window(), Messages.get(plan.losesNewerData()
                ? "backup.restore.titleOlder"
                : "backup.restore.title"), body.toString())) {
            Arrays.fill(password, '\0');
            return;
        }
        Path safety = AppPaths.dataDirectory().resolve("backups");
        run(() -> {
            try {
                return service().restore(plan.file(), password, safety);
            } finally {
                Arrays.fill(password, '\0');
            }
        }, saved -> {
            LOG.log(System.Logger.Level.INFO, "Restored {0}; the previous data is in {1}", plan.file(),
                    saved.map(Path::toString).orElse("(nothing to keep)"));
            Dialogs.info(window(), Messages.get("backup.restored.title"), Messages.get("backup.restored.body",
                    saved.map(Path::toString).orElse("—")));
            // every page is rebuilt from the restored files (the router stays connected)
            workspace.reloadLanguage();
        });
    }

    private Optional<char[]> askPassword() {
        PasswordField password = new PasswordField();
        Label problems = new Label();
        problems.getStyleClass().add("status-error");
        GridPane form = form();
        form.addRow(0, new Label(Messages.get("backup.password")), password);
        form.add(problems, 0, 1, 2, 1);
        return show(Messages.get("backup.password.needed"), form, problems, () -> {
            if (password.getText().isEmpty()) {
                throw new IllegalArgumentException(Messages.get("backup.error.NEEDS_PASSWORD"));
            }
            return password.getText().toCharArray();
        });
    }

    // ------------------------------------------------------------------ helpers

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted");
        label.setWrapText(true);
        label.setMaxWidth(380);
        return label;
    }

    private static GridPane form() {
        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.setPadding(new Insets(10, 4, 4, 4));
        return form;
    }

    private <T> Optional<T> show(String header, GridPane form, Label problems, Callable<T> read) {
        Dialog<T> dialog = new Dialog<>();
        dialog.initOwner(window());
        dialog.setTitle(NetPilotApp.NAME);
        dialog.setHeaderText(header);
        ButtonType ok = new ButtonType(Messages.get("button.ok"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(Messages.get("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(ok, cancel);
        dialog.getDialogPane().setContent(form);
        Styling.apply(dialog.getDialogPane());
        java.util.List<T> result = new java.util.ArrayList<>(1);
        dialog.getDialogPane().lookupButton(ok).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            try {
                result.clear();
                result.add(read.call());
            } catch (Exception problem) {
                problems.setText(problem.getMessage());
                e.consume();
            }
        });
        dialog.setResultConverter(button -> button == ok && !result.isEmpty() ? result.getFirst() : null);
        return dialog.showAndWait();
    }

    private <T> void run(Callable<T> work, Consumer<T> onSuccess) {
        Task<T> task = new Task<>() {

            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> {
            Throwable error = task.getException();
            LOG.log(System.Logger.Level.WARNING, "Backup action failed", error);
            Dialogs.error(window(), Messages.get("backup.failed"), String.valueOf(error.getMessage()));
        });
        Thread thread = new Thread(task, "netpilot-backup-action");
        thread.setDaemon(true);
        thread.start();
    }
}
