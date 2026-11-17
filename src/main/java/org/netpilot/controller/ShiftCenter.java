package org.netpilot.controller;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.model.Money;
import org.netpilot.model.Role;
import org.netpilot.model.Shift;
import org.netpilot.repository.AppPaths;
import org.netpilot.service.ReceiptContent;
import org.netpilot.service.ShiftBook;

/**
 * The cash drawer: opening a shift with the cash in it, taking cash out, and closing it with the
 * cash counted, which prints a closing receipt. Pages that take money tell it ({@link #changed}),
 * so the expected cash on Home and in the top bar is always current.
 */
final class ShiftCenter {

    private static final System.Logger LOG = System.getLogger(ShiftCenter.class.getName());
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private final Workspace workspace;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private ShiftBook book;

    ShiftCenter(Workspace workspace) {
        this.workspace = workspace;
    }

    ShiftBook book() {
        if (book == null) {
            book = new ShiftBook(AppPaths.shiftsFile(), workspace.context().resellerAccounts(),
                    Clock.systemDefaultZone());
        }
        return book;
    }

    Optional<Shift> current() {
        return book().current();
    }

    Optional<ShiftBook.Figures> figures() {
        try {
            Optional<Shift> open = current();
            return open.isPresent() ? Optional.of(book().figures(open.get())) : Optional.empty();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot read the shift figures", e);
            return Optional.empty();
        }
    }

    void onChange(Runnable listener) {
        listeners.add(listener);
    }

    /** Money came in or went out: Home and the top bar show the new expected cash. */
    void changed() {
        Platform.runLater(() -> listeners.forEach(Runnable::run));
    }

    /**
     * Before the cashier sells or takes a payment: when the owner requires a shift and none is
     * open, offers to open one. Always true for the owner.
     */
    boolean readyForCash() {
        if (workspace.role() != Role.CASHIER || !workspace.context().access().vault().requireShift()
                || current().isPresent()) {
            return true;
        }
        ButtonType open = new ButtonType(Messages.get("shift.open"), ButtonBar.ButtonData.OK_DONE);
        if (Dialogs.choose(workspace.window(), Messages.get("shift.required.title"),
                Messages.get("shift.required.body"), open).isPresent()) {
            openShift();
        }
        return current().isPresent();
    }

    void openShift() {
        if (current().isPresent()) {
            return;
        }
        amount(Messages.get("shift.open.title"), Messages.get("shift.open.hint"), Messages.get("shift.openingCash"),
                false, true).ifPresent(a -> {
                    try {
                        book().open(by(), a.amount(), currency());
                        workspace.toast("✓ " + Messages.get("shift.opened"));
                        changed();
                    } catch (IOException | RuntimeException e) {
                        failed(Messages.get("shift.open.title"), e);
                    }
                });
    }

    void withdraw() {
        if (current().isEmpty()) {
            return;
        }
        amount(Messages.get("shift.withdraw.title"), Messages.get("shift.withdraw.hint"),
                Messages.get("shift.withdraw.amount"), true, false).ifPresent(a -> {
                    try {
                        book().withdraw(by(), a.amount(), a.note());
                        workspace.toast("✓ " + Messages.get("shift.withdrawn", money(a.amount())));
                        changed();
                    } catch (IOException | RuntimeException e) {
                        failed(Messages.get("shift.withdraw.title"), e);
                    }
                });
    }

    /**
     * Closes the open shift: the cash is counted first, then the expected cash and the difference
     * are shown and the closing receipt is printed.
     */
    void closeShift() {
        if (current().isEmpty()) {
            return;
        }
        amount(Messages.get("shift.close.title"), Messages.get("shift.close.hint"), Messages.get("shift.counted"),
                true, true).ifPresent(a -> {
                    try {
                        Shift closed = book().close(by(), a.amount(), a.note());
                        Shift.Closing c = closed.closing().orElseThrow();
                        BigDecimal difference = c.difference();
                        String result = difference.signum() == 0
                                ? Messages.get("shift.result.even")
                                : Messages.get(difference.signum() < 0 ? "shift.result.short" : "shift.result.over",
                                        money(difference.abs()));
                        Dialogs.info(workspace.window(), Messages.get("shift.closed"),
                                Messages.get("shift.result", money(c.expected()), money(c.counted())) + "\n" + result);
                        PaymentReceipt.deliver(workspace, receipt(closed), Messages.get("shift.receipt.title"));
                        changed();
                    } catch (IOException | RuntimeException e) {
                        failed(Messages.get("shift.close.title"), e);
                    }
                });
    }

