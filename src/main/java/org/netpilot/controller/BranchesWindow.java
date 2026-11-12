package org.netpilot.controller;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.model.Branch;
import org.netpilot.model.RouterEndpoint;

/**
 * "Manage branches": the routers to switch between, with a name and a colour each. A branch's
 * password, when given, goes to the encrypted credential store like the Connect dialog's
 * "remember"; it is never shown again or written anywhere else.
 */
final class BranchesWindow {

    private static final System.Logger LOG = System.getLogger(BranchesWindow.class.getName());

    private BranchesWindow() {
    }

    /** @param changed runs after any change, so the top bar shows it */
    static void show(Workspace workspace, Runnable changed) {
        ListView<Branch> list = new ListView<>();
        list.setPrefSize(440, 260);
        list.setPlaceholder(new Label(Messages.get("branch.empty")));
        list.setCellFactory(view -> new ListCell<>() {

            @Override
            protected void updateItem(Branch item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setGraphic(new Circle(6, Color.web(item.color())));
                    setText(item.name() + "   " + item.address());
                }
            }
        });
        Runnable reload = () -> list.getItems().setAll(workspace.settings().router().branches());
        reload.run();

        Button add = new Button(Messages.get("branch.add"), Icons.of(Icons.PLUS));
        Button edit = new Button(Messages.get("branch.edit"), Icons.of(Icons.SETTINGS));
        Button remove = new Button(Messages.get("branch.remove"), Icons.of(Icons.CROSS));
        Button up = new Button("▲");
        Button down = new Button("▼");
        var none = list.getSelectionModel().selectedItemProperty().isNull();
        edit.disableProperty().bind(none);
        remove.disableProperty().bind(none);
        up.disableProperty().bind(none);
        down.disableProperty().bind(none);

