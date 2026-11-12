package org.netpilot.controller;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.netpilot.AppContext;
import org.netpilot.i18n.Messages;
import org.netpilot.model.Money;
import org.netpilot.model.ReportPeriod;
import org.netpilot.model.RouterUser;
import org.netpilot.model.SalesReport;
import org.netpilot.model.UsageRecord;
import org.netpilot.model.VoucherTemplate;
import org.netpilot.service.MonthlyReport;
import org.netpilot.service.MonthlyReportPdf;
import org.netpilot.service.ReportService;
import org.netpilot.service.router.RouterClient;

/**
 * "Reports" window: sales by period, batch and profile, with a revenue-per-day chart.
 *
 * <p>Each refresh reads the router's users once, records newly used cards in the usage history,
 * and builds the report. Changing the period only rebuilds the report locally.
 */
public class ReportsController implements Page {

    private static final System.Logger LOG = System.getLogger(ReportsController.class.getName());
    private static final DateTimeFormatter BATCH_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd");
    private static final int CHART_DAYS = 30;

    private final AppContext context;

    private RouterClient client;
    private Supplier<VoucherTemplate> template;
    private String routerName = "";
    private List<RouterUser> users = List.of();
    private List<UsageRecord> history = List.of();
    private SalesReport report;

    @FXML
    private ComboBox<ReportPeriod> periodBox;
    @FXML
    private ProgressIndicator progressIndicator;
    @FXML
    private Button exportButton;
    @FXML
    private Button monthlyButton;
    @FXML
    private Button refreshButton;
    @FXML
    private Label revenueLabel;
    @FXML
    private Label potentialLabel;
    @FXML
    private Label soldLabel;
    @FXML
    private Label unusedLabel;
    @FXML
    private Label createdLabel;
    @FXML
    private BarChart<String, Number> dailyChart;
    @FXML
    private TableView<SalesReport.BatchRow> batchTable;
    @FXML
    private TableColumn<SalesReport.BatchRow, String> batchColumn;
    @FXML
    private TableColumn<SalesReport.BatchRow, String> batchCreatedColumn;
    @FXML
    private TableColumn<SalesReport.BatchRow, String> batchProfileColumn;
    @FXML
    private TableColumn<SalesReport.BatchRow, Integer> batchCardsColumn;
    @FXML
    private TableColumn<SalesReport.BatchRow, Integer> batchUsedColumn;
    @FXML
    private TableColumn<SalesReport.BatchRow, Integer> batchUnusedColumn;
    @FXML
    private TableColumn<SalesReport.BatchRow, Money> batchRevenueColumn;
    @FXML
    private TableView<SalesReport.ProfileRow> profileTable;
    @FXML
    private TableColumn<SalesReport.ProfileRow, String> profileColumn;
    @FXML
    private TableColumn<SalesReport.ProfileRow, String> profilePriceColumn;
    @FXML
    private TableColumn<SalesReport.ProfileRow, Integer> profileSoldColumn;
    @FXML
    private TableColumn<SalesReport.ProfileRow, Integer> profileUnusedColumn;
    @FXML
    private TableColumn<SalesReport.ProfileRow, Money> profileRevenueColumn;
    @FXML
    private TableColumn<SalesReport.ProfileRow, Long> profileDataColumn;
    @FXML
    private Label warningsLabel;

    private Workspace workspace;

    public ReportsController(AppContext context) {
        this.context = context;
    }

    @Override
    public void attach(Workspace shared) {
        this.workspace = shared;
    }

    @Override
    public void routerConnected(RouterClient routerClient, String routerName) {
        users = List.of();
        report = null;
        // prices are read on each rebuild, so changes in Voucher Design apply after Refresh
        start(routerClient, routerName, () -> workspace.settings().voucherTemplate());
    }

    @Override
    public void routerDisconnected() {
        client = null;
        exportButton.setDisable(true);
        monthlyButton.setDisable(true);
    }

    @FXML
    private void initialize() {
        periodBox.getItems().setAll(ReportPeriod.values());
        periodBox.setValue(ReportPeriod.LAST_30_DAYS);
        periodBox.valueProperty().addListener((obs, old, value) -> rebuild());
        exportButton.setDisable(true);
        monthlyButton.setDisable(true);
        setUpTables();
        setUpBranches();
        monthlyButton.setGraphic(Icons.of(Icons.LOG));
        exportButton.setGraphic(Icons.of(Icons.DOWNLOAD));
    }