    /** The closing receipt. */
    ReceiptContent receipt(Shift shift) {
        Shift.Closing c = shift.closing().orElseThrow();
        List<ReceiptContent.Line> lines = new ArrayList<>();
        String network = workspace.settings().voucherTemplate().networkName();
        if (!network.isBlank()) {
            lines.add(new ReceiptContent.Title(network));
        }
        lines.add(new ReceiptContent.Note(Messages.get("shift.receipt.title")));
        lines.add(new ReceiptContent.Rule());
        lines.add(new ReceiptContent.Field(Messages.get("shift.column.opened"), shift.opened().format(TIME)));
        lines.add(new ReceiptContent.Field(Messages.get("shift.column.closed"), c.at().format(TIME)));
        lines.add(new ReceiptContent.Field(Messages.get("shift.column.by"), roleName(c.by())));
        lines.add(new ReceiptContent.Rule());
        lines.add(new ReceiptContent.Field(Messages.get("shift.openingCash"), money(shift.openingCash())));
        lines.add(new ReceiptContent.Field(Messages.get("shift.column.sales"), money(c.sales())));
        lines.add(new ReceiptContent.Field(Messages.get("shift.column.payments"), money(c.payments())));
        lines.add(new ReceiptContent.Field(Messages.get("shift.column.withdrawn"), money(shift.withdrawn())));
        lines.add(new ReceiptContent.Field(Messages.get("shift.column.expected"), money(c.expected())));
        lines.add(new ReceiptContent.Code(Messages.get("shift.counted"), money(c.counted())));
        lines.add(new ReceiptContent.Field(Messages.get("shift.column.difference"), money(c.difference())));
        if (!c.note().isBlank()) {
            lines.add(new ReceiptContent.Note(c.note()));
        }
        return new ReceiptContent(lines, Messages.isArabic());
    }

    static String roleName(String role) {
        return switch (role) {
            case "OWNER" -> Messages.get("role.owner");
            case "CASHIER" -> Messages.get("role.cashier");
            default -> role;
        };
    }

    private String by() {
        return workspace.role().name();
    }

    /** The currency of the package prices. */
    String currency() {
        return workspace.settings().voucherTemplate().prices().values().stream().map(Money::parse)
                .flatMap(Optional::stream).map(Money::currency).filter(c -> !c.isEmpty()).findFirst().orElse("");
    }

    String money(BigDecimal amount) {
        String currency = current().map(Shift::currency).filter(c -> !c.isEmpty()).orElse(currency());
        return new Money(amount, currency).format();
    }

    private record Amount(BigDecimal amount, String note) {
    }

    /** Asks for an amount and, when {@code withNote}, a note or reason. */
    private Optional<Amount> amount(String title, String hint, String label, boolean withNote, boolean zeroAllowed) {
        TextField amount = new TextField();
        amount.setTextFormatter(new TextFormatter<String>(c -> c.getControlNewText().matches("\\d{0,9}(\\.\\d{0,2})?")
                ? c
                : null));
        amount.setPromptText("0");
        TextField note = new TextField();
        note.setPromptText(Messages.get("shift.note.prompt"));
        Label text = new Label(hint);
        text.setWrapText(true);
        text.setMaxWidth(380);
        Label problem = new Label();
        problem.getStyleClass().add("status-error");
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label(label), amount);
        if (withNote) {
            grid.addRow(1, new Label(Messages.get("shift.note")), note);
        }
        VBox content = new VBox(10, text, grid, problem);
        content.setPadding(new Insets(6));
        Dialog<Amount> dialog = new Dialog<>();
        dialog.initOwner(workspace.window());
        dialog.setTitle(NetPilotApp.NAME);
        dialog.setHeaderText(title);
        ButtonType ok = new ButtonType(Messages.get("button.ok"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes()
                .setAll(ok, new ButtonType(Messages.get("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.getDialogPane().setContent(content);
        Styling.apply(dialog.getDialogPane());
        dialog.getDialogPane().lookupButton(ok).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            BigDecimal value = amount.getText().isBlank() ? null : new BigDecimal(amount.getText());
            if (value == null || (!zeroAllowed && value.signum() == 0)) {
                problem.setText(Messages.get("shift.validation.amount"));
                e.consume();
            }
        });
        dialog.setResultConverter(button -> button == ok
                ? new Amount(new BigDecimal(amount.getText()), note.getText())
                : null);
        Platform.runLater(amount::requestFocus);
        return dialog.showAndWait();
    }

    private void failed(String title, Exception e) {
        LOG.log(System.Logger.Level.WARNING, "Shift action failed", e);
        Dialogs.error(workspace.window(), title, String.valueOf(e.getMessage()));
    }
}