        add.setOnAction(e -> editor(workspace, Optional.empty()).ifPresent(b -> {
            workspace.updateSettings(s -> s.withRouter(s.router().withBranch(b)));
            reload.run();
            changed.run();
        }));
        Runnable editSelected = () -> {
            Branch selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                editor(workspace, Optional.of(selected)).ifPresent(b -> {
                    workspace.updateSettings(s -> s.withRouter(s.router().withBranch(b)));
                    reload.run();
                    changed.run();
                });
            }
        };
        edit.setOnAction(e -> editSelected.run());
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                editSelected.run();
            }
        });
        remove.setOnAction(e -> {
            Branch selected = list.getSelectionModel().getSelectedItem();
            if (selected != null && Dialogs.confirm(workspace.window(),
                    Messages.get("branch.remove.title", selected.name()), Messages.get("branch.remove.body"))) {
                workspace.updateSettings(s -> s.withRouter(s.router().withoutBranch(selected.id())));
                forgetPassword(workspace, selected.endpoint());
                reload.run();
                changed.run();
            }
        });
        up.setOnAction(e -> move(workspace, list, -1, reload, changed));
        down.setOnAction(e -> move(workspace, list, 1, reload, changed));

        HBox buttons = new HBox(6, add, edit, remove, up, down);
        buttons.setAlignment(Pos.CENTER_LEFT);
        Label help = new Label(Messages.get("branch.help"));
        help.getStyleClass().add("muted");
        help.setWrapText(true);
        help.setMaxWidth(440);
        VBox content = new VBox(10, help, list, buttons);
        content.setPadding(new Insets(8, 4, 4, 4));

        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(workspace.window());
        dialog.setTitle(NetPilotApp.NAME);
        dialog.setHeaderText(Messages.get("branch.manage.title"));
        dialog.getDialogPane().getButtonTypes().setAll(
                new ButtonType(Messages.get("button.close"), ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.getDialogPane().setContent(content);
        Styling.apply(dialog.getDialogPane());
        dialog.showAndWait();
    }

    private static void move(Workspace workspace, ListView<Branch> list, int by, Runnable reload, Runnable changed) {
        int at = list.getSelectionModel().getSelectedIndex();
        int to = at + by;
        if (at < 0 || to < 0 || to >= list.getItems().size()) {
            return;
        }
        java.util.List<Branch> order = new java.util.ArrayList<>(workspace.settings().router().branches());
        Branch moved = order.remove(at);
        order.add(to, moved);
        workspace.updateSettings(s -> {
            org.netpilot.model.RouterSettings r = s.router();
            for (Branch b : r.branches()) {
                r = r.withoutBranch(b.id());
            }
            for (Branch b : order) {
                r = r.withBranch(b);
            }
            return s.withRouter(r);
        });
        reload.run();
        list.getSelectionModel().select(to);
        changed.run();
    }

    /** Asks for a branch's details; empty when cancelled. */
    private static Optional<Branch> editor(Workspace workspace, Optional<Branch> existing) {
        RouterEndpoint start = existing.map(Branch::endpoint).orElse(RouterEndpoint.defaults());
        TextField name = new TextField(existing.map(Branch::name).orElse(""));
        name.setPromptText(Messages.get("branch.name.prompt"));
        TextField host = new TextField(start.host());
        Spinner<Integer> port = new Spinner<>(1, 65_535, start.port());
        port.setEditable(true);
        port.setPrefWidth(110);
        CheckBox https = new CheckBox(Messages.get("branch.https"));
        https.setSelected(start.https());
        https.selectedProperty().addListener((obs, old, on) -> {
            int current = port.getValue() == null ? 0 : port.getValue();
            if (current == RouterEndpoint.DEFAULT_HTTP_PORT || current == RouterEndpoint.DEFAULT_HTTPS_PORT) {
                port.getValueFactory()
                        .setValue(on ? RouterEndpoint.DEFAULT_HTTPS_PORT : RouterEndpoint.DEFAULT_HTTP_PORT);
            }
        });
        TextField user = new TextField(start.username());
        PasswordField password = new PasswordField();
        password.setPromptText(Messages.get(existing.isPresent() && workspace.context().passwordVault()
                .has(start) ? "branch.password.kept" : "branch.password.prompt"));
        ToggleGroup colors = new ToggleGroup();
        HBox colorRow = new HBox(6);
        for (String c : Branch.COLORS) {
            ToggleButton dot = new ToggleButton();
            dot.setGraphic(new Circle(8, Color.web(c)));
            dot.setUserData(c);
            dot.setToggleGroup(colors);
            dot.getStyleClass().add("color-dot");
            dot.setSelected(c.equals(existing.map(Branch::color).orElse(nextColor(workspace))));
            colorRow.getChildren().add(dot);
        }
        Label problem = new Label();
        problem.getStyleClass().add("status-error");
        problem.setWrapText(true);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.setPadding(new Insets(10, 4, 4, 4));
        form.addRow(0, new Label(Messages.get("branch.name")), name);
        form.addRow(1, new Label(Messages.get("branch.host")), host);
        form.addRow(2, new Label(Messages.get("branch.port")), new HBox(10, port, https));
        form.addRow(3, new Label(Messages.get("branch.user")), user);
        form.addRow(4, new Label(Messages.get("branch.password")), password);
        form.addRow(5, new Label(Messages.get("branch.color")), colorRow);
        form.add(problem, 0, 6, 2, 1);

        Dialog<Branch> dialog = new Dialog<>();
        dialog.initOwner(workspace.window());
        dialog.setTitle(NetPilotApp.NAME);
        dialog.setHeaderText(Messages.get(existing.isPresent() ? "branch.edit.title" : "branch.add.title"));
        ButtonType save = new ButtonType(Messages.get("button.ok"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(Messages.get("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(save, cancel);
        dialog.getDialogPane().setContent(form);
        Styling.apply(dialog.getDialogPane());

        Branch[] result = new Branch[1];
        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            try {
                port.increment(0); // a typed port counts without pressing Enter
                RouterEndpoint endpoint = new RouterEndpoint(host.getText(), port.getValue(), https.isSelected(),
                        user.getText());
                String id = existing.map(Branch::id).orElse(UUID.randomUUID().toString());
                boolean taken = workspace.settings().router().branches().stream()
                        .anyMatch(b -> !b.id().equals(id) && b.address().equals(endpoint.address()));
                if (taken) {
                    throw new IllegalArgumentException(Messages.get("branch.duplicate", endpoint.address()));
                }
                String color = colors.getSelectedToggle() == null
                        ? nextColor(workspace)
                        : (String) colors.getSelectedToggle().getUserData();
                result[0] = new Branch(id, name.getText(), endpoint, color);
                if (existing.isPresent() && !existing.get().address().equals(endpoint.address())) {
                    Dialogs.info(workspace.window(), Messages.get("branch.moved.title"),
                            Messages.get("branch.moved.body", existing.get().address(), endpoint.address()));
                }
                char[] typed = password.getText().toCharArray();
                password.clear();
                if (typed.length > 0) {
                    savePassword(workspace, endpoint, typed);
                }
            } catch (IllegalArgumentException invalid) {
                problem.setText(invalid.getMessage());
                e.consume();
            }
        });
        dialog.setResultConverter(button -> button == save ? result[0] : null);
        return dialog.showAndWait();
    }

    private static String nextColor(Workspace workspace) {
        int count = workspace.settings().router().branches().size();
        return Branch.COLORS.get(count % Branch.COLORS.size());
    }

    private static void savePassword(Workspace workspace, RouterEndpoint endpoint, char[] password) {
        try {
            workspace.context().passwordVault().save(endpoint, password);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot remember the branch password: {0}", e.getMessage());
            Dialogs.error(workspace.window(), Messages.get("branch.password.failed"), String.valueOf(e.getMessage()));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static void forgetPassword(Workspace workspace, RouterEndpoint endpoint) {
        try {
            workspace.context().passwordVault().forget(endpoint);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot forget the branch password: {0}", e.getMessage());
        }
    }
}
