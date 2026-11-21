package org.netpilot.controller;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.util.StringConverter;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.model.BackupSettings;
import org.netpilot.service.BackupService;
import org.netpilot.service.CloudFolders;

/**
 * Settings › Cloud backup: pick a folder OneDrive, Google Drive for desktop or Dropbox keeps in
 * sync, set the backup password once (remembered on this computer only), and see when the last
 * copy was made.
 */
final class CloudSettings {

    private static final System.Logger LOG = System.getLogger(CloudSettings.class.getName());

    private final Workspace workspace;
    private final VBox box;

    CloudSettings(Workspace workspace, VBox box) {
        this.workspace = workspace;
        this.box = box;
        workspace.cloudBackup().onChange(this::show);
    }

    void show() {
        BackupSettings backup = workspace.settings().backup();
        Label status = new Label();
        status.getStyleClass().add("protection-status");
        status.setWrapText(true);
        if (backup.cloud()) {
            status.setText(Messages.get("cloud.on", backup.cloudFolder()));
            Label last = new Label(backup.lastCloud()
                    .map(t -> Messages.get("cloud.last", t.format(ShiftCenter.TIME)))
                    .orElse(Messages.get("cloud.never")));
            last.getStyleClass().add("muted");
            if (!workspace.context().passwordVault().hasBackupPassword()) {
                last.setText(Messages.get("cloud.noPassword"));
                last.getStyleClass().add("status-error");
            }
            Button now = new Button(Messages.get("cloud.now"), Icons.of(Icons.REFRESH));
            now.getStyleClass().add("primary");
            now.setOnAction(e -> workspace.cloudBackup().run(true));
            Button password = new Button(Messages.get("cloud.changePassword"));
            password.setOnAction(e -> {
                if (choosePassword()) {
                    show();
                }
            });
            Button open = new Button(Messages.get("main.openFolder"), Icons.of(Icons.FOLDER));
            open.setOnAction(e -> workspace.context().hostServices().showDocument(
                    Path.of(backup.cloudFolder(), BackupService.CLOUD_SUBFOLDER).toUri().toString()));
            Button stop = new Button(Messages.get("cloud.stop"));
            stop.getStyleClass().add("danger");
            stop.setOnAction(e -> {
                workspace.updateSettings(s -> s.withBackup(s.backup().withCloud("")));
                show();
            });
            FlowPane buttons = new FlowPane(8, 8, now, password, open, stop);
            box.getChildren().setAll(status, last, buttons, help());
            return;
        }
        status.setText(Messages.get("cloud.off"));
        List<CloudFolders.Folder> found = CloudFolders.find();
        ComboBox<CloudFolders.Folder> folders = new ComboBox<>();
        folders.getItems().setAll(found);
        folders.setConverter(new StringConverter<>() {

            @Override
            public String toString(CloudFolders.Folder f) {
                return f == null ? "" : f.service() + "  ·  " + f.path();
            }

            @Override
            public CloudFolders.Folder fromString(String text) {
                return null;
            }
        });
        if (!found.isEmpty()) {
            folders.getSelectionModel().selectFirst();
        }
        folders.setPromptText(Messages.get("cloud.noneFound"));
        folders.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(folders, javafx.scene.layout.Priority.ALWAYS);
        Button other = new Button(Messages.get("cloud.otherFolder"), Icons.of(Icons.FOLDER));
        other.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(Messages.get("cloud.title"));
            File chosen = chooser.showDialog(workspace.window());
            if (chosen != null) {
                CloudFolders.Folder picked = new CloudFolders.Folder(Messages.get("cloud.folder"), chosen.toPath());
                folders.getItems().add(picked);
                folders.getSelectionModel().select(picked);
            }
        });
        HBox row = new HBox(8, folders, other);
        row.setAlignment(Pos.CENTER_LEFT);
        Button start = new Button(Messages.get("cloud.start"));
        start.getStyleClass().add("primary");
        start.disableProperty().bind(folders.getSelectionModel().selectedItemProperty().isNull());
        start.setOnAction(e -> start(folders.getValue()));
        box.getChildren().setAll(status, row, start, help());
    }

    private Label help() {
        Label help = new Label(Messages.get("cloud.help"));
        help.getStyleClass().add("muted");
        help.setWrapText(true);
        return help;
    }

    /** Turns the cloud copy on: the backup password first (when none is remembered), then a copy at once. */
    private void start(CloudFolders.Folder folder) {
        if (folder == null) {
            return;
        }
        if (!workspace.context().passwordVault().available()) {
            Dialogs.error(workspace.window(), Messages.get("cloud.title"), Messages.get("cloud.noVault"));
            return;
        }
        if (!workspace.context().passwordVault().hasBackupPassword() && !choosePassword()) {
            return;
        }
        workspace.updateSettings(s -> s.withBackup(s.backup().withCloud(folder.path().toString())));
        LOG.log(System.Logger.Level.INFO, "Cloud backup to {0} ({1})", folder.path(), folder.service());
        show();
        workspace.cloudBackup().run(true);
    }

    /**
     * Asks for the backup password twice and that it was written down, then remembers it on this
     * computer. Without it a cloud copy can never be opened, on any computer.
     */
    private boolean choosePassword() {
        PasswordField first = new PasswordField();
        PasswordField again = new PasswordField();
        first.setPromptText(Messages.get("backup.password.prompt", BackupActions.MIN_PASSWORD));
        CheckBox written = new CheckBox(Messages.get("cloud.password.written"));
        written.setWrapText(true);
        Label warning = new Label(Messages.get("cloud.password.warning"));
        warning.setWrapText(true);
        warning.setMaxWidth(420);
        warning.getStyleClass().add("status-error");
        Label problem = new Label();
        problem.getStyleClass().add("status-error");
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label(Messages.get("backup.password")), first);
        grid.addRow(1, new Label(Messages.get("backup.password.again")), again);
        VBox content = new VBox(10, warning, grid, written, problem);
        content.setPadding(new Insets(6));
        Dialog<char[]> dialog = new Dialog<>();
        dialog.initOwner(workspace.window());
        dialog.setTitle(NetPilotApp.NAME);
        dialog.setHeaderText(Messages.get("cloud.password.title"));
        dialog.setGraphic(Icons.of(Icons.LOCK));
        ButtonType ok = new ButtonType(Messages.get("button.ok"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes()
                .setAll(ok, new ButtonType(Messages.get("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.getDialogPane().setContent(content);
        Styling.apply(dialog.getDialogPane());
        dialog.getDialogPane().lookupButton(ok).disableProperty().bind(written.selectedProperty().not());
        dialog.getDialogPane().lookupButton(ok).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (first.getText().length() < BackupActions.MIN_PASSWORD) {
                problem.setText(Messages.get("backup.password.short", BackupActions.MIN_PASSWORD));
                e.consume();
            } else if (!first.getText().equals(again.getText())) {
                problem.setText(Messages.get("backup.password.mismatch"));
                e.consume();
            }
        });
        dialog.setResultConverter(button -> button == ok ? first.getText().toCharArray() : null);
        Platform.runLater(first::requestFocus);
        Optional<char[]> chosen = dialog.showAndWait();
        first.clear();
        again.clear();
        if (chosen.isEmpty()) {
            return false;
        }
        try {
            workspace.context().passwordVault().saveBackupPassword(chosen.get());
            return true;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot remember the backup password", e);
            Dialogs.error(workspace.window(), Messages.get("cloud.title"), String.valueOf(e.getMessage()));
            return false;
        } finally {
            Arrays.fill(chosen.get(), '\0');
        }
    }
}
