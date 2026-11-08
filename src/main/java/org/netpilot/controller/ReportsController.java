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
    }

    @FXML
    private void initialize() {
        periodBox.getItems().setAll(ReportPeriod.values());
        periodBox.setValue(ReportPeriod.LAST_30_DAYS);
        periodBox.valueProperty().addListener((obs, old, value) -> rebuild());
        exportButton.setDisable(true);
        setUpTables();
    }

    /**
     * @param voucherTemplate current template, read on each rebuild so new prices apply at once
     */
    public void start(RouterClient routerClient, String routerName, Supplier<VoucherTemplate> voucherTemplate) {
        this.client = routerClient;
        this.template = voucherTemplate;
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
                List<UsageRecord> updated = context.usageTracker().track(router, current, LocalDate.now());
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
    }

    /** The last 30 days of the period, one bar per day (empty days included so gaps show). */
    private void showChart(SalesReport sales) {
        LocalDate today = LocalDate.now();
        LocalDate end = sales.period().to(today);
        LocalDate start = end.minusDays(CHART_DAYS - 1L);
        if (sales.period() != ReportPeriod.ALL && sales.period().from(today).isAfter(start)) {
            start = sales.period().from(today);
        }
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        Map<LocalDate, Money> daily = sales.dailyRevenue();
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
