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
import org.netpilot.service.QuickSale;
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
        // the daily update check, a little after start so it never slows the window down
        javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(UPDATE_CHECK_DELAY);
        wait.setOnFinished(e -> checkForUpdate());
        wait.play();
    }

    // ------------------------------------------------------------------ update banner

    private static final javafx.util.Duration UPDATE_CHECK_DELAY = javafx.util.Duration.seconds(8);

    @FXML
    private javafx.scene.layout.HBox updateBanner;
    @FXML
    private Label updateLabel;

    /** The release the banner offers. */
    private org.netpilot.service.update.Release offered;

    /**
     * Once a day (when turned on in Settings): asks GitHub for a newer release and shows the banner
     * unless the user skipped that version. No internet or no answer: nothing shows, the next day
     * tries again.
     */
    private void checkForUpdate() {
        java.nio.file.Path file = org.netpilot.repository.AppPaths.updateFile();
        org.netpilot.service.update.UpdatePreferences preferences = org.netpilot.service.update.UpdatePreferences
                .load(file);
        LocalDate today = LocalDate.now();
        if (!preferences.due(today)) {
            return;
        }
        org.netpilot.service.update.UpdateChecker checker = org.netpilot.service.update.UpdateChecker
                .gitHub(org.netpilot.NetPilotApp.VERSION);
        Task<java.util.Optional<org.netpilot.service.update.Release>> task = new Task<>() {

            @Override
            protected java.util.Optional<org.netpilot.service.update.Release> call() throws Exception {
                return checker.newer();
            }
        };
        task.setOnSucceeded(e -> {
            UpdateFlow.savePreferences(preferences.checked(today));
            task.getValue().filter(preferences::remind).ifPresent(this::showUpdate);
        });
        task.setOnFailed(e -> LOG.log(System.Logger.Level.INFO, "Update check skipped: {0}",
                String.valueOf(task.getException().getMessage())));
        Thread thread = new Thread(task, "netpilot-update-check");
        thread.setDaemon(true);
        thread.start();
    }

    private void showUpdate(org.netpilot.service.update.Release release) {
        offered = release;
        updateLabel.setText(Messages.get("update.banner", release.version()));
        updateBanner.setVisible(true);
        updateBanner.setManaged(true);
    }

    private void hideUpdate() {
        updateBanner.setVisible(false);
        updateBanner.setManaged(false);
    }

    @FXML
    private void onUpdateDetails() {
        if (offered != null) {
            org.netpilot.service.update.Release release = offered;
            switch (UpdateFlow.ask(workspace, release)) {
                case UPDATE -> UpdateFlow.install(workspace, release);
                case SKIP -> onUpdateSkip();
                case LATER -> hideUpdate();
            }
        }
    }

    @FXML
    private void onUpdateNow() {
        if (offered != null) {
            UpdateFlow.install(workspace, offered);
        }
    }

    @FXML
    private void onUpdateLater() {
        hideUpdate(); // the next daily check offers it again
    }

    @FXML
    private void onUpdateSkip() {
        if (offered != null) {
            UpdateFlow.savePreferences(org.netpilot.service.update.UpdatePreferences
                    .load(org.netpilot.repository.AppPaths.updateFile()).skip(offered));
        }
        hideUpdate();
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

    /** Snapshot of one refresh; {@code holders} are the cards points of sale have. */
    private record Figures(List<RouterUser> users, Set<String> online, SalesReport report,
            java.util.Map<String, String> holders, List<QuickSale.Reservation> pending,
            org.netpilot.service.ResellerAccounts.DirectSummary direct) {
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
                List<UsageRecord> history = context.usageTracker().track(router, users, online, LocalDate.now());
                SalesReport report = context.reportService().build(router, users, history,
                        workspace.settings().voucherTemplate(), ReportPeriod.THIS_MONTH, LocalDate.now());
                QuickSale sales = new QuickSale(context.resellerAccounts());
                return new Figures(users, online, report, context.resellerAccounts().holders(), sales.pending(),
                        sales.summary(LocalDate.now()));
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
        showQuickSale(figures);
    }

    // ------------------------------------------------------------------ quick sale

    @FXML
    private VBox quickSaleCard;
    @FXML
    private javafx.scene.layout.FlowPane quickSaleButtons;
    @FXML
    private Label quickSaleEmpty;
    private boolean selling;
    private String lastProfile;
    private boolean f2Registered;
    /** The reservation of the sale being finished now, so it is not shown as unfinished meanwhile. */
    private String inProgress = "";

    /** The currency of the package prices, for totals that have no sale yet. */
    private String priceCurrency() {
        return workspace.settings().voucherTemplate().prices().values().stream()
                .map(org.netpilot.model.Money::parse).flatMap(java.util.Optional::stream)
                .map(org.netpilot.model.Money::currency).filter(c -> !c.isEmpty()).findFirst().orElse("");
    }

    /** One button per package that has a price, with how many cards are ready to sell. */
    private void showQuickSale(Figures figures) {
        String router = client == null ? "" : client.endpoint().address();
        java.util.Map<String, Integer> stock = QuickSale.stock(router, figures.users(), figures.online(),
                figures.holders());
        quickSaleButtons.getChildren().clear();
        workspace.settings().voucherTemplate().prices().forEach((profile, priceText) -> {
            if (org.netpilot.model.Money.parse(priceText).isEmpty()) {
                return; // a card without a readable price cannot be sold here
            }
            int left = stock.getOrDefault(profile, 0);
            Button sell = new Button(left == 0
                    ? Messages.get("quickSale.soldOut", profile, priceText)
                    : Messages.get("quickSale.button", profile, priceText, left), Icons.of(Icons.TICKET));
            sell.getStyleClass().add(profile.equals(lastProfile) ? "primary" : "quick-sale");
            sell.setDisable(left == 0 || selling);
            sell.setOnAction(e -> sell(profile));
            quickSaleButtons.getChildren().add(sell);
        });
        boolean any = !quickSaleButtons.getChildren().isEmpty();
        quickSaleEmpty.setVisible(!any);
        quickSaleEmpty.setManaged(!any);
        var direct = figures.direct();
        quickSaleToday.setText(Messages.get("quickSale.today", direct.soldToday(),
                new org.netpilot.model.Money(direct.totalToday(), direct.currency().isEmpty()
                        ? priceCurrency()
                        : direct.currency()).format()));
        // the sale on screen right now is not "unfinished": it is being finished
        showPending(figures.pending().stream().filter(r -> !r.reserve().id().equals(inProgress)).toList());
        quickSaleCard.setVisible(true);
        quickSaleCard.setManaged(true);
        registerF2();
    }

    @FXML
    private Label quickSaleToday;
    @FXML
    private VBox quickSalePending;

    /**
     * Sales left unfinished (NetPilot stopped, or recording failed, after the card was reserved):
     * the user says whether the receipt was handed over. Each answer is recorded once.
     */
    private void showPending(List<QuickSale.Reservation> pending) {
        quickSalePending.getChildren().clear();
        for (QuickSale.Reservation r : pending) {
            Label text = new Label("⚠ " + Messages.get("quickSale.pending", r.card().name(), r.card().profile(),
                    r.reserve().at().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))));
            text.setWrapText(true);
            text.getStyleClass().add("stock-text");
            javafx.scene.layout.Region gap = new javafx.scene.layout.Region();
            javafx.scene.layout.HBox.setHgrow(gap, javafx.scene.layout.Priority.ALWAYS);
            Button handed = new Button(Messages.get("quickSale.pending.handed"));
            Button back = new Button(Messages.get("quickSale.pending.back"));
            QuickSale sale = new QuickSale(context.resellerAccounts());
            handed.setOnAction(e -> resolve(() -> sale.confirm(r), r.card().name()));
            back.setOnAction(e -> resolve(() -> sale.cancel(r), r.card().name()));
            javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(8, text, gap, handed, back);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            quickSalePending.getChildren().add(row);
        }
        quickSalePending.setVisible(!pending.isEmpty());
        quickSalePending.setManaged(!pending.isEmpty());
    }

    /** An action on the ledger that may fail; it is safe to repeat. */
    private interface LedgerStep {

        void run() throws java.io.IOException;
    }

    private void resolve(LedgerStep step, String card) {
        Task<Void> task = new Task<>() {

            @Override
            protected Void call() throws Exception {
                step.run();
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            LOG.log(System.Logger.Level.INFO, "Pending direct sale of card {0} settled", card);
            onRefresh();
        });
        task.setOnFailed(e -> {
            LOG.log(System.Logger.Level.WARNING, "Cannot settle the pending sale of card " + card, task.getException());
            Dialogs.error(workspace.window(), Messages.get("quickSale.failed"),
                    String.valueOf(task.getException().getMessage()));
        });
        Thread thread = new Thread(task, "netpilot-quick-sale-pending");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * F2 sells another card of the last package, from any page, but only when nothing else could
     * receive the key: the main window has the focus (no dialog open), no text is being typed and
     * no sale is running.
     */
    private void registerF2() {
        javafx.stage.Window window = workspace.window();
        if (f2Registered || window == null || window.getScene() == null) {
            return;
        }
        javafx.scene.Scene scene = window.getScene();
        scene.getAccelerators().put(new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.F2), () -> {
            if (f2Allowed(window, scene.getFocusOwner())) {
                sell(lastProfile);
            }
        });
        f2Registered = true;
    }

    private boolean f2Allowed(javafx.stage.Window window, javafx.scene.Node focus) {
        boolean typing = focus instanceof javafx.scene.control.TextInputControl
                || focus instanceof javafx.scene.control.ComboBoxBase<?>
                || focus instanceof javafx.scene.control.Spinner<?>;
        return lastProfile != null && !selling && client != null && window.isFocused() && window.isShowing()
                && !typing;
    }

    /** What a sale needs once the card is taken: the reservation and the card with its password. */
    private record Taken(QuickSale.Reservation reservation, org.netpilot.model.HotspotUser card) {
    }

    /**
     * Sells one card of this package: reads the router afresh, takes the oldest ready card (a
     * handover to the counter, so no other sale can take it), prints or shows its receipt, then
     * records the payment, or takes the card back when the receipt could not be printed.
     */
    private void sell(String profile) {
        RouterClient target = client;
        if (target == null || selling) {
            return;
        }
        selling = true;
        lastProfile = profile;
        String router = target.endpoint().address();
        java.util.Map<String, String> prices = workspace.settings().voucherTemplate().prices();
        QuickSale sale = new QuickSale(context.resellerAccounts());
        Task<Taken> take = new Task<>() {

            @Override
            protected Taken call() throws Exception {
                List<RouterUser> users = target.hotspotUsers();
                Set<String> online = target.activeSessions().stream().map(ActiveSession::user)
                        .collect(Collectors.toSet());
                RouterUser card = QuickSale.pick(router, users, online, context.resellerAccounts().holders(),
                        profile).orElseThrow(() -> new IllegalStateException(Messages.get("quickSale.none", profile)));
                QuickSale.Reservation reserved = sale.reserve(new org.netpilot.service.ResellerAccounts.Card(router,
                        card.name(), card.profile()), prices)
                        .orElseThrow(() -> new IllegalStateException(Messages.get("quickSale.taken")));
                List<org.netpilot.model.HotspotUser> withPassword = context.routerUserActions()
                        .voucherUsers(target, List.of(card));
                if (withPassword.isEmpty()) {
                    sale.cancel(reserved); // deleted from the router meanwhile
                    throw new IllegalStateException(Messages.get("quickSale.taken"));
                }
                return new Taken(reserved, withPassword.getFirst());
            }
        };
        take.setOnSucceeded(e -> {
            inProgress = take.getValue().reservation().reserve().id();
            deliver(sale, take.getValue());
        });
        take.setOnFailed(e -> finish(take.getException(), null));
        Thread thread = new Thread(take, "netpilot-quick-sale");
        thread.setDaemon(true);
        thread.start();
    }

    /** Prints the receipt (or shows it when no receipt printer is chosen), then settles the sale. */
    private void deliver(QuickSale sale, Taken taken) {
        javafx.scene.image.WritableImage receipt = workspace.renderReceipt(taken.card());
        org.netpilot.model.ReceiptSettings printer = workspace.settings().receipt();
        if (printer.printer().isEmpty()) {
            boolean handed = QuickSaleWindow.show(workspace.window(), receipt, taken.card());
            settle(() -> {
                if (handed) {
                    sale.confirm(taken.reservation());
                } else {
                    sale.cancel(taken.reservation());
                }
                return handed;
            }, taken);
            return;
        }
        settle(() -> {
            try {
                ReceiptPrinting.forPlatform(org.netpilot.platform.PlatformInfo.current()).print(List.of(receipt),
                        printer);
            } catch (RuntimeException printFailed) {
                sale.cancel(taken.reservation()); // nothing printed: the card goes back, nothing charged
                throw printFailed;
            }
            try {
                sale.confirm(taken.reservation());
            } catch (java.io.IOException notRecorded) {
                // printed but not recorded: the sale stays pending (card reserved) for the user to finish
                throw new IllegalStateException(Messages.get("quickSale.printedNotRecorded",
                        taken.card().username()), notRecorded);
            }
            return true;
        }, taken);
    }

    private void settle(java.util.concurrent.Callable<Boolean> work, Taken taken) {
        Task<Boolean> task = new Task<>() {

            @Override
            protected Boolean call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> finish(null, task.getValue() ? taken : null));
        task.setOnFailed(e -> finish(task.getException(), null));
        Thread thread = new Thread(task, "netpilot-quick-sale-settle");
        thread.setDaemon(true);
        thread.start();
    }

    /** Tells the user how the sale ended and refreshes the figures. */
    private void finish(Throwable error, Taken sold) {
        inProgress = "";
        selling = false;
        if (error != null) {
            LOG.log(System.Logger.Level.WARNING, "Quick sale failed", error);
            Dialogs.error(workspace.window(), Messages.get("quickSale.failed"), error instanceof IllegalStateException
                    ? error.getMessage()
                    : Messages.get("quickSale.failedBody", String.valueOf(error.getMessage())));
        } else if (sold != null) {
            LOG.log(System.Logger.Level.INFO, "Quick sale: card {0} ({1})", sold.card().username(),
                    sold.card().profile());
            workspace.toast("✓ " + Messages.get("quickSale.sold", sold.card().profile(), sold.card().username()));
        }
        onRefresh();
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
        quickSaleCard.setVisible(false);
        quickSaleCard.setManaged(false);
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
