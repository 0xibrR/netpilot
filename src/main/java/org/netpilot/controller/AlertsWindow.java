package org.netpilot.controller;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.model.Alert;

/** The alerts noticed, newest first: when, which branch, what, and whether it still lasts. */
final class AlertsWindow {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private AlertsWindow() {
    }

    static void show(Workspace workspace, List<Alert> alerts) {
        List<Alert> newestFirst = new ArrayList<>(alerts);
        Collections.reverse(newestFirst);
        TableView<Alert> table = new TableView<>();
        table.setPrefSize(720, 380);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(Messages.get("alert.none")));
        TableColumn<Alert, String> when = new TableColumn<>(Messages.get("alert.column.when"));
        when.setCellValueFactory(c -> new ReadOnlyStringWrapper(WHEN.format(c.getValue().started())));
        when.setPrefWidth(130);
        TableColumn<Alert, String> branch = new TableColumn<>(Messages.get("alert.column.branch"));
        branch.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().branch()));
        branch.setPrefWidth(130);
        TableColumn<Alert, String> what = new TableColumn<>(Messages.get("alert.column.what"));
        what.setCellValueFactory(c -> new ReadOnlyStringWrapper(AlertCenter.describe(c.getValue())));
        what.setPrefWidth(280);
        TableColumn<Alert, String> state = new TableColumn<>(Messages.get("alert.column.state"));
        state.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().resolved()
                .map(r -> c.getValue().kind() == Alert.Kind.REBOOTED
                        ? Messages.get("alert.state.noted")
                        : Messages.get("alert.state.resolved", WHEN.format(r)))
                .orElse(Messages.get("alert.state.active"))));
        state.setPrefWidth(170);
        table.getColumns().setAll(List.of(when, branch, what, state));
        table.setRowFactory(t -> new TableRow<>() {

            @Override
            protected void updateItem(Alert item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("log-warning");
                if (!empty && item != null && item.active()) {
                    getStyleClass().add("log-warning"); // still lasting: amber, like warnings in the router log
                }
            }
        });
        table.getItems().setAll(newestFirst);

        Label help = new Label(Messages.get("alert.help"));
        help.getStyleClass().add("muted");
        help.setWrapText(true);
        help.setMaxWidth(720);
        VBox content = new VBox(8, help, table);
        content.setPadding(new Insets(8, 4, 4, 4));

        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(workspace.window());
        dialog.setTitle(NetPilotApp.NAME);
        dialog.setHeaderText(Messages.get("alert.title"));
        dialog.getDialogPane().getButtonTypes().setAll(
                new ButtonType(Messages.get("button.close"), ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.getDialogPane().setContent(content);
        Styling.apply(dialog.getDialogPane());
        dialog.setResizable(true);
        dialog.showAndWait();
    }
}
