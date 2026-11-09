package org.netpilot.controller;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;
import org.netpilot.AppContext;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.model.BatchTag;
import org.netpilot.model.LedgerEvent;
import org.netpilot.model.Money;
import org.netpilot.model.Reseller;
import org.netpilot.model.RouterUser;
import org.netpilot.service.Csv;
import org.netpilot.service.ReportService;
import org.netpilot.service.ResellerAccounts;
import org.netpilot.service.ResellerAccounts.Card;
import org.netpilot.service.ResellerAccounts.Statement;
import org.netpilot.service.router.RouterClient;

/**
 * "Points of sale" page: who has which cards, what they sold, what they paid and what they still
 * owe, with the full history of each one. Everything comes from the ledger on this computer, so
 * the page works without a router; only handing over and taking back cards need one.
 */
public class ResellersController implements Page {

    private static final System.Logger LOG = System.getLogger(ResellersController.class.getName());
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private final AppContext context;
    private Workspace workspace;
    private RouterClient client;

    @FXML
    private Button refreshButton;
    @FXML
    private Button addButton;
    @FXML
    private Button editButton;
    @FXML
    private Button handoverButton;
    @FXML
    private Button returnButton;
    @FXML
    private Button paymentButton;
    @FXML
    private Button voidButton;
    @FXML
    private Button exportButton;
    @FXML
    private TableView<Statement> resellersTable;
    @FXML
    private TableColumn<Statement, String> nameColumn;
    @FXML
    private TableColumn<Statement, String> givenColumn;
    @FXML
    private TableColumn<Statement, String> soldColumn;
    @FXML
    private TableColumn<Statement, String> leftColumn;
    @FXML
    private TableColumn<Statement, String> valueColumn;
    @FXML
    private TableColumn<Statement, String> commissionColumn;
    @FXML
    private TableColumn<Statement, String> paidColumn;
    @FXML
    private TableColumn<Statement, String> owesColumn;
    @FXML
    private TableColumn<Statement, String> creditColumn;
    @FXML
    private Label historyTitle;
    @FXML
    private TableView<LedgerEvent> eventsTable;
    @FXML
    private TableColumn<LedgerEvent, String> whenColumn;
    @FXML
    private TableColumn<LedgerEvent, String> kindColumn;
    @FXML
    private TableColumn<LedgerEvent, String> cardColumn;
    @FXML
    private TableColumn<LedgerEvent, String> profileColumn;
    @FXML
    private TableColumn<LedgerEvent, String> amountColumn;
    @FXML
    private TableColumn<LedgerEvent, String> noteColumn;

    public ResellersController(AppContext context) {
        this.context = context;
    }

    @FXML
    private void initialize() {
        nameColumn.setCellValueFactory(c -> text(c.getValue().reseller().name()));
        givenColumn.setCellValueFactory(c -> text(String.valueOf(c.getValue().given())));
        soldColumn.setCellValueFactory(c -> text(String.valueOf(c.getValue().sold())));
        leftColumn.setCellValueFactory(c -> text(String.valueOf(c.getValue().left())));
        valueColumn.setCellValueFactory(c -> text(c.getValue().format(c.getValue().chargedValue())));
        commissionColumn.setCellValueFactory(c -> text(c.getValue().format(c.getValue().commission())));
        paidColumn.setCellValueFactory(c -> text(c.getValue().format(c.getValue().paid())));
        // never a negative amount owed: money paid in advance shows as the shop's credit
        owesColumn.setCellValueFactory(c -> text(c.getValue().format(c.getValue().due())));
        creditColumn.setCellValueFactory(c -> text(c.getValue().credit().signum() == 0
                ? "—"
                : c.getValue().format(c.getValue().credit())));

        whenColumn.setCellValueFactory(c -> text(c.getValue().at().format(WHEN)));
        kindColumn.setCellValueFactory(c -> text(Messages.get("ledger.kind." + c.getValue().kind().name())));
        cardColumn.setCellValueFactory(c -> text(c.getValue().card()));
        profileColumn.setCellValueFactory(c -> text(c.getValue().profile()));
        amountColumn.setCellValueFactory(c -> text(amount(c.getValue())));
        noteColumn.setCellValueFactory(c -> text(c.getValue().note()));

        var none = resellersTable.getSelectionModel().selectedItemProperty().isNull();
        for (Button b : List.of(editButton, paymentButton, exportButton)) {
            b.disableProperty().bind(none);
        }
        voidButton.disableProperty().bind(eventsTable.getSelectionModel().selectedItemProperty().isNull());
        resellersTable.getSelectionModel().selectedItemProperty().addListener((obs, old, s) -> showHistory(s));

        addButton.setGraphic(Icons.of(Icons.PLUS));
        editButton.setGraphic(Icons.of(Icons.SETTINGS));
        handoverButton.setGraphic(Icons.of(Icons.SEND));
        returnButton.setGraphic(Icons.of(Icons.UNPLUG));
        paymentButton.setGraphic(Icons.of(Icons.CHECK));
        voidButton.setGraphic(Icons.of(Icons.CROSS));
        exportButton.setGraphic(Icons.of(Icons.DOWNLOAD));
        refreshButton.setGraphic(Icons.of(Icons.REFRESH));
        updateRouterButtons();
    }

