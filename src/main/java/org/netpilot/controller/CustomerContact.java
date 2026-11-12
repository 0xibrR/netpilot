package org.netpilot.controller;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.model.Customer;
import org.netpilot.model.DataSize;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.NotificationSettings;
import org.netpilot.model.PhoneNumber;
import org.netpilot.model.RouterDuration;
import org.netpilot.service.CustomerMessages;
import org.netpilot.service.QrCodeService;
import org.netpilot.service.Reminders;

/**
 * Customers' numbers and WhatsApp messages: asking for a number, linking it to a card, and opening
 * WhatsApp on the customer's chat with the card's details or a reminder written in. The user
 * presses Send in WhatsApp; nothing is sent by NetPilot itself.
 */
final class CustomerContact {

    private static final System.Logger LOG = System.getLogger(CustomerContact.class.getName());
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** What the number window ended with. */
    sealed interface Answer {
    }

    /** A valid number, international digits. */
    record Number(String phone) implements Answer {
    }

    /** The user chose to unlink the card's number. */
    record Unlink() implements Answer {
    }

    private CustomerContact() {
    }

    /**
     * Asks for a customer's number for a card.
     *
     * @param current the number already linked, if any (then "Unlink" is offered)
     * @return empty when cancelled
     */
    static Optional<Answer> ask(Workspace workspace, String card, Optional<Customer> current) {
        NotificationSettings settings = workspace.settings().notifications();
        TextField field = new TextField(current.map(Customer::displayPhone).orElse(""));
        field.setPromptText(Messages.get("notify.phone.prompt"));
        field.setPrefColumnCount(16);
        javafx.scene.control.ComboBox<org.netpilot.model.CallingCodes.Entry> country = new javafx.scene.control.ComboBox<>();
        CountryBox.setUpRemembering(country, workspace);
        javafx.scene.layout.HBox number = new javafx.scene.layout.HBox(8, country, field);
        number.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label problem = new Label();
        problem.getStyleClass().add("status-error");
        problem.setWrapText(true);
        Label hint = new Label(Messages.get("notify.phone.hint"));
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        hint.setMaxWidth(420);
        VBox box = new VBox(8, number, hint, problem);
        box.setPadding(new Insets(8, 4, 4, 4));

        Dialog<Answer> dialog = new Dialog<>();
        dialog.initOwner(workspace.window());
        dialog.setTitle(NetPilotApp.NAME);
        dialog.setHeaderText(Messages.get("notify.phone.title", card));
        ButtonType save = new ButtonType(Messages.get("notify.phone.save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(Messages.get("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType unlink = new ButtonType(Messages.get("notify.phone.unlink"), ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().setAll(save, cancel);
        if (current.isPresent()) {
            dialog.getDialogPane().getButtonTypes().add(unlink);
        }
        dialog.getDialogPane().setContent(box);
        Styling.apply(dialog.getDialogPane());
        // a wrong number keeps the window open with the reason, instead of closing and losing it
        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (PhoneNumber.normalize(field.getText(), CountryBox.code(country, settings.countryCode())).isEmpty()) {
                problem.setText(Messages.get("notify.phone.invalid"));
                e.consume();
            }
        });
        dialog.setResultConverter(button -> {
            if (button == save) {
                return PhoneNumber.normalize(field.getText(), CountryBox.code(country, settings.countryCode()))
                        .map(Number::new).orElse(null);
            }
            return button == unlink ? new Unlink() : null;
        });
        javafx.application.Platform.runLater(field::requestFocus);
        return dialog.showAndWait();
    }

    /** Saves the number for a card; a failure is shown and logged. */
    static boolean link(Workspace workspace, String router, String card, String phone) {
        try {
            workspace.context().customerRepository()
                    .save(new Customer(router, card, phone, LocalDateTime.now(), Optional.empty()));
            return true;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot save the customer's number", e);
            Dialogs.error(workspace.window(), Messages.get("notify.saveFailed"), String.valueOf(e.getMessage()));
            return false;
        }
    }

    static void unlink(Workspace workspace, String router, String card) {
        try {
            workspace.context().customerRepository().remove(router, card);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot remove the customer's number", e);
            Dialogs.error(workspace.window(), Messages.get("notify.saveFailed"), String.valueOf(e.getMessage()));
        }
    }

    /** Opens WhatsApp with the card's details for the customer. */
    static void sendCard(Workspace workspace, HotspotUser card, String phone) {
        String message = CustomerMessages.card(workspace.settings().notifications(),
                new CustomerMessages.Fields(network(workspace), card.username(), card.password(), card.profile(),
                        loginLink(workspace, card), "", ""));
        open(workspace, phone, message);
    }

    /** Opens WhatsApp with the "ends soon" reminder, and marks the customer as reminded. */
    static void sendReminder(Workspace workspace, Reminders.Due due) {
        String left = due.timeLeft().map(RouterDuration::clock).orElse("");
        if (due.reason() == Reminders.Reason.DATA_LOW && due.bytesLeft().isPresent()) {
            left = DataSize.format(Math.max(0, due.bytesLeft().getAsLong()));
        }
        String message = CustomerMessages.reminder(workspace.settings().notifications(),
                new CustomerMessages.Fields(network(workspace), due.card().name(), "", due.card().profile(), "",
                        due.endsAt().map(WHEN::format).orElse(""), left));
        open(workspace, due.customer().phone(), message);
        try {
            workspace.context().customerRepository().save(due.customer().remindedAt(LocalDateTime.now()));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot mark the reminder as sent", e);
        }
    }

    /** "1:30:00 left" or "300 MiB left" for the reminders list. */
    static String describe(Reminders.Due due) {
        return switch (due.reason()) {
            case ENDS_SOON -> Messages.get("notify.reason.ends", due.endsAt().map(WHEN::format).orElse(""));
            case TIME_LOW -> Messages.get("notify.reason.time",
                    due.timeLeft().map(d -> RouterDuration.clock(d.isNegative() ? Duration.ZERO : d)).orElse(""));
            case DATA_LOW -> Messages.get("notify.reason.data",
                    DataSize.format(Math.max(0, due.bytesLeft().orElse(0))));
        };
    }

    private static String network(Workspace workspace) {
        String name = workspace.settings().voucherTemplate().networkName();
        return name.isBlank() ? NetPilotApp.NAME : name;
    }

    private static String loginLink(Workspace workspace, HotspotUser card) {
        String url = workspace.settings().loginUrl();
        return QrCodeService.isValidLoginUrl(url) ? new QrCodeService().loginLink(url, card) : "";
    }

    private static void open(Workspace workspace, String phone, String message) {
        LOG.log(System.Logger.Level.INFO, "Opening WhatsApp for a customer ({0} characters)", message.length());
        workspace.context().hostServices().showDocument(CustomerMessages.whatsAppLink(phone, message));
    }
}
