package org.netpilot.controller;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.netpilot.AppContext;
import org.netpilot.i18n.Messages;
import org.netpilot.model.Money;
import org.netpilot.model.Role.Permission;
import org.netpilot.model.Shift;
import org.netpilot.service.ShiftBook;

/**
 * Cashier shifts (owner only): when each opened and closed, who opened it, the cash expected and
 * counted, and the difference in red when cash was missing. A shift left open (forgotten, or the
 * computer was turned off) can be closed here.
 */
public class ShiftsController implements Page {

    private static final System.Logger LOG = System.getLogger(ShiftsController.class.getName());

    private final AppContext context;
    private Workspace workspace;
    private final Map<String, ShiftBook.Figures> figures = new HashMap<>();

    @FXML
    private TableView<Shift> shiftsTable;
    @FXML
    private TableColumn<Shift, String> openedColumn;
    @FXML
    private TableColumn<Shift, String> closedColumn;
    @FXML
    private TableColumn<Shift, String> byColumn;
    @FXML
    private TableColumn<Shift, String> openingColumn;
    @FXML
    private TableColumn<Shift, String> salesColumn;
    @FXML
    private TableColumn<Shift, String> paymentsColumn;
    @FXML
    private TableColumn<Shift, String> withdrawnColumn;
    @FXML
    private TableColumn<Shift, String> expectedColumn;
    @FXML
    private TableColumn<Shift, String> countedColumn;
    @FXML
    private TableColumn<Shift, String> differenceColumn;
    @FXML
    private TableColumn<Shift, String> noteColumn;
    @FXML
    private Label summaryLabel;
    @FXML
    private Button refreshButton;
    @FXML
    private Button detailsButton;
    @FXML
    private Button receiptButton;
    @FXML
    private Button closeButton;
    @FXML
    private Button exportButton;

    public ShiftsController(AppContext context) {
        this.context = context;
    }