    @Override
    public void attach(Workspace shared) {
        this.workspace = shared;
        workspace.router().client().ifPresent(c -> this.client = c);
        updateRouterButtons();
        reload();
    }

    @Override
    public void shown() {
        refreshFromRouter();
    }

    /** Live figures while the page is open: new sales show up without pressing Refresh. */
    @Override
    public void tick() {
        if (++ticks % 3 == 0 && !refreshing) {
            refreshFromRouter(); // every 15 seconds, like the dashboard
        }
    }

    private int ticks;
    private boolean refreshing;

    @Override
    public void routerConnected(RouterClient routerClient, String name) {
        this.client = routerClient;
        updateRouterButtons();
        refreshFromRouter();
    }

    @Override
    public void routerDisconnected() {
        this.client = null;
        updateRouterButtons();
    }

    private void updateRouterButtons() {
        handoverButton.setDisable(client == null);
        returnButton.setDisable(client == null);
    }

    // ------------------------------------------------------------------ data

    @FXML
    private void onRefresh() {
        refreshFromRouter();
    }

    /**
     * Reads the router's cards first, so cards used since the last look are recorded as sales
     * (once each, by the ledger's rules), then shows the figures. Without a router, or when the
     * router cannot be read, the figures already in the ledger are shown.
     */
    private void refreshFromRouter() {
        RouterClient target = client;
        if (target == null) {
            reload();
            return;
        }
        refreshing = true;
        String router = target.endpoint().address();
        Task<Integer> task = new Task<>() {

            @Override
            protected Integer call() throws Exception {
                List<RouterUser> users = target.hotspotUsers();
                // a card on its first login is in the active list before its uptime reaches the user list
                java.util.Set<String> online = target.activeSessions().stream()
                        .map(org.netpilot.model.ActiveSession::user)
                        .collect(java.util.stream.Collectors.toSet());
                context.usageTracker().track(router, users, online, LocalDate.now());
                return online.size();
            }
        };
        task.setOnSucceeded(e -> {
            refreshing = false;
            LOG.log(System.Logger.Level.DEBUG, "Points of sale synced with {0}: {1} card(s) online", router,
                    task.getValue());
            reload();
        });
        task.setOnFailed(e -> {
            refreshing = false;
            // the figures already in the ledger are still shown; the next poll tries again
            LOG.log(System.Logger.Level.WARNING, "Points of sale sync with " + router + " failed", task.getException());
            reload();
        });
        Thread thread = new Thread(task, "netpilot-resellers-sales");
        thread.setDaemon(true);
        thread.start();
    }

