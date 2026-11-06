package org.netpilot.controller;

import java.util.Optional;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import org.netpilot.NetPilotApp;

/**
 * Small helpers for consistent alert dialogs.
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
        Optional<ButtonType> answer = alert(Alert.AlertType.CONFIRMATION, owner, header, message).showAndWait();
        return answer.isPresent() && answer.get() == ButtonType.OK;
    }

    static Alert alert(Alert.AlertType type, Window owner, String header, String message) {
        Alert alert = new Alert(type, message);
        alert.initOwner(owner);
        alert.setTitle(NetPilotApp.NAME);
        alert.setHeaderText(header);
        alert.getDialogPane().getStylesheets().add(NetPilotApp.resource("css/styles.css").toExternalForm());
        return alert;
    }
}
