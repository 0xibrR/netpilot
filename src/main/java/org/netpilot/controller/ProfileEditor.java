package org.netpilot.controller;

import java.util.List;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Window;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.model.HotspotProfile;
import org.netpilot.model.RateLimit;

/**
 * Dialog to add or change a hotspot profile: name, download and upload speed in the user's own
 * numbers (10 Mbps or 1000 Mbps alike), and how many devices may share a card.
 */
final class ProfileEditor {

    private static final List<String> UNITS = List.of("k", "M", "G");

    private ProfileEditor() {
    }

    /**
     * @param existing the profile to change, or {@code null} to add one
     * @return the edited profile (with the same router id), or empty when cancelled
     */
    static Optional<HotspotProfile> show(Window owner, HotspotProfile existing) {
        HotspotProfile start = existing == null ? new HotspotProfile("", "", "", 1) : existing;
        RateLimit speed = start.speed();

        TextField name = new TextField(start.name());
        name.setPromptText(Messages.get("profile.name.prompt"));
        name.setDisable(start.builtIn()); // RouterOS keeps the name "default"
        Speed download = new Speed(speed.download());
        Speed upload = new Speed(speed.upload());
        Spinner<Integer> shared = new Spinner<>(1, 1000, start.sharedUsers());
        shared.setEditable(true);
        Label problems = new Label();
        problems.getStyleClass().add("status-error");
        problems.setWrapText(true);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.setPadding(new Insets(10, 4, 4, 4));
        form.addRow(0, new Label(Messages.get("profile.name")), name);
        form.addRow(1, new Label(Messages.get("profile.download")), download.box());
        form.addRow(2, new Label(Messages.get("profile.upload")), upload.box());
        form.addRow(3, new Label(Messages.get("profile.sharedUsers")), shared);
        Label hint = new Label(Messages.get("profile.hint"));
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        hint.setMaxWidth(380);
        form.add(hint, 0, 4, 2, 1);
        form.add(problems, 0, 5, 2, 1);

        Dialog<HotspotProfile> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(NetPilotApp.NAME);
        dialog.setHeaderText(Messages.get(existing == null ? "profile.add.title" : "profile.edit.title"));
        ButtonType save = new ButtonType(Messages.get("button.save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(Messages.get("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(save, cancel);
        dialog.getDialogPane().setContent(form);
        Styling.apply(dialog.getDialogPane());

        // keep the dialog open while the input is not valid
        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            List<String> errors = profile(start, name, download, upload, shared).validate();
            if (!errors.isEmpty()) {
                problems.setText(String.join("\n", errors));
                e.consume();
            }
        });
        dialog.setResultConverter(button -> button == save ? profile(start, name, download, upload, shared) : null);
        javafx.application.Platform.runLater(name::requestFocus);
        return dialog.showAndWait();
    }

    private static HotspotProfile profile(HotspotProfile start, TextField name, Speed download, Speed upload,
            Spinner<Integer> shared) {
        String limit = new RateLimit(upload.value(), download.value()).format();
        return new HotspotProfile(start.routerId(), name.getText(), limit, shared.getValue());
    }

    /** A number and its unit; an empty number means no limit. */
    private static final class Speed {

        private final TextField number = new TextField();
        private final ComboBox<String> unit = new ComboBox<>();

        Speed(String rate) {
            number.setPrefColumnCount(7);
            number.setPromptText(Messages.get("profile.noLimit"));
            number.setTextFormatter(new TextFormatter<>(c -> c.getControlNewText().matches("\\d{0,6}(\\.\\d{0,2})?")
                    ? c
                    : null));
            unit.getItems().setAll(UNITS);
            unit.setValue("M");
            if (!rate.isEmpty()) {
                char last = rate.charAt(rate.length() - 1);
                boolean hasUnit = Character.isLetter(last);
                number.setText(hasUnit ? rate.substring(0, rate.length() - 1) : rate);
                unit.setValue(hasUnit ? String.valueOf(last) : "k");
                if (!hasUnit) {
                    // plain bits per second: show in k when it divides evenly, else keep as typed
                    long bits = Long.parseLong(rate.replaceAll("\\..*", ""));
                    if (bits % 1000 == 0) {
                        number.setText(String.valueOf(bits / 1000));
                    } else {
                        unit.setValue("");
                    }
                }
            }
        }

        HBox box() {
            HBox box = new HBox(6, number, unit, new Label(Messages.get("profile.bps")));
            box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            return box;
        }

        String value() {
            String n = number.getText().strip();
            return n.isEmpty() ? "" : n + (unit.getValue() == null ? "" : unit.getValue());
        }
    }
}