    /** Reloads every statement from the ledger and keeps the selected point of sale. */
    private void reload() {
        String selected = Optional.ofNullable(resellersTable.getSelectionModel().getSelectedItem())
                .map(s -> s.reseller().id()).orElse(null);
        run(() -> context.resellerAccounts().statements(), statements -> {
            resellersTable.getItems().setAll(statements);
            statements.stream().filter(s -> s.reseller().id().equals(selected)).findFirst()
                    .ifPresentOrElse(s -> resellersTable.getSelectionModel().select(s),
                            () -> resellersTable.getSelectionModel().selectFirst());
            showHistory(resellersTable.getSelectionModel().getSelectedItem());
        });
    }

    private void showHistory(Statement statement) {
        if (statement == null) {
            historyTitle.setText(Messages.get("reseller.history"));
            eventsTable.getItems().clear();
            return;
        }
        historyTitle.setText(Messages.get("reseller.historyOf", statement.reseller().name()));
        List<LedgerEvent> newestFirst = new ArrayList<>(statement.events());
        java.util.Collections.reverse(newestFirst);
        eventsTable.getItems().setAll(newestFirst);
    }

    private static String amount(LedgerEvent e) {
        return switch (e.kind()) {
            case PAYMENT, PAYMENT_VOID -> new Money(e.amount(), e.currency()).format();
            default -> new Money(e.price(), e.currency()).format() + " (" + Messages.get("reseller.commissionShort")
                    + " " + new Money(e.commission(), e.currency()).format() + ")";
        };
    }

    // ------------------------------------------------------------------ points of sale

    @FXML
    private void onAdd() {
        editor(null).ifPresent(this::save);
    }

