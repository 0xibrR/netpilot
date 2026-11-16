package org.netpilot.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.service.PinVault;

/**
 * The small windows of app protection: typing a PIN, choosing a new one (typed twice), using the
 * recovery code and showing a new recovery code. PINs are kept in {@code char[]} and never logged.
 */
final class PinDialogs {

    private PinDialogs() {
    }

    /** A field that takes digits only, at most {@link PinVault#MAX_DIGITS}. */
    static PasswordField pinField() {
        PasswordField field = new PasswordField();
        field.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText()
                .matches("\\d{0," + PinVault.MAX_DIGITS + "}") ? change : null));
        field.setPrefColumnCount(10);
        field.setPromptText(Messages.get("lock.pin.prompt"));
        return field;
    }

    /** Asks for one PIN, e.g. the owner's before a sensitive change. */
    static Optional<char[]> ask(Window owner, String header, String hint) {
        PasswordField pin = pinField();
        Label text = new Label(hint);
        text.setWrapText(true);
        text.setMaxWidth(360);
        VBox content = new VBox(10, text, pin);
        content.setPadding(new Insets(6));
        Dialog<char[]> dialog = dialog(owner, header, content);
        ButtonType ok = (ButtonType) dialog.getDialogPane().getButtonTypes().getFirst();
        dialog.getDialogPane().lookupButton(ok).disableProperty()
                .bind(pin.textProperty().length().lessThan(PinVault.MIN_DIGITS));
        dialog.setResultConverter(button -> button == ok ? pin.getText().toCharArray() : null);
        Platform.runLater(pin::requestFocus);
        return dialog.showAndWait();
    }

    /** A new PIN typed twice; empty when cancelled. Mismatches are refused before closing. */
    static Optional<char[]> choose(Window owner, String header, String hint) {
        PasswordField first = pinField();
        PasswordField again = pinField();
        Label error = new Label();
        error.getStyleClass().add("status-error");
        Label text = new Label(hint);
        text.setWrapText(true);
        text.setMaxWidth(380);
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label(Messages.get("lock.pin.new")), first);
        grid.addRow(1, new Label(Messages.get("lock.pin.again")), again);
        VBox content = new VBox(10, text, grid, error);
        content.setPadding(new Insets(6));
        Dialog<char[]> dialog = dialog(owner, header, content);
        ButtonType ok = (ButtonType) dialog.getDialogPane().getButtonTypes().getFirst();
        dialog.getDialogPane().lookupButton(ok).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (first.getText().length() < PinVault.MIN_DIGITS) {
                error.setText(Messages.get("lock.error.pinFormat", PinVault.MIN_DIGITS, PinVault.MAX_DIGITS));
                e.consume();
            } else if (!first.getText().equals(again.getText())) {
                error.setText(Messages.get("lock.error.mismatch"));
                e.consume();
            }
        });
        dialog.setResultConverter(button -> button == ok ? first.getText().toCharArray() : null);
        Platform.runLater(first::requestFocus);
        return dialog.showAndWait();
    }

    /** The recovery code and a new owner PIN; empty when cancelled. */
    record Recovery(String code, char[] newOwner) {
    }

    static Optional<Recovery> recovery(Window owner) {
        TextField code = new TextField();
        code.setPromptText("XXXX-XXXX-XXXX");
        PasswordField first = pinField();
        PasswordField again = pinField();
        Label error = new Label();
        error.getStyleClass().add("status-error");
        Label text = new Label(Messages.get("lock.recover.hint"));
        text.setWrapText(true);
        text.setMaxWidth(400);
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label(Messages.get("lock.recover.code")), code);
        grid.addRow(1, new Label(Messages.get("lock.pin.newOwner")), first);
        grid.addRow(2, new Label(Messages.get("lock.pin.again")), again);
        VBox content = new VBox(10, text, grid, error);
        content.setPadding(new Insets(6));
        Dialog<Recovery> dialog = dialog(owner, Messages.get("lock.recover.title"), content);
        ButtonType ok = (ButtonType) dialog.getDialogPane().getButtonTypes().getFirst();
        dialog.getDialogPane().lookupButton(ok).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (code.getText().isBlank()) {
                error.setText(Messages.get("lock.error.wrongCode"));
                e.consume();
            } else if (first.getText().length() < PinVault.MIN_DIGITS) {
                error.setText(Messages.get("lock.error.pinFormat", PinVault.MIN_DIGITS, PinVault.MAX_DIGITS));
                e.consume();
            } else if (!first.getText().equals(again.getText())) {
                error.setText(Messages.get("lock.error.mismatch"));
                e.consume();
            }
        });
        dialog.setResultConverter(button -> button == ok
                ? new Recovery(code.getText(), first.getText().toCharArray())
                : null);
        Platform.runLater(code::requestFocus);
        return dialog.showAndWait();
    }

    /** Shows a recovery code once, to be written down; it can be selected and copied. */
    static void showCode(Window owner, String code) {
        TextField shown = new TextField(PinVault.formatCode(code));
        shown.setEditable(false);
        shown.getStyleClass().add("recovery-code");
        shown.setPrefColumnCount(16);
        Label text = new Label(Messages.get("lock.code.body"));
        text.setWrapText(true);
        text.setMaxWidth(400);
        VBox content = new VBox(12, text, shown);
        content.setPadding(new Insets(6));
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(NetPilotApp.NAME);
        dialog.setHeaderText(Messages.get("lock.code.title"));
        dialog.getDialogPane().getButtonTypes()
                .setAll(new ButtonType(Messages.get("lock.code.done"), ButtonBar.ButtonData.OK_DONE));
        dialog.getDialogPane().setContent(content);
        Styling.apply(dialog.getDialogPane());
        dialog.showAndWait();
    }

    /** The user's words for what the PIN store refused. */
    static String explain(RuntimeException e) {
        String reason = String.valueOf(e.getMessage());
        if (reason.startsWith("wait:")) {
            return waitText(Instant.parse(reason.substring(5)));
        }
        return switch (reason) {
            case "pin-length", "pin-digits" -> Messages.get("lock.error.pinFormat", PinVault.MIN_DIGITS,
                    PinVault.MAX_DIGITS);
            case "same-as-cashier" -> Messages.get("lock.error.sameAsCashier");
            case "wrong-owner-pin" -> Messages.get("lock.error.wrongOwner");
            case "wrong-recovery-code" -> Messages.get("lock.error.wrongCode");
            default -> reason;
        };
    }

    /** "Too many wrong tries: try again in 02:30". */
    static String waitText(Instant until) {
        long seconds = Math.max(1, Duration.between(Instant.now(), until).toSeconds() + 1);
        return Messages.get("lock.error.wait", String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60,
                seconds % 60));
    }

    static void clear(char[] secret) {
        if (secret != null) {
            Arrays.fill(secret, '\0');
        }
    }

    private static <T> Dialog<T> dialog(Window owner, String header, javafx.scene.Node content) {
        Dialog<T> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(NetPilotApp.NAME);
        dialog.setHeaderText(header);
        dialog.setGraphic(Icons.of(Icons.LOCK));
        ButtonType ok = new ButtonType(Messages.get("button.ok"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes()
                .setAll(ok, new ButtonType(Messages.get("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.getDialogPane().setContent(content);
        Styling.apply(dialog.getDialogPane());
        return dialog;
    }
}