    @FXML
    private void initialize() {
        openedColumn.setCellValueFactory(c -> text(c.getValue().opened().format(ShiftCenter.TIME)));
        closedColumn.setCellValueFactory(c -> text(c.getValue().closing()
                .map(x -> x.at().format(ShiftCenter.TIME)).orElse(Messages.get("shift.stillOpen"))));
        byColumn.setCellValueFactory(c -> text(ShiftCenter.roleName(c.getValue().openedBy())));
        openingColumn.setCellValueFactory(c -> money(c.getValue(), c.getValue().openingCash()));
        salesColumn.setCellValueFactory(c -> money(c.getValue(), figuresOf(c.getValue()).sales()));
        paymentsColumn.setCellValueFactory(c -> money(c.getValue(), figuresOf(c.getValue()).payments()));
        withdrawnColumn.setCellValueFactory(c -> money(c.getValue(), c.getValue().withdrawn()));
        expectedColumn.setCellValueFactory(c -> money(c.getValue(), figuresOf(c.getValue()).expected()));
        countedColumn.setCellValueFactory(c -> c.getValue().closing().map(x -> money(c.getValue(), x.counted()))
                .orElse(text("—")));
        differenceColumn.setCellValueFactory(c -> c.getValue().closing()
                .map(x -> money(c.getValue(), x.difference())).orElse(text("—")));
        // cash missing in red, cash over in amber
        differenceColumn.setCellFactory(column -> new TableCell<>() {

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("shift-short", "shift-over");
                Shift shift = empty || getTableRow() == null ? null : getTableRow().getItem();
                setText(empty ? null : item);
                if (shift != null && shift.closing().isPresent()) {
                    int sign = shift.closing().get().difference().signum();
                    if (sign < 0) {
                        getStyleClass().add("shift-short");
                    } else if (sign > 0) {
                        getStyleClass().add("shift-over");
                    }
                }
            }
        });
        noteColumn.setCellValueFactory(c -> text(c.getValue().closing().map(Shift.Closing::note).orElse("")));
        var none = shiftsTable.getSelectionModel().selectedItemProperty().isNull();
        detailsButton.disableProperty().bind(none);
        receiptButton.disableProperty().bind(none.or(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> shiftsTable.getSelectionModel().getSelectedItem() == null
                        || shiftsTable.getSelectionModel().getSelectedItem().open(),
                shiftsTable.getSelectionModel().selectedItemProperty())));
        refreshButton.setGraphic(Icons.of(Icons.REFRESH));
        exportButton.setGraphic(Icons.of(Icons.DOWNLOAD));
        closeButton.setGraphic(Icons.of(Icons.CHECK));
    }

    @Override
    public void attach(Workspace shared) {
        this.workspace = shared;
        shared.shifts().onChange(this::reload);
        reload();
    }

    @Override
    public void shown() {
        reload();
    }

    @FXML
    private void onRefresh() {
        reload();
    }

    private void reload() {
        if (workspace == null) {
            return;
        }
        Shift selected = shiftsTable.getSelectionModel().getSelectedItem();
        List<Shift> all = workspace.shifts().book().all();
        figures.clear();
        for (Shift s : all) {
            try {
                figures.put(s.id(), workspace.shifts().book().figures(s));
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Cannot read the figures of a shift", e);
            }
        }
        shiftsTable.getItems().setAll(all);
        if (selected != null) {
            all.stream().filter(s -> s.id().equals(selected.id())).findFirst()
                    .ifPresent(s -> shiftsTable.getSelectionModel().select(s));
        }
        boolean open = all.stream().anyMatch(Shift::open);
        closeButton.setDisable(!open);
        BigDecimal missing = all.stream().flatMap(s -> s.closing().stream()).map(Shift.Closing::difference)
                .filter(d -> d.signum() < 0).reduce(BigDecimal.ZERO, BigDecimal::add).abs();
        summaryLabel.setText(Messages.get("shift.summary", all.size(),
                new Money(missing, workspace.shifts().currency()).format()));
    }

    private ShiftBook.Figures figuresOf(Shift shift) {
        return figures.getOrDefault(shift.id(), new ShiftBook.Figures(shift.openingCash(), BigDecimal.ZERO,
                BigDecimal.ZERO, shift.withdrawn(), shift.currency()));
    }

    /** The withdrawals of the selected shift. */
    @FXML
    private void onDetails() {
        Shift s = shiftsTable.getSelectionModel().getSelectedItem();
        if (s == null || !workspace.allowed(Permission.REPORTS)) {
            return;
        }
        String withdrawals = s.withdrawals().isEmpty()
                ? Messages.get("shift.noWithdrawals")
                : s.withdrawals().stream().map(w -> w.at().format(ShiftCenter.TIME) + "  ·  "
                        + new Money(w.amount(), s.currency()).format() + "  ·  " + ShiftCenter.roleName(w.by())
                        + (w.reason().isBlank() ? "" : "  ·  " + w.reason()))
                        .collect(Collectors.joining("\n"));
        Dialogs.info(workspace.window(), Messages.get("shift.details.title", s.opened().format(ShiftCenter.TIME)),
                withdrawals);
    }

    /** Prints (or shows) the closing receipt again. */
    @FXML
    private void onReceipt() {
        Shift s = shiftsTable.getSelectionModel().getSelectedItem();
        if (s == null || s.open() || !workspace.allowed(Permission.REPORTS)) {
            return;
        }
        PaymentReceipt.deliver(workspace, workspace.shifts().receipt(s), Messages.get("shift.receipt.title"));
    }

    /** Closes a shift left open, with the cash the owner counts now. */
    @FXML
    private void onClose() {
        if (!workspace.allowed(Permission.REPORTS)) {
            return;
        }
        workspace.shifts().closeShift();
    }

    @FXML
    private void onExport() {
        if (!workspace.allowed(Permission.REPORTS) || shiftsTable.getItems().isEmpty()) {
            return;
        }
        List<Shift> all = List.copyOf(shiftsTable.getItems());
        ExportFiles.choose(workspace.window(), Messages.get("nav.SHIFTS"),
                Messages.get("shift.export.file", LocalDate.now()), false).ifPresent(target -> {
                    try {
                        org.netpilot.service.ShiftExport.write(all, this::figuresOf, ShiftCenter::roleName,
                                target.file());
                        context.hostServices().showDocument(target.file().toUri().toString());
                    } catch (IOException e) {
                        LOG.log(System.Logger.Level.WARNING, "Cannot export the shifts", e);
                        Dialogs.error(workspace.window(), Messages.get("nav.SHIFTS"), e.getMessage());
                    }
                });
    }

    private static ReadOnlyStringWrapper text(String value) {
        return new ReadOnlyStringWrapper(value);
    }

    private static ReadOnlyStringWrapper money(Shift shift, BigDecimal value) {
        return new ReadOnlyStringWrapper(new Money(value, shift.currency()).format());
    }
}
