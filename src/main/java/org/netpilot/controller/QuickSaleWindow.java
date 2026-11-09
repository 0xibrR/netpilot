package org.netpilot.controller;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.model.HotspotUser;

/**
 * A quick sale without a receipt printer: the card's receipt (number, password, QR code) is shown
 * large on screen, for the customer to type or scan. The sale counts only when the user confirms
 * it was handed over; cancelling puts the card back in stock.
 */
final class QuickSaleWindow {

    private QuickSaleWindow() {
    }

    /** @return whether the card was handed over (sold) */
    static boolean show(Window owner, Image receipt, HotspotUser card) {
        ImageView picture = new ImageView(receipt);
        picture.setPreserveRatio(true);
        double width = Math.min(380, receipt.getWidth());
        picture.setFitWidth(width);
        ScrollPane scroll = new ScrollPane(picture);
        scroll.setFitToWidth(true);
        // the buttons must stay on screen: the receipt scrolls when the screen is short (laptops)
        double shown = receipt.getHeight() * width / receipt.getWidth();
        double room = javafx.stage.Screen.getPrimary().getVisualBounds().getHeight() - 330;
        scroll.setPrefViewportHeight(Math.max(160, Math.min(shown, room)));
        scroll.setStyle("-fx-background: white;"); // the receipt is paper: white in every theme
        Label code = new Label(card.username());
        code.getStyleClass().add("stat-value");
        Label hint = new Label(Messages.get("quickSale.window.hint"));
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        hint.setMaxWidth(380);
        VBox content = new VBox(10, code, scroll, hint);
        content.setAlignment(Pos.TOP_CENTER);
        content.setPadding(new Insets(8));

        Dialog<Boolean> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(NetPilotApp.NAME);
        dialog.setHeaderText(Messages.get("quickSale.window.title", card.profile()));
        ButtonType sold = new ButtonType(Messages.get("quickSale.window.sold"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(Messages.get("quickSale.window.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(sold, cancel);
        dialog.getDialogPane().setContent(content);
        Styling.apply(dialog.getDialogPane());
        dialog.setResultConverter(button -> button == sold);
        return dialog.showAndWait().orElse(false);
    }
}
