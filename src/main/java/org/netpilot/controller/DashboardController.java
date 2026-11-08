package org.netpilot.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;
import org.netpilot.AppContext;
import org.netpilot.i18n.Messages;
import org.netpilot.model.ActiveSession;
import org.netpilot.model.ReportPeriod;
import org.netpilot.model.RouterUser;
import org.netpilot.model.SalesReport;
import org.netpilot.model.UsageRecord;
import org.netpilot.service.StockAlerts;
import org.netpilot.service.router.RouterClient;

/**
 * "Dashboard" page: the connected router at a glance (cards, online now, unused, expired, revenue
 * this month) and shortcuts to the other pages.
 *
 * <p>Like Reports, a refresh records newly used cards in the usage history, so revenue stays
 * correct even if cards are deleted later.
 */
public class DashboardController implements Page {

    private static final System.Logger LOG = System.getLogger(DashboardController.class.getName());

    private final AppContext context;

    private Workspace workspace;
    private RouterClient client;
    private boolean loading;

    @FXML
    private VBox offlineCard;
    @FXML
    private VBox routerCard;
    @FXML
    private Label routerName;
    @FXML
    private Label routerDetails;
    @FXML
    private Label usersValue;
    @FXML
    private Label onlineValue;
    @FXML
    private Label unusedValue;
    @FXML
    private Label expiredValue;
    @FXML
    private Label revenueValue;
    @FXML
    private Label potentialValue;
    @FXML
    private ProgressIndicator progressIndicator;
    @FXML
    private Button refreshButton;
    @FXML
    private Button generateQuick;
    @FXML
    private Button hotspotQuick;
    @FXML
    private Button reportsQuick;
    @FXML
    private Button designQuick;
    @FXML
    private VBox stockCard;
    @FXML
    private VBox stockRows;

    public DashboardController(AppContext context) {
        this.context = context;
    }

    @Override
    public void attach(Workspace shared) {
        this.workspace = shared;
        // same names and icons as the side menu
        generateQuick.setGraphic(Icons.of(Icons.TICKET));
        hotspotQuick.setGraphic(Icons.of(Icons.USERS));
        reportsQuick.setGraphic(Icons.of(Icons.CHART));
        designQuick.setGraphic(Icons.of(Icons.PALETTE));
        refreshButton.setGraphic(Icons.of(Icons.REFRESH));
        showOffline();
    }

    @Override
    public void shown() {
        onRefresh();
    }

    /** Live figures without the busy indicator. */
    @Override
    public void tick() {
        if (++ticks % 3 != 0) {
            return; // every 15 seconds is plenty for totals (and saves usage history less often)
        }
        quiet = true;
        onRefresh();
        quiet = false;
    }

    private boolean quiet;
    private int ticks;

    @Override
    public void routerConnected(RouterClient routerClient, String name) {
        this.client = routerClient;
        offlineCard.setVisible(false);
        offlineCard.setManaged(false);
        routerCard.setVisible(true);
        routerCard.setManaged(true);
        refreshButton.setDisable(false);
        routerName.setText(name);
        workspace.router().info().ifPresent(info -> routerDetails.setText(Messages.get("dashboard.routerDetails",
                info.boardName(), info.version(), info.uptime())));
        onRefresh();
    }

    @Override
    public void routerDisconnected() {
        client = null;
        showOffline();
    }

    /** Snapshot of one refresh. */
    private record Figures(List<RouterUser> users, Set<String> online, SalesReport report) {
    }

    @FXML
    private void onRefresh() {
        RouterClient target = client;
        if (target == null || loading) {
            return;
        }
        String router = target.endpoint().address();
        Task<Figures> task = new Task<>() {

            @Override
            protected Figures call() throws Exception {
                List<RouterUser> users = target.hotspotUsers();
                Set<String> online = target.activeSessions().stream().map(ActiveSession::user)
                        .collect(Collectors.toSet());
                List<UsageRecord> history = context.usageTracker().track(router, users, LocalDate.now());
                SalesReport report = context.reportService().build(router, users, history,
                        workspace.settings().voucherTemplate(), ReportPeriod.THIS_MONTH, LocalDate.now());
                return new Figures(users, online, report);
            }
        };
        setBusy(true);
        task.setOnSucceeded(e -> {
            setBusy(false);
            if (client == target) {
                show(task.getValue());
            }
        });
        task.setOnFailed(e -> {
            setBusy(false);
            LOG.log(System.Logger.Level.WARNING, "Cannot load dashboard", task.getException());
        });
        Thread thread = new Thread(task, "netpilot-dashboard");
        thread.setDaemon(true);
        thread.start();
    }

    private void show(Figures figures) {
        List<RouterUser> cards = figures.users().stream().filter(u -> !u.builtIn()).toList();
        usersValue.setText(String.valueOf(cards.size()));
        // only cards: the router can also list sessions of names that are not hotspot users here
        onlineValue.setText(String.valueOf(cards.stream().filter(c -> figures.online().contains(c.name())).count()));
        unusedValue.setText(String.valueOf(cards.stream().filter(RouterUser::neverUsed).count()));
        expiredValue.setText(String.valueOf(cards.stream().filter(RouterUser::expired).count()));
        revenueValue.setText(figures.report().revenue().format());
        potentialValue.setText(figures.report().potential().format());
        showStock(workspace.checkStock(figures.users()));
    }

    /** One line per package running low, each with a button to create a batch of it. */
    private void showStock(List<StockAlerts.Low> low) {
        stockRows.getChildren().clear();
        for (StockAlerts.Low item : low) {
            Label text = new Label("⚠ " + (item.unused() == 0
                    ? Messages.get("stock.soldOut", item.profile())
                    : Messages.get("stock.few", item.profile(), item.unused())));
            text.setWrapText(true);
            text.getStyleClass().add("stock-text");
            javafx.scene.layout.Region gap = new javafx.scene.layout.Region();
            javafx.scene.layout.HBox.setHgrow(gap, javafx.scene.layout.Priority.ALWAYS);
            Button create = new Button(Messages.get("nav.GENERATE"), Icons.of(Icons.TICKET));
            create.setOnAction(
                    e -> workspace.show(PageId.GENERATE, GenerateController.PROFILE_SECTION + item.profile()));
            javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(10, text, gap, create);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            stockRows.getChildren().add(row);
        }
        stockCard.setVisible(!low.isEmpty());
        stockCard.setManaged(!low.isEmpty());
    }

    private void showOffline() {
        offlineCard.setVisible(true);
        offlineCard.setManaged(true);
        routerCard.setVisible(false);
        routerCard.setManaged(false);
        refreshButton.setDisable(true);
        routerName.setText(Messages.get("router.notConnected"));
        routerDetails.setText("");
        showStock(List.of());
        for (Label value : List.of(usersValue, onlineValue, unusedValue, expiredValue, revenueValue, potentialValue)) {
            value.setText("—");
        }
    }

    private void setBusy(boolean busy) {
        loading = busy;
        boolean show = busy && !quiet;
        progressIndicator.setVisible(show);
        progressIndicator.setManaged(show);
    }

    @FXML
    private void onConnect() {
        workspace.connect();
    }

    @FXML
    private void onGenerate() {
        workspace.show(PageId.GENERATE);
    }

    @FXML
    private void onHotspot() {
        workspace.show(PageId.HOTSPOT);
    }

    @FXML
    private void onReports() {
        workspace.show(PageId.REPORTS);
    }

    @FXML
    private void onDesign() {
        workspace.show(PageId.DESIGN);
    }
}