    /**
     * @param voucherTemplate current template, read on each rebuild so new prices apply at once
     */
    public void start(RouterClient routerClient, String routerName, Supplier<VoucherTemplate> voucherTemplate) {
        this.client = routerClient;
        this.template = voucherTemplate;
        this.routerName = routerName == null ? "" : routerName;
        onRefresh();
    }

    // ------------------------------------------------------------------ actions

    @FXML
    private void onRefresh() {
        String router = client.endpoint().address();
        Task<Snapshot> task = new Task<>() {

            @Override
            protected Snapshot call() throws Exception {
                List<RouterUser> current = client.hotspotUsers();
                java.util.Set<String> online = client.activeSessions().stream()
                        .map(org.netpilot.model.ActiveSession::user)
                        .collect(java.util.stream.Collectors.toSet());
                List<UsageRecord> updated = context.usageTracker().track(router, current, online, LocalDate.now());
                return new Snapshot(current, updated);
            }
        };
        task.setOnRunning(e -> setBusy(true));
        task.setOnSucceeded(e -> {
            setBusy(false);
            users = task.getValue().users();
            history = task.getValue().history();
            rebuild();
        });
        task.setOnFailed(e -> {
            setBusy(false);
            LOG.log(System.Logger.Level.WARNING, "Cannot build report", task.getException());
            Dialogs.error(stage(), Messages.get("reports.failed"), String.valueOf(task.getException().getMessage()));
        });
        Thread thread = new Thread(task, "netpilot-reports");
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void onExport() {
        if (report == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("reports.exportTitle"));
        chooser.setInitialFileName("netpilot-report-" + LocalDate.now() + ".csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File file = chooser.showSaveDialog(stage());
        if (file == null) {
            return;
        }
        try {
            context.reportExporter().export(report, file.toPath());
            context.hostServices().showDocument(file.toURI().toString());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Report export failed", e);
            Dialogs.error(stage(), Messages.get("error.exportFailed"), e.getMessage());
        }
    }

    /**
     * Monthly PDF report: pick a month and its parts, choose where to save, and the PDF is made in
     * the background from the same figures this page shows, then opened.
     */
    @FXML
    private void onMonthly() {
        if (client == null || template == null) {
            return;
        }
        String routerKey = client.endpoint().address();
        List<java.time.YearMonth> months;
        try {
            months = MonthlyReport.months(routerKey, history, users, context.resellerAccounts().ledgerMonths(),
                    LocalDate.now());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Cannot read the points-of-sale ledger", e);
            Dialogs.error(stage(), Messages.get("monthly.failed", ""), e.getMessage());
            return;
        }
        MonthlyReportDialog.show(stage(), months).ifPresent(choice -> {
            MonthlyReport monthly;
            try {
                monthly = MonthlyReport.build(choice.month(), routerKey, routerName, users, history, template.get(),
                        context.resellerAccounts(), choice.sections(), context.reportService(),
                        java.time.LocalDateTime.now(), workspace.settings().router().branches(),
                        context.stockReadings().all());
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Monthly report failed", e);
                Dialogs.error(stage(), Messages.get("monthly.failed", e.getMessage()), "");
                return;
            }
            if (monthly.empty()) {
                Dialogs.info(stage(), Messages.get("monthly.dialog.title"),
                        Messages.get("monthly.empty", MonthlyReportPdf.monthName(monthly)));
                return;
            }
            FileChooser chooser = new FileChooser();
            chooser.setTitle(Messages.get("monthly.dialog.title"));
            chooser.setInitialFileName("netpilot-report-" + choice.month() + ".pdf");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
            File file = chooser.showSaveDialog(stage());
            if (file != null) {
                writeMonthly(monthly, file.toPath());
            }
        });
    }

    private void writeMonthly(MonthlyReport monthly, java.nio.file.Path file) {
        MonthlyReportPdf pdf = new MonthlyReportPdf(org.netpilot.NetPilotApp.VERSION);
        Task<Void> task = new Task<>() {

            @Override
            protected Void call() throws Exception {
                pdf.write(monthly, file);
                return null;
            }
        };
        task.setOnRunning(e -> setBusy(true));
        task.setOnSucceeded(e -> {
            setBusy(false);
            workspace.toast("✓ " + Messages.get("monthly.saved", file.getFileName()));
            context.hostServices().showDocument(file.toUri().toString());
        });
        task.setOnFailed(e -> {
            setBusy(false);
            LOG.log(System.Logger.Level.ERROR, "Writing the monthly report failed", task.getException());
            Dialogs.error(stage(), Messages.get("monthly.failed", String.valueOf(task.getException().getMessage())),
                    "");
        });
        Thread thread = new Thread(task, "netpilot-monthly-report");
        thread.setDaemon(true);
        thread.start();
    }

    // ------------------------------------------------------------------ showing the report

    private void rebuild() {
        if (client == null) {
            return;
        }
        report = context.reportService().build(client.endpoint().address(), users, history, template.get(),
                periodBox.getValue(), LocalDate.now());
        revenueLabel.setText(report.revenue().format());
        potentialLabel.setText(report.potential().format());
        soldLabel.setText(String.valueOf(report.sold()));
        unusedLabel.setText(String.valueOf(report.unused()));
        createdLabel.setText(String.valueOf(report.created()));
        batchTable.getItems().setAll(report.batches());
        profileTable.getItems().setAll(report.profiles());
        showChart(report);
        warningsLabel.setText(String.join("\n", report.warnings().stream().map(w -> "⚠ " + w).toList()));
        warningsLabel.setVisible(!report.warnings().isEmpty());
        warningsLabel.setManaged(!report.warnings().isEmpty());
        exportButton.setDisable(false);
        monthlyButton.setDisable(false);
        showBranches();
    }

    // ------------------------------------------------------------------ branches

    /** Which sales the page shows: the connected branch's, or every branch's together. */
    enum Scope {
        CURRENT,
        ALL
    }

    @FXML
    private ComboBox<Scope> scopeBox;
    @FXML
    private javafx.scene.control.TabPane reportTabs;
    @FXML
    private javafx.scene.control.Tab batchTab;
    @FXML
    private javafx.scene.control.Tab branchTab;
    @FXML
    private TableView<org.netpilot.service.BranchReport.Line> branchTable;
    @FXML
    private TableColumn<org.netpilot.service.BranchReport.Line, String> branchNameColumn;
    @FXML
    private TableColumn<org.netpilot.service.BranchReport.Line, Integer> branchSoldColumn;
    @FXML
    private TableColumn<org.netpilot.service.BranchReport.Line, Money> branchRevenueColumn;
    @FXML
    private TableColumn<org.netpilot.service.BranchReport.Line, String> branchStockColumn;
    @FXML
    private TableColumn<org.netpilot.service.BranchReport.Line, String> branchReadColumn;

    private void setUpBranches() {
        scopeBox.getItems().setAll(Scope.values());
        scopeBox.setConverter(new javafx.util.StringConverter<>() {

            @Override
            public String toString(Scope scope) {
                return scope == null ? "" : Messages.get("reports.scope." + scope.name());
            }

            @Override
            public Scope fromString(String text) {
                return Scope.CURRENT;
            }
        });
        scopeBox.setValue(Scope.CURRENT);
        scopeBox.valueProperty().addListener((obs, old, scope) -> rebuild());
        branchNameColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name()));
        branchNameColumn.setCellFactory(column -> new javafx.scene.control.TableCell<>() {

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                org.netpilot.service.BranchReport.Line line = empty ? null : getTableRow().getItem();
                setText(empty || item == null ? null : item);
                setGraphic(line == null || line.color().isEmpty()
                        ? null
                        : new javafx.scene.shape.Circle(5, javafx.scene.paint.Color.web(line.color())));
            }
        });
        branchSoldColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().sold()));
        branchRevenueColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().revenue()));
        branchRevenueColumn.setCellFactory(column -> formatted(Money::format));
        branchRevenueColumn.setComparator(java.util.Comparator.comparing(Money::amount));
        branchStockColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().stock()
                .map(s -> Messages.get("reports.stockValue", s.unused(), s.value().format()))
                .orElse("—")));
        branchReadColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().stock()
                .map(s -> BATCH_TIME.format(s.at()))
                .orElse(Messages.get("reports.neverRead"))));
    }

    /**
     * The branches tab, always; and with "All branches" the figures, chart and packages of every
     * branch together. Stock in that view is each branch's last reading.
     */
    private void showBranches() {
        org.netpilot.service.BranchReport all = org.netpilot.service.BranchReport.build(context.reportService(),
                history, workspace.settings().router().branches(), context.stockReadings().all(), template.get(),
                periodBox.getValue(), LocalDate.now());
        java.util.List<org.netpilot.service.BranchReport.Line> rows = new java.util.ArrayList<>(all.lines());
        if (rows.size() > 1) {
            rows.add(new org.netpilot.service.BranchReport.Line("", Messages.get("reports.allBranches"), "",
                    all.total().sold(), all.total().revenue(), all.total().stock()));
        }
        branchTable.getItems().setAll(rows);
        boolean together = scopeBox.getValue() == Scope.ALL;
        batchTab.setDisable(together); // batches belong to one router's cards
        exportButton.setDisable(together);
        if (!together) {
            return;
        }
        revenueLabel.setText(all.total().revenue().format());
        soldLabel.setText(String.valueOf(all.total().sold()));
        potentialLabel.setText(all.total().stock().map(s -> s.value().format()).orElse("—"));
        unusedLabel.setText(all.total().stock().map(s -> String.valueOf(s.unused())).orElse("—"));
        createdLabel.setText("—");
        profileTable.getItems().setAll(all.profiles());
        showChart(periodBox.getValue(), all.daily());
        if (reportTabs.getSelectionModel().getSelectedItem() == batchTab) {
            reportTabs.getSelectionModel().select(branchTab);
        }
    }

    private void showChart(SalesReport sales) {
        showChart(sales.period(), sales.dailyRevenue());
    }

    /** The last 30 days of the period, one bar per day (empty days included so gaps show). */
    private void showChart(ReportPeriod period, Map<LocalDate, Money> daily) {
        LocalDate today = LocalDate.now();
        LocalDate end = period.to(today);
        LocalDate start = end.minusDays(CHART_DAYS - 1L);
        if (period != ReportPeriod.ALL && period.from(today).isAfter(start)) {
            start = period.from(today);
        }
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            Money money = daily.get(day);
            series.getData().add(new XYChart.Data<>(DAY.format(day), money == null ? 0 : money.amount()));
        }
        dailyChart.getData().setAll(List.of(series));
    }

    private void setUpTables() {
        batchColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(ReportService.batchLabel(c.getValue().batch())));
        batchCreatedColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().created().map(BATCH_TIME::format).orElse("")));
        batchProfileColumn.setCellValueFactory(
                c -> new ReadOnlyStringWrapper(ReportService.profileLabel(c.getValue().profile())));
        batchCardsColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().cards()));
        batchUsedColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().used()));
        batchUnusedColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().unused()));
        batchRevenueColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().revenue()));
        batchRevenueColumn.setCellFactory(column -> formatted(Money::format));
        batchRevenueColumn.setComparator(java.util.Comparator.comparing(Money::amount));

        profileColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().profile()));
        profilePriceColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().price().map(Money::format).orElse("—")));
        profileSoldColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().sold()));
        profileUnusedColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().unused()));
        profileRevenueColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().revenue()));
        profileRevenueColumn.setCellFactory(column -> formatted(Money::format));
        profileRevenueColumn.setComparator(java.util.Comparator.comparing(Money::amount));
        profileDataColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().bytes()));
        profileDataColumn.setCellFactory(column -> formatted(RouterUsersController::formatBytes));
    }

    /** Shows a sortable value (money, bytes) as text. */
    private static <S, T> TableCell<S, T> formatted(Function<T, String> format) {
        return new TableCell<>() {

            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : format.apply(item));
            }
        };
    }

    private void setBusy(boolean busy) {
        progressIndicator.setVisible(busy);
        progressIndicator.setManaged(busy);
        refreshButton.setDisable(busy);
    }

    /** What one refresh reads and records. */
    private record Snapshot(List<RouterUser> users, List<UsageRecord> history) {
    }

    private Window stage() {
        return periodBox.getScene().getWindow();
    }
}