    @FXML
    private void onEdit() {
        Statement selected = resellersTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            editor(selected.reseller()).ifPresent(this::save);
        }
    }

    private void save(Reseller reseller) {
        run(() -> context.resellerAccounts().save(reseller), saved -> {
            workspace.toast("✓ " + Messages.get("reseller.saved", saved.name()));
            reload();
        });
    }

    /** Name, phone, commission and when it pays; a change applies to later handovers only. */
    private Optional<Reseller> editor(Reseller existing) {
        TextField name = new TextField(existing == null ? "" : existing.name());
        TextField phone = new TextField(existing == null ? "" : existing.phone());
        ComboBox<Reseller.CommissionType> type = new ComboBox<>();
        type.getItems().setAll(Reseller.CommissionType.values());
        type.setValue(existing == null ? Reseller.CommissionType.PERCENT : existing.commissionType());
        TextField value = new TextField(existing == null ? "0" : existing.commissionValue().toPlainString());
        value.setTextFormatter(new TextFormatter<>(c -> c.getControlNewText().matches("\\d{0,7}(\\.\\d{0,2})?")
                ? c
                : null));
        ComboBox<Reseller.Billing> billing = new ComboBox<>();
        billing.getItems().setAll(Reseller.Billing.values());
        billing.setValue(existing == null ? Reseller.Billing.ON_SALE : existing.billing());
        Label problems = new Label();
        problems.getStyleClass().add("status-error");
        problems.setWrapText(true);
        Label hint = new Label(Messages.get(existing == null ? "reseller.hint.new" : "reseller.hint.change"));
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        hint.setMaxWidth(380);

        GridPane form = form();
        form.addRow(0, new Label(Messages.get("reseller.name")), name);
        form.addRow(1, new Label(Messages.get("reseller.phone")), phone);
        form.addRow(2, new Label(Messages.get("reseller.commissionType")), type);
        form.addRow(3, new Label(Messages.get("reseller.commissionValue")), value);
        form.addRow(4, new Label(Messages.get("reseller.billing")), billing);
        form.add(hint, 0, 5, 2, 1);
        form.add(problems, 0, 6, 2, 1);

        String id = existing == null ? context.resellerAccounts().newId() : existing.id();
        Callable<Reseller> read = () -> new Reseller(id, name.getText(), phone.getText(), type.getValue(),
                new BigDecimal(value.getText().isBlank() ? "0" : value.getText()), billing.getValue());
        return showForm(Messages.get(existing == null ? "reseller.add" : "reseller.edit"), form, problems, () -> {
            Reseller r = read.call();
            List<String> errors = r.validate();
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException(String.join("\n", errors));
            }
            return r;
        });
    }

    // ------------------------------------------------------------------ cards

    /** Picks a batch of unused NetPilot cards from the router and hands it over. */
    @FXML
    private void onHandover() {
        RouterClient target = client;
        if (target == null) {
            return;
        }
        run(() -> new RouterCards(target.hotspotUsers(), target.activeSessions().stream()
                .map(org.netpilot.model.ActiveSession::user).collect(java.util.stream.Collectors.toSet())), read -> {
                    Map<String, List<Card>> batches = unusedBatches(target, read.users(), read.online());
                    if (batches.isEmpty()) {
                        Dialogs.info(window(), Messages.get("handover.title"), Messages.get("handover.noBatches"));
                        return;
                    }
                    List<String> labels = new ArrayList<>(batches.keySet());
                    ChoiceDialog<String> choose = new ChoiceDialog<>(labels.getFirst(), labels);
                    choose.initOwner(window());
                    choose.setTitle(NetPilotApp.NAME);
                    choose.setHeaderText(Messages.get("handover.chooseBatch"));
                    choose.setContentText(Messages.get("handover.batch"));
                    Styling.apply(choose.getDialogPane());
                    choose.showAndWait().ifPresent(label -> HandoverFlow.start(workspace, window(), batches.get(label),
                            Optional.ofNullable(resellersTable.getSelectionModel().getSelectedItem())
                                    .map(Statement::reseller).orElse(null),
                            this::reload));
                });
    }

    /** Unsold cards of the selected point of sale go back (e.g. the shop returned them). */
    @FXML
    private void onReturn() {
        Statement selected = resellersTable.getSelectionModel().getSelectedItem();
        RouterClient target = client;
        if (selected == null || target == null) {
            return;
        }
        String router = target.endpoint().address();
        run(() -> {
            Map<String, String> holders = context.resellerAccounts().holders();
            return target.hotspotUsers().stream()
                    .filter(u -> selected.reseller().id().equals(holders.get(ResellerAccounts.key(router, u.name()))))
                    .filter(RouterUser::neverUsed)
                    .map(u -> new Card(router, u.name(), u.profile()))
                    .toList();
        }, cards -> {
            if (cards.isEmpty()) {
                Dialogs.info(window(), Messages.get("reseller.return"), Messages.get("reseller.return.none"));
                return;
            }
            if (!Dialogs.confirm(window(), Messages.get("reseller.return.title", cards.size(),
                    selected.reseller().name()), Messages.get("reseller.return.body"))) {
                return;
            }
            run(() -> context.resellerAccounts().returnCards(cards, Messages.get("reseller.return.note")), done -> {
                workspace.toast("✓ " + Messages.get("reseller.return.done", done.size()));
                reload();
            });
        });
    }

    /** The router's users and the names logged in right now, read together. */
    private record RouterCards(List<RouterUser> users, java.util.Set<String> online) {
    }

    /** Unused NetPilot cards on the router, by batch, labelled with count and package. */
    static Map<String, List<Card>> unusedBatches(RouterClient target, List<RouterUser> users,
            java.util.Set<String> online) {
        String router = target.endpoint().address();
        Map<String, List<Card>> byBatch = new TreeMap<>(java.util.Comparator.reverseOrder());
        for (RouterUser u : users) {
            // a card logged in now is in use even before its uptime reaches the user list
            if (!u.builtIn() && u.neverUsed() && !online.contains(u.name()) && !u.disabled()
                    && BatchTag.parse(u.comment()).isPresent()) {
                byBatch.computeIfAbsent(u.comment(), k -> new ArrayList<>()).add(new Card(router, u.name(),
                        u.profile()));
            }
        }
        Map<String, List<Card>> labelled = new java.util.LinkedHashMap<>();
        byBatch.forEach((batch, cards) -> labelled.put(Messages.get("handover.batchLabel",
                ReportService.batchLabel(batch), cards.size(), cards.getFirst().profile()), cards));
        return labelled;
    }

    // ------------------------------------------------------------------ money

    /** Records money the point of sale paid; any amount, so partial payments are normal. */
    @FXML
    private void onPayment() {
        Statement selected = resellersTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        // made once per window: pressing Save twice still records one payment
        String paymentId = context.resellerAccounts().newId();
        TextField amount = new TextField();
        amount.setPromptText(Messages.get("payment.amount.prompt"));
        amount.setTextFormatter(new TextFormatter<>(c -> c.getControlNewText().matches("\\d{0,9}(\\.\\d{0,2})?")
                ? c
                : null));
        DatePicker day = new DatePicker(LocalDate.now());
        TextField note = new TextField();
        note.setPromptText(Messages.get("payment.note.prompt"));
        Label owes = new Label(selected.credit().signum() > 0
                ? Messages.get("payment.credit", selected.format(selected.credit()))
                : Messages.get("payment.owes", selected.format(selected.due())));
        owes.getStyleClass().add("muted");
        Label problems = new Label();
        problems.getStyleClass().add("status-error");

        GridPane form = form();
        form.add(owes, 0, 0, 2, 1);
        form.addRow(1, new Label(Messages.get("payment.amount")), amount);
        form.addRow(2, new Label(Messages.get("payment.day")), day);
        form.addRow(3, new Label(Messages.get("payment.note")), note);
        form.add(problems, 0, 4, 2, 1);

        String currency = selected.currency().isEmpty() ? priceCurrency() : selected.currency();
        showForm(Messages.get("payment.title", selected.reseller().name()), form, problems, () -> {
            if (amount.getText().isBlank() || new BigDecimal(amount.getText()).signum() <= 0) {
                throw new IllegalArgumentException(Messages.get("payment.validation.amount"));
            }
            return new BigDecimal(amount.getText());
        }).ifPresent(value -> run(() -> context.resellerAccounts().pay(paymentId, selected.reseller(), value,
                currency, day.getValue() == null ? LocalDate.now() : day.getValue(), note.getText()), saved -> {
                    if (saved.isPresent()) {
                        workspace.toast("✓ " + Messages.get("payment.saved", new Money(value, currency).format()));
                    }
                    reload();
                }));
    }

    /** A payment entered by mistake is cancelled by a new entry; the payment stays in the history. */
    @FXML
    private void onVoid() {
        LedgerEvent event = eventsTable.getSelectionModel().getSelectedItem();
        if (event == null) {
            return;
        }
        if (event.kind() != LedgerEvent.Kind.PAYMENT) {
            Dialogs.info(window(), Messages.get("reseller.void"), Messages.get("payment.void.onlyPayments"));
            return;
        }
        String amountText = new Money(event.amount(), event.currency()).format();
        if (!Dialogs.confirm(window(), Messages.get("payment.void.title", amountText),
                Messages.get("payment.void.body"))) {
            return;
        }
        run(() -> context.resellerAccounts().voidPayment(event.id(), Messages.get("payment.void.note")), done -> {
            if (done.isEmpty()) {
                Dialogs.info(window(), Messages.get("reseller.void"), Messages.get("payment.void.already"));
            } else {
                workspace.toast("✓ " + Messages.get("payment.voided", amountText));
            }
            reload();
        });
    }

    /** The selected point of sale's statement and full history as CSV (opens in Excel). */
    @FXML
    private void onExport() {
        Statement s = resellersTable.getSelectionModel().getSelectedItem();
        if (s == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("reseller.export"));
        chooser.setInitialFileName(Messages.get("reseller.export.file", s.reseller().name().replaceAll(
                "[\\\\/:*?\"<>|]", "_"), LocalDate.now()));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File file = chooser.showSaveDialog(window());
        if (file == null) {
            return;
        }
        try (Writer out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            out.write('﻿'); // Excel reads UTF-8 (Arabic) only with a BOM
            out.write(Csv.line(Messages.get("reseller.column.name"), s.reseller().name()) + "\r\n");
            out.write(Csv.line(Messages.get("reseller.column.given"), String.valueOf(s.given())) + "\r\n");
            out.write(Csv.line(Messages.get("reseller.column.sold"), String.valueOf(s.sold())) + "\r\n");
            out.write(Csv.line(Messages.get("reseller.column.billing"), s.reseller().billing().toString()) + "\r\n");
            out.write(Csv.line(Messages.get("reseller.column.value"), s.format(s.salesValue())) + "\r\n");
            out.write(Csv.line(Messages.get("reseller.column.charged"), s.format(s.chargedValue())) + "\r\n");
            out.write(Csv.line(Messages.get("reseller.column.commission"), s.format(s.commission())) + "\r\n");
            out.write(Csv.line(Messages.get("reseller.column.paid"), s.format(s.paid())) + "\r\n");
            out.write(Csv.line(Messages.get("reseller.column.owes"), s.format(s.due())) + "\r\n");
            out.write(Csv.line(Messages.get("reseller.column.credit"), s.format(s.credit())) + "\r\n\r\n");
            out.write(Csv.line(Messages.get("reseller.column.when"), Messages.get("reseller.column.kind"),
                    Messages.get("reseller.column.card"), Messages.get("column.profile"),
                    Messages.get("reseller.column.amount"), Messages.get("reseller.column.note")) + "\r\n");
            for (LedgerEvent e : s.events()) {
                out.write(Csv.line(e.at().format(WHEN), Messages.get("ledger.kind." + e.kind().name()), e.card(),
                        e.profile(), amount(e), e.note()) + "\r\n");
            }
            workspace.toast("✓ " + Messages.get("reseller.exported", file.getName()));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot export the statement", e);
            Dialogs.error(window(), Messages.get("reseller.export"), e.getMessage());
        }
    }

    /** The currency most package prices use, for a point of sale with no history yet. */
    private String priceCurrency() {
        return workspace.settings().voucherTemplate().prices().values().stream()
                .map(Money::parse).flatMap(Optional::stream).map(Money::currency)
                .filter(c -> !c.isEmpty()).findFirst().orElse("");
    }

    // ------------------------------------------------------------------ helpers

    private static ReadOnlyStringWrapper text(String value) {
        return new ReadOnlyStringWrapper(value);
    }

    private static GridPane form() {
        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.setPadding(new Insets(10, 4, 4, 4));
        return form;
    }

    /** A Save / Cancel dialog that stays open, showing the problem, while {@code read} fails. */
    private <T> Optional<T> showForm(String header, GridPane form, Label problems, Callable<T> read) {
        Dialog<T> dialog = new Dialog<>();
        dialog.initOwner(window());
        dialog.setTitle(NetPilotApp.NAME);
        dialog.setHeaderText(header);
        ButtonType save = new ButtonType(Messages.get("button.save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(Messages.get("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(save, cancel);
        dialog.getDialogPane().setContent(form);
        Styling.apply(dialog.getDialogPane());
        List<T> result = new ArrayList<>(1);
        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            try {
                result.clear();
                result.add(read.call());
            } catch (Exception problem) {
                problems.setText(problem instanceof NumberFormatException
                        ? Messages.get("payment.validation.amount")
                        : problem.getMessage());
                e.consume();
            }
        });
        dialog.setResultConverter(button -> button == save && !result.isEmpty() ? result.getFirst() : null);
        return dialog.showAndWait();
    }

    private <T> void run(Callable<T> work, Consumer<T> onSuccess) {
        Task<T> task = new Task<>() {

            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> {
            LOG.log(System.Logger.Level.WARNING, "Points of sale action failed", task.getException());
            Dialogs.error(window(), Messages.get("reseller.error"), String.valueOf(task.getException().getMessage()));
        });
        Thread thread = new Thread(task, "netpilot-resellers");
        thread.setDaemon(true);
        thread.start();
    }

    private javafx.stage.Window window() {
        return workspace.window();
    }
}
