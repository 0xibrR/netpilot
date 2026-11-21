package org.netpilot.controller;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.model.CardDetails;
import org.netpilot.model.DataSize;
import org.netpilot.model.RouterDuration;

/**
 * One card's window, like WinBox's hotspot user: General, Limits and Statistics. Returns the card
 * as edited; the caller sends only what changed.
 */
final class CardEditor {

    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    private CardEditor() {
    }

    /**
     * Why the name cannot change, when it cannot.
     *
     * @param nameLock empty when the card may be renamed, else the reason shown under the name
     * @param handedOver the card is with a point of sale (changing its package is warned about)
     * @param reset      sets the counters back to zero on the router, then reads the card again
     */
    record Options(List<String> servers, List<String> profiles, Optional<String> nameLock, boolean handedOver,
            Callable<CardDetails> reset) {
    }

    static Optional<CardDetails> show(Window owner, CardDetails card, Options options) {
        // ---- General
        CheckBox enabled = new CheckBox(Messages.get("card.edit.enabled"));
        enabled.setSelected(!card.disabled());
        TextField comment = new TextField(card.comment());
        ComboBox<String> server = editableBox(options.servers(), card.server());
        TextField name = new TextField(card.name());
        Label nameNote = new Label();
        nameNote.getStyleClass().add("muted");
        wrapped(nameNote);
        options.nameLock().ifPresent(reason -> {
            name.setEditable(false);
            name.setDisable(true);
            nameNote.setText(reason);
        });
        PasswordField password = new PasswordField();
        password.setText(card.password());
        TextField shownPassword = new TextField(card.password());
        shownPassword.setManaged(false);
        shownPassword.setVisible(false);
        shownPassword.textProperty().bindBidirectional(password.textProperty());
        ToggleButton show = new ToggleButton(Messages.get("card.edit.showPassword"));
        show.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        show.selectedProperty().addListener((obs, was, on) -> {
            shownPassword.setVisible(on);
            shownPassword.setManaged(on);
            password.setVisible(!on);
            password.setManaged(!on);
        });
        HBox passwordRow = new HBox(6, password, shownPassword, show);
        HBox.setHgrow(password, Priority.ALWAYS);
        HBox.setHgrow(shownPassword, Priority.ALWAYS);
        ComboBox<String> profile = editableBox(options.profiles(), card.profile());
        Label profileNote = new Label(options.handedOver() ? Messages.get("card.edit.profileHandedOver") : "");
        profileNote.getStyleClass().add("muted");
        wrapped(profileNote);
        TextField address = new TextField(card.address());
        address.setPromptText(Messages.get("card.edit.address.prompt"));
        TextField mac = new TextField(card.macAddress());
        mac.setPromptText(Messages.get("card.edit.mac.prompt"));

        GridPane general = grid();
        int r = 0;
        general.add(enabled, 1, r++);
        general.addRow(r++, label("card.edit.comment"), comment);
        general.addRow(r++, label("card.edit.server"), server);
        general.addRow(r++, label("card.edit.name"), name);
        if (options.nameLock().isPresent()) {
            general.add(nameNote, 1, r++);
        }
        general.addRow(r++, label("card.edit.password"), passwordRow);
        general.addRow(r++, label("card.edit.profile"), profile);
        if (options.handedOver()) {
            general.add(profileNote, 1, r++);
        }
        general.addRow(r++, label("card.edit.address"), address);
        general.addRow(r, label("card.edit.mac"), mac);

        // ---- Limits
        TextField uptime = new TextField(card.limitUptime());
        uptime.setPromptText(Messages.get("card.edit.uptime.prompt"));
        TextField data = new TextField();
        data.setTextFormatter(new TextFormatter<String>(c -> c.getControlNewText().matches("\\d{0,6}(\\.\\d{0,2})?")
                ? c
                : null));
        ComboBox<String> unit = new ComboBox<>();
        unit.getItems().setAll("MB", "GB");
        if (card.limitBytes() > 0 && card.limitBytes() % GIB == 0) {
            data.setText(String.valueOf(card.limitBytes() / GIB));
            unit.setValue("GB");
        } else if (card.limitBytes() > 0) {
            data.setText(trim(card.limitBytes() / (double) MIB));
            unit.setValue("MB");
        } else {
            unit.setValue("GB");
        }
        data.setPromptText(Messages.get("card.edit.data.prompt"));
        HBox dataRow = new HBox(6, data, unit);
        HBox.setHgrow(data, Priority.ALWAYS);
        Label limitsHelp = new Label(Messages.get("card.edit.limits.help"));
        limitsHelp.getStyleClass().add("muted");
        limitsHelp.setWrapText(true);
        limitsHelp.setMaxWidth(380);
        GridPane limits = grid();
        limits.addRow(0, label("card.edit.uptime"), uptime);
        limits.addRow(1, label("card.edit.data"), dataRow);
        limits.add(limitsHelp, 0, 2, 2, 1);

        // ---- Statistics
        Label used = new Label();
        Label down = new Label();
        Label up = new Label();
        Runnable showCounters = () -> {
        };
        CardDetails[] current = {card};
        showCounters = () -> {
            used.setText(RouterDuration.clock(current[0].uptime()));
            down.setText(DataSize.format(current[0].bytesOut()));
            up.setText(DataSize.format(current[0].bytesIn()));
        };
        showCounters.run();
        Button reset = new Button(Messages.get("card.edit.reset"), Icons.of(Icons.REFRESH));
        reset.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        Label resetProblem = new Label();
        resetProblem.getStyleClass().add("status-error");
        Runnable redraw = showCounters;
        reset.setOnAction(e -> {
            if (!Dialogs.confirm(owner, Messages.get("card.edit.reset.title", card.name()),
                    Messages.get("card.edit.reset.body"))) {
                return;
            }
            reset.setDisable(true);
            Task<CardDetails> task = new Task<>() {

                @Override
                protected CardDetails call() throws Exception {
                    return options.reset().call();
                }
            };
            task.setOnSucceeded(done -> {
                reset.setDisable(false);
                current[0] = task.getValue();
                redraw.run();
            });
            task.setOnFailed(done -> {
                reset.setDisable(false);
                resetProblem.setText(String.valueOf(task.getException().getMessage()));
            });
            Thread thread = new Thread(task, "netpilot-card-reset");
            thread.setDaemon(true);
            thread.start();
        });
        GridPane stats = grid();
        stats.addRow(0, label("card.edit.used"), used);
        stats.addRow(1, label("card.edit.download"), down);
        stats.addRow(2, label("card.edit.upload"), up);
        stats.add(new VBox(8, reset, resetProblem), 0, 3, 2, 1);

        TabPane tabs = new TabPane(new Tab(Messages.get("card.edit.tab.general"), general),
                new Tab(Messages.get("card.edit.tab.limits"), limits),
                new Tab(Messages.get("card.edit.tab.stats"), stats));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Label problem = new Label();
        problem.getStyleClass().add("status-error");
        problem.setWrapText(true);
        VBox content = new VBox(10, tabs, problem);
        content.setPrefWidth(460);

        Dialog<CardDetails> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(NetPilotApp.NAME);
        dialog.setHeaderText(Messages.get("card.edit.title", card.name()));
        ButtonType save = new ButtonType(Messages.get("card.edit.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes()
                .setAll(save, new ButtonType(Messages.get("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.getDialogPane().setContent(content);
        Styling.apply(dialog.getDialogPane());
        java.util.function.Supplier<CardDetails> edited = () -> new CardDetails(card.routerId(), name.getText(),
                password.getText(), server.getEditor().getText(), profile.getEditor().getText(), comment.getText(),
                !enabled.isSelected(), address.getText(), mac.getText(), uptime.getText(),
                bytes(data.getText(), unit.getValue()), current[0].uptime(), current[0].bytesIn(),
                current[0].bytesOut());
        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            List<String> problems = edited.get().problems();
            if (!problems.isEmpty()) {
                problem.setText(String.join("\n", problems));
                e.consume();
            }
        });
        dialog.setResultConverter(button -> button == save ? edited.get() : null);
        return dialog.showAndWait();
    }

    /** Bytes from what was typed: 0 for empty (no limit). */
    static long bytes(String typed, String unit) {
        if (typed == null || typed.isBlank()) {
            return 0;
        }
        double value = Double.parseDouble(typed);
        return Math.round(value * ("GB".equals(unit) ? GIB : MIB));
    }

    private static String trim(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static ComboBox<String> editableBox(List<String> items, String value) {
        ComboBox<String> box = new ComboBox<>();
        box.getItems().setAll(items);
        if (!value.isEmpty() && !box.getItems().contains(value)) {
            box.getItems().addFirst(value);
        }
        box.setEditable(true);
        box.setValue(value);
        box.getEditor().setText(value);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    /**
     * A note under a field that wraps onto more lines instead of ending in "…": a grid measures a
     * wrapping label as one long line unless it has a width to wrap at.
     */
    private static void wrapped(Label note) {
        note.setWrapText(true);
        note.setMaxWidth(320);
        note.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(14, 6, 6, 6));
        javafx.scene.layout.ColumnConstraints labels = new javafx.scene.layout.ColumnConstraints();
        labels.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        javafx.scene.layout.ColumnConstraints fields = new javafx.scene.layout.ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().setAll(labels, fields);
        return grid;
    }

    private static Label label(String key) {
        return new Label(Messages.get(key));
    }
}
