package org.netpilot.controller;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;

/**
 * Small helpers for consistent alert dialogs.
 *
 * <p>Buttons get translated text: JavaFX's own OK/Cancel labels are not available in Arabic.
 */
final class Dialogs {

    private Dialogs() {
    }

    static void error(Window owner, String header, String message) {
        alert(Alert.AlertType.ERROR, owner, header, message).showAndWait();
    }

    static void info(Window owner, String header, String message) {
        alert(Alert.AlertType.INFORMATION, owner, header, message).showAndWait();
    }

    static boolean confirm(Window owner, String header, String message) {
        return alert(Alert.AlertType.CONFIRMATION, owner, header, message).showAndWait()
                .map(answer -> answer.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                .orElse(false);
    }

    static Alert alert(Alert.AlertType type, Window owner, String header, String message) {
        Alert alert = new Alert(type, message);
        alert.initOwner(owner);
        alert.setTitle(NetPilotApp.NAME);
        alert.setHeaderText(header);
        ButtonType ok = new ButtonType(Messages.get("button.ok"), ButtonBar.ButtonData.OK_DONE);
        if (type == Alert.AlertType.CONFIRMATION) {
            alert.getButtonTypes().setAll(ok,
                    new ButtonType(Messages.get("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE));
        } else {
            alert.getButtonTypes().setAll(ok);
        }
        Styling.apply(alert.getDialogPane());
        return alert;
    }
}
