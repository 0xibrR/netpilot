package org.netpilot.controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.model.ReceiptSettings;
import org.netpilot.service.ReceiptContent;

/**
 * The receipt of a payment from a point of sale: printed on the receipt printer chosen in Card
 * design, or shown on screen when there is none.
 */
final class PaymentReceipt {

    private static final System.Logger LOG = System.getLogger(PaymentReceipt.class.getName());
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private PaymentReceipt() {
    }

    /**
     * What the receipt says, in NetPilot's language.
     *
     * @param balanceLabel "still owes" or "credit"
     * @param id           the payment's id; its first characters are the receipt number
     */
    static ReceiptContent content(String network, String reseller, String amount, String balanceLabel,
            String balance, String id, LocalDateTime at, String note, String takenBy) {
        List<ReceiptContent.Line> lines = new ArrayList<>();
        if (network != null && !network.isBlank()) {
            lines.add(new ReceiptContent.Title(network));
        }
        lines.add(new ReceiptContent.Note(Messages.get("payment.receipt.title")));
        lines.add(new ReceiptContent.Rule());
        lines.add(new ReceiptContent.Code(Messages.get("payment.receipt.amount"), amount));
        lines.add(new ReceiptContent.Rule());
        lines.add(new ReceiptContent.Field(Messages.get("payment.receipt.from"), reseller));
        lines.add(new ReceiptContent.Field(balanceLabel, balance));
        lines.add(new ReceiptContent.Field(Messages.get("payment.receipt.when"), at.format(WHEN)));
        lines.add(new ReceiptContent.Field(Messages.get("payment.receipt.number"), number(id)));
        lines.add(new ReceiptContent.Field(Messages.get("payment.receipt.by"), takenBy));
        if (note != null && !note.isBlank()) {
            lines.add(new ReceiptContent.Note(note));
        }
        return new ReceiptContent(lines, Messages.isArabic());
    }

    /** A short receipt number from the payment id: letters and digits only, 8 at most. */
    static String number(String id) {
        String clean = id == null ? "" : id.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return clean.length() > 8 ? clean.substring(0, 8) : clean;
    }

    /** Prints the receipt in the background, or shows it when no receipt printer is chosen. */
    static void deliver(Workspace workspace, ReceiptContent content) {
        deliver(workspace, content, Messages.get("payment.receipt.title"));
    }

    /** The same for any receipt; {@code title} heads the window when it is shown on screen. */
    static void deliver(Workspace workspace, ReceiptContent content, String title) {
        ReceiptSettings settings = workspace.settings().receipt();
        WritableImage receipt = ReceiptPrinter.render(content, settings, Optional.empty());
        if (settings.printer().isEmpty()) {
            show(workspace, receipt, title);
            return;
        }
        Task<Void> task = new Task<>() {

            @Override
            protected Void call() {
                ReceiptPrinting.forPlatform(org.netpilot.platform.PlatformInfo.current()).print(List.of(receipt),
                        settings);
                return null;
            }
        };
        task.setOnSucceeded(e -> workspace.toast("✓ " + Messages.get("payment.receipt.printed")));
        task.setOnFailed(e -> {
            LOG.log(System.Logger.Level.WARNING, "Payment receipt not printed", task.getException());
            // the payment is saved; the receipt is shown so it can still be handed over
            show(workspace, receipt, title);
        });
        Thread thread = new Thread(task, "netpilot-payment-receipt");
        thread.setDaemon(true);
        thread.start();
    }

    private static void show(Workspace workspace, WritableImage receipt, String title) {
        ImageView view = new ImageView(receipt);
        view.setPreserveRatio(true);
        view.setFitWidth(Math.min(receipt.getWidth(), 360));
        ScrollPane scroll = new ScrollPane(view);
        scroll.setPrefViewportHeight(Math.min(receipt.getHeight() * 360 / Math.max(1, receipt.getWidth()), 520));
        scroll.setStyle("-fx-background: white;");
        VBox content = new VBox(10, scroll);
        content.setAlignment(Pos.TOP_CENTER);
        content.setPadding(new Insets(8));
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(workspace.window());
        dialog.setTitle(NetPilotApp.NAME);
        dialog.setHeaderText(title);
        dialog.getDialogPane().getButtonTypes()
                .setAll(new ButtonType(Messages.get("button.ok"), ButtonBar.ButtonData.OK_DONE));
        dialog.getDialogPane().setContent(content);
        Styling.apply(dialog.getDialogPane());
        dialog.showAndWait();
    }
}
