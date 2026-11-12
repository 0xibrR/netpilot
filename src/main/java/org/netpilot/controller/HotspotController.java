package org.netpilot.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.util.StringConverter;
import org.netpilot.AppContext;
import org.netpilot.i18n.Messages;
import org.netpilot.model.ActiveSession;
import org.netpilot.model.DataSize;
import org.netpilot.model.HotspotProfile;
import org.netpilot.model.HotspotServer;
import org.netpilot.model.RouterDuration;
import org.netpilot.model.RouterResource;
import org.netpilot.model.RouterUser;
import org.netpilot.service.ResellerAccounts;
import org.netpilot.service.router.RouterClient;
import org.netpilot.service.router.RouterException;
import org.netpilot.service.router.RouterUserActions.BulkResult;
import org.netpilot.service.tools.LiveTraffic;

/**
 * "Hotspot" page, with WinBox-like tabs: users (the {@link RouterUsersController} view), active
 * sessions, user profiles and servers.
 */
public class HotspotController implements Page {

    private static final System.Logger LOG = System.getLogger(HotspotController.class.getName());

    /** Parts of the page the side menu can open. */
    static final String USERS = "users";

    private final AppContext context;

    private Workspace workspace;
    private RouterClient client;
    private boolean activeLoaded;

    @FXML
    private TabPane tabs;
    @FXML
    private Tab activeTab;
    @FXML
    private Tab usersTab;

    @FXML
    private RouterUsersController usersController;

    @FXML
    private Button refreshActiveButton;
    @FXML
    private Button kickButton;
    @FXML
    private ProgressIndicator activeProgress;
    @FXML
    private TextField activeSearch;
    @FXML
    private ComboBox<Integer> intervalBox;
    @FXML
    private HBox chartHeader;
    @FXML
    private StackPane chartIcon;
    @FXML
    private StackPane chevron;
    @FXML
    private Label foldedDownload;
    @FXML
    private Label foldedUpload;

    /** The chart section is open; kept while switching pages. */
    private final BooleanProperty chartOpen = new SimpleBooleanProperty(true);
    @FXML
    private Label activeProblem;
    @FXML
    private Label onlineValue;
    @FXML
    private Label cpuValue;
    @FXML
    private Label memoryValue;
    @FXML
    private Label downloadValue;
    @FXML
    private Label uploadValue;
    @FXML
    private LineChart<Number, Number> trafficChart;
    @FXML
    private NumberAxis timeAxis;
    @FXML
    private TableView<ActiveSession> activeTable;
    @FXML
    private TableColumn<ActiveSession, String> activeUserColumn;
    @FXML
    private TableColumn<ActiveSession, String> activeProfileColumn;
    @FXML
    private TableColumn<ActiveSession, String> activeAddressColumn;
    @FXML
    private TableColumn<ActiveSession, String> activeMacColumn;
    @FXML
    private TableColumn<ActiveSession, Duration> activeUptimeColumn;
    @FXML
    private TableColumn<ActiveSession, String> activeLeftColumn;
    @FXML
    private TableColumn<ActiveSession, Long> activeDownRateColumn;
    @FXML
    private TableColumn<ActiveSession, Long> activeUpRateColumn;
    @FXML
    private TableColumn<ActiveSession, Long> activeDownloadedColumn;
    @FXML
    private TableColumn<ActiveSession, Long> activeUploadedColumn;

    /** Every session read, before the search filter. */
    private final ObservableList<ActiveSession> sessions = FXCollections.observableArrayList();
    private final FilteredList<ActiveSession> shownSessions = new FilteredList<>(sessions);
    private final LiveTraffic traffic = new LiveTraffic();
    private LiveTraffic.Snapshot rates = new LiveTraffic.Snapshot(Map.of(), LiveTraffic.Rate.ZERO, List.of());
    private Map<String, String> profiles = Map.of();
    private final XYChart.Series<Number, Number> downloadSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> uploadSeries = new XYChart.Series<>();
    private final Timeline liveTimer = new Timeline();

    @FXML
    private TableView<HotspotProfile> profilesTable;
    @FXML
    private TableColumn<HotspotProfile, String> profileNameColumn;
    @FXML
    private TableColumn<HotspotProfile, String> profileDownloadColumn;
    @FXML
    private TableColumn<HotspotProfile, String> profileUploadColumn;
    @FXML
    private TableColumn<HotspotProfile, String> profileSharedColumn;
    @FXML
    private TableColumn<HotspotProfile, String> profileValidityColumn;
    @FXML
    private Button newProfileButton;
    @FXML
    private Button editProfileButton;
    @FXML
    private Button deleteProfileButton;
    @FXML
    private TableColumn<HotspotProfile, String> profilePriceColumn;

    @FXML
    private TableView<HotspotServer> serversTable;
    @FXML
    private TableColumn<HotspotServer, String> serverNameColumn;
    @FXML
    private TableColumn<HotspotServer, String> serverInterfaceColumn;
    @FXML
    private TableColumn<HotspotServer, String> serverStateColumn;

    public HotspotController(AppContext context) {
        this.context = context;
    }

    @FXML
    private void initialize() {
        initActiveTab();

        profileNameColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name()));
        profileDownloadColumn
                .setCellValueFactory(c -> new ReadOnlyStringWrapper(speed(c.getValue().speed().download())));
        profileUploadColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(speed(c.getValue().speed().upload())));
        profileSharedColumn.setCellValueFactory(
                c -> new ReadOnlyStringWrapper(String.valueOf(c.getValue().sharedUsers())));
        profileValidityColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().validity()
                .map(v -> v.amount() + " " + v.unit())
                .orElse("—")));
        var noProfile = profilesTable.getSelectionModel().selectedItemProperty().isNull();
        editProfileButton.disableProperty().bind(noProfile);
        deleteProfileButton.disableProperty().bind(noProfile.or(Bindings.createBooleanBinding(
                () -> {
                    HotspotProfile p = profilesTable.getSelectionModel().getSelectedItem();
                    return p != null && p.builtIn();
                }, profilesTable.getSelectionModel().selectedItemProperty())));
        newProfileButton.setGraphic(Icons.of(Icons.PLUS));
        editProfileButton.setGraphic(Icons.of(Icons.SETTINGS));
        deleteProfileButton.setGraphic(Icons.of(Icons.CROSS));
        profilesTable.setRowFactory(table -> {
            javafx.scene.control.TableRow<HotspotProfile> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    onEditProfile();
                }
            });
            return row;
        });
        profilePriceColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                workspace == null
                        ? ""
                        : workspace.settings().voucherTemplate().priceFor(c.getValue().name()).orElse("—")));

        serverNameColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name()));
        serverInterfaceColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().interfaceName()));
        serverStateColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(Messages.get(
                c.getValue().disabled() ? "users.state.disabled" : "hotspot.state.running")));

        tabs.getSelectionModel().selectedItemProperty().addListener((obs, old, tab) -> {
            if (tab == activeTab && !activeLoaded) {
                onRefreshActive();
            }
        });
    }

    @Override
    public void attach(Workspace shared) {
        this.workspace = shared;
    }

    @Override
    public void open(String section) {
        if (USERS.equals(section)) {
            tabs.getSelectionModel().select(usersTab);
        }
    }

    /** Prices may have changed in Voucher Design. */
    @Override
    public void shown() {
        profilesTable.refresh();
    }

    /** Every few seconds: users and the profile lists. The online tab has its own, faster timer. */
    @Override
    public void tick() {
        if (client != null) {
            usersController.liveRefresh(); // profiles and servers are refreshed for every page by the main window
        }
    }

    // ------------------------------------------------------------------ online now (live)

    /** One reading of the router for the online tab. */
    private record LiveReading(List<ActiveSession> sessions, Optional<RouterResource> resource, Instant at) {
    }

    /** A reading is on its way; the next one waits for it, so a slow router never gets a queue. */
    private boolean liveRunning;

    /** CPU and memory change slowly: read every few seconds even when users refresh every second. */
    static final Duration LOAD_EVERY = Duration.ofSeconds(4);

    /** Written by the reading thread only. */
    private volatile Instant lastLoadAt;

    private boolean loadDue(Instant now) {
        Instant last = lastLoadAt;
        return last == null || Duration.between(last, now).compareTo(LOAD_EVERY) >= 0;
    }

    private void initActiveTab() {
        SortedList<ActiveSession> sorted = new SortedList<>(shownSessions);
        sorted.comparatorProperty().bind(activeTable.comparatorProperty());
        activeTable.setItems(sorted);
        activeTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        activeSearch.textProperty().addListener((obs, old, text) -> applySearch());

        activeUserColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().user()));
        activeUserColumn.getStyleClass().add("monospace");
        activeProfileColumn.setCellValueFactory(
                c -> new ReadOnlyStringWrapper(profiles.getOrDefault(c.getValue().user(), "")));
        activeAddressColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().address()));
        activeMacColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().macAddress()));
        activeUptimeColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().uptime()));
        activeUptimeColumn.setCellFactory(column -> new TableCell<>() {

            @Override
            protected void updateItem(Duration item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : RouterDuration.clock(item));
            }
        });
        activeLeftColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().timeLeft()
                .map(RouterDuration::clock)
                .orElse(Messages.get("common.unlimited"))));
        // speeds sort as numbers; -1 means "not measured yet" and shows as a dash
        activeDownRateColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                rates.rateOf(c.getValue().routerId()).map(LiveTraffic.Rate::downloadBps).orElse(-1L)));
        activeUpRateColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                rates.rateOf(c.getValue().routerId()).map(LiveTraffic.Rate::uploadBps).orElse(-1L)));
        activeDownRateColumn.setCellFactory(column -> numberCell(v -> v < 0 ? "—" : LiveTraffic.format(v)));
        activeUpRateColumn.setCellFactory(column -> numberCell(v -> v < 0 ? "—" : LiveTraffic.format(v)));
        activeDownloadedColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().bytesOut()));
        activeUploadedColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().bytesIn()));
        activeDownloadedColumn.setCellFactory(column -> numberCell(DataSize::format));
        activeUploadedColumn.setCellFactory(column -> numberCell(DataSize::format));
        kickButton.disableProperty().bind(Bindings.isEmpty(activeTable.getSelectionModel().getSelectedItems()));

        downloadSeries.setName(Messages.get("hotspot.live.download"));
        uploadSeries.setName(Messages.get("hotspot.live.upload"));
        trafficChart.getData().setAll(List.of(downloadSeries, uploadSeries));
        trafficChart.visibleProperty().bind(chartOpen);
        trafficChart.managedProperty().bind(chartOpen);
        chartIcon.getChildren().setAll(Icons.of(Icons.LINE_CHART));
        chartOpen.addListener((obs, old, open) -> showFold(open));
        chartHeader.setOnMouseClicked(e -> chartOpen.set(!chartOpen.get()));
        chartHeader.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                chartOpen.set(!chartOpen.get());
                e.consume();
            }
        });
        showFold(chartOpen.get());
        timeAxis.setTickLabelFormatter(new StringConverter<>() {

            @Override
            public String toString(Number seconds) {
                long minutes = Math.round(-seconds.doubleValue() / 60);
                return minutes == 0
                        ? Messages.get("hotspot.live.now")
                        : Messages.get("hotspot.live.minutesAgo", minutes);
            }

            @Override
            public Number fromString(String text) {
                return 0;
            }
        });

        intervalBox.getItems().setAll(INTERVALS);
        intervalBox.setConverter(new StringConverter<>() {

            @Override
            public String toString(Integer seconds) {
                if (seconds == null) {
                    return "";
                }
                return seconds == 1
                        ? Messages.get("hotspot.live.everySecond")
                        : Messages.get("hotspot.live.seconds", seconds);
            }

            @Override
            public Integer fromString(String text) {
                return DEFAULT_INTERVAL;
            }
        });
        intervalBox.setValue(DEFAULT_INTERVAL);
        intervalBox.valueProperty().addListener((obs, old, seconds) -> scheduleLive());
        scheduleLive();
    }

    /**
     * Open: the chart below the header and an up arrow (click folds it). Folded: one line with the
     * current speeds and a down arrow, so the key figure stays in sight while the table gets the room.
     */
    private void showFold(boolean open) {
        chevron.getChildren().setAll(Icons.of(open ? Icons.CHEVRON_UP : Icons.CHEVRON_DOWN));
        for (Label figure : List.of(foldedDownload, foldedUpload)) {
            figure.setVisible(!open);
            figure.setManaged(!open);
        }
        Tooltip.install(chartHeader,
                new Tooltip(Messages.get(open ? "hotspot.live.hideChart" : "hotspot.live.showChart")));
        chartHeader.pseudoClassStateChanged(FOLDED, !open);
    }

    private static final PseudoClass FOLDED = PseudoClass.getPseudoClass("folded");

    private static <T> TableCell<T, Long> numberCell(java.util.function.LongFunction<String> format) {
        return new TableCell<>() {

            @Override
            protected void updateItem(Long item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : format.apply(item));
            }
        };
    }

    /** Refresh choices for the online tab, in seconds. */
    static final List<Integer> INTERVALS = List.of(1, 2, 5, 10);
    static final int DEFAULT_INTERVAL = 5;

    private void scheduleLive() {
        int seconds = intervalBox.getValue() == null ? DEFAULT_INTERVAL : intervalBox.getValue();
        liveTimer.stop();
        liveTimer.getKeyFrames().setAll(new KeyFrame(javafx.util.Duration.seconds(seconds), e -> liveTick()));
        liveTimer.setCycleCount(Animation.INDEFINITE);
        if (client != null) {
            liveTimer.play();
        }
    }

    /**
     * Reads the router only while someone can see the online tab: this page shown, that tab
     * selected and the window not minimised. Otherwise the timer fires but sends nothing.
     */
    private void liveTick() {
        if (client != null && !liveRunning && onScreen()) {
            readLive(false);
        }
    }

    private boolean onScreen() {
        if (tabs.getSelectionModel().getSelectedItem() != activeTab || activeTable.getScene() == null
                || !(activeTable.getScene().getWindow() instanceof javafx.stage.Stage stage) || stage.isIconified()
                || !stage.isShowing()) {
            return false;
        }
        for (javafx.scene.Node node = activeTable; node != null; node = node.getParent()) {
            if (!node.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads the online sessions and the router's load in the background.
     *
     * @param busyIndicator show the spinner (a Refresh press) rather than updating quietly
     */
    private void readLive(boolean busyIndicator) {
        RouterClient target = client;
        if (target == null || liveRunning) {
            return;
        }
        liveRunning = true;
        Task<LiveReading> task = new Task<>() {

            @Override
            protected LiveReading call() throws Exception {
                List<ActiveSession> read = target.activeSessions();
                Instant at = Instant.now(); // the counters' moment, not the moment the UI gets them
                Optional<RouterResource> load;
                try {
                    load = loadDue(at) ? Optional.of(target.resource()) : Optional.empty();
                    load.ifPresent(l -> lastLoadAt = at);
                } catch (RouterException e) {
                    LOG.log(System.Logger.Level.DEBUG, "Router load not read", e);
                    load = Optional.empty(); // the users matter more than the load figures
                }
                return new LiveReading(read, load, at);
            }
        };
        if (busyIndicator) {
            task.setOnRunning(e -> setBusy(true));
        }
        task.setOnSucceeded(e -> {
            liveRunning = false;
            setBusy(false);
            if (client == target) {
                showLive(task.getValue());
            }
        });
        task.setOnFailed(e -> {
            liveRunning = false;
            setBusy(false);
            Throwable error = task.getException();
            LOG.log(error instanceof RouterException ? System.Logger.Level.WARNING : System.Logger.Level.ERROR,
                    "Reading the online users failed", error);
            if (client == target) {
                activeProblem.setText(Messages.get("hotspot.live.failed", String.valueOf(error.getMessage())));
                activeProblem.setVisible(true);
                activeProblem.setManaged(true);
            }
        });
        Thread thread = new Thread(task, "netpilot-online");
        thread.setDaemon(true);
        thread.start();
    }

    private void showLive(LiveReading reading) {
        activeLoaded = true;
        activeProblem.setVisible(false);
        activeProblem.setManaged(false);
        rates = traffic.update(reading.sessions(), reading.at());
        profiles = usersController.profiles();

        Set<String> selected = activeTable.getSelectionModel().getSelectedItems().stream()
                .map(ActiveSession::routerId).collect(Collectors.toSet());
        sessions.setAll(reading.sessions());
        activeTable.getSelectionModel().clearSelection();
        activeTable.getItems().stream().filter(s -> selected.contains(s.routerId()))
                .forEach(s -> activeTable.getSelectionModel().select(s));
        activeTable.refresh(); // speeds and packages are read through the cells

        onlineValue.setText(String.valueOf(reading.sessions().size()));
        downloadValue.setText(LiveTraffic.format(rates.total().downloadBps()));
        uploadValue.setText(LiveTraffic.format(rates.total().uploadBps()));
        foldedDownload.setText("↓ " + downloadValue.getText());
        foldedUpload.setText("↑ " + uploadValue.getText());
        reading.resource().ifPresent(load -> {
            cpuValue.setText(load.cpuLoad() + "%");
            memoryValue.setText(load.totalMemory() <= 0 ? "—" : load.usedMemory() * 100 / load.totalMemory() + "%");
        });
        showChart(reading.at());
    }

    private void showChart(Instant now) {
        List<XYChart.Data<Number, Number>> down = new java.util.ArrayList<>();
        List<XYChart.Data<Number, Number>> up = new java.util.ArrayList<>();
        for (LiveTraffic.Point point : rates.history()) {
            double secondsAgo = -Duration.between(point.at(), now).toMillis() / 1000.0;
            down.add(new XYChart.Data<>(secondsAgo, point.total().downloadBps() / 1_000_000.0));
            up.add(new XYChart.Data<>(secondsAgo, point.total().uploadBps() / 1_000_000.0));
        }
        downloadSeries.getData().setAll(down);
        uploadSeries.getData().setAll(up);
    }

    private void applySearch() {
        String query = activeSearch.getText() == null ? "" : activeSearch.getText().strip().toLowerCase(Locale.ROOT);
        shownSessions.setPredicate(query.isEmpty() ? null : s -> s.matches(query));
    }

    private void clearLive() {
        lastLoadAt = null;
        foldedDownload.setText("");
        foldedUpload.setText("");
        traffic.reset();
        rates = new LiveTraffic.Snapshot(Map.of(), LiveTraffic.Rate.ZERO, List.of());
        sessions.clear();
        downloadSeries.getData().clear();
        uploadSeries.getData().clear();
        for (Label value : List.of(onlineValue, cpuValue, memoryValue, downloadValue, uploadValue)) {
            value.setText("—");
        }
        activeProblem.setVisible(false);
        activeProblem.setManaged(false);
    }

    @Override
    public void routerConnected(RouterClient routerClient, String name) {
        this.client = routerClient;
        activeLoaded = false;
        clearLive();
        scheduleLive();
        usersController.start(routerClient, name, workspace::printVouchers, workspace::printReceipts,
                workspace::checkStock);
        String routerKey = routerClient.endpoint().address();
        usersController.setHandover(selected -> handOver(routerKey, selected));
        usersController.setCustomerActions(card -> linkCustomer(routerKey, card),
                card -> sendToCustomer(routerClient, routerKey, card));
        if (tabs.getSelectionModel().getSelectedItem() == activeTab) {
            onRefreshActive();
        }
    }

    @Override
    public void routerListsLoaded(RouterSession.RouterLists lists) {
        HotspotProfile selected = profilesTable.getSelectionModel().getSelectedItem();
        profilesTable.getItems().setAll(lists.profiles());
        if (selected != null) {
            lists.profiles().stream().filter(p -> p.routerId().equals(selected.routerId())).findFirst()
                    .ifPresent(p -> profilesTable.getSelectionModel().select(p));
        }
        serversTable.getItems().setAll(lists.servers());
    }

    @Override
    public void routerDisconnected() {
        client = null;
        liveTimer.stop();
        usersController.stop();
        clearLive();
        profilesTable.getItems().clear();
        serversTable.getItems().clear();
    }

    // ------------------------------------------------------------------ profiles (speeds are the user's)

    /**
     * Hands unused selected cards to a point of sale. Used cards are left out: a card that was
     * already used cannot be sold again, so it never goes into a point of sale's stock.
     */
    private void handOver(String routerKey, List<RouterUser> selected) {
        List<ResellerAccounts.Card> unused = selected.stream()
                .filter(u -> !u.builtIn() && u.neverUsed())
                .map(u -> new ResellerAccounts.Card(routerKey, u.name(), u.profile()))
                .toList();
        if (unused.size() < selected.size()) {
            Dialogs.info(workspace.window(), Messages.get("handover.title"),
                    Messages.get("handover.usedLeftOut", selected.size() - unused.size()));
        }
        HandoverFlow.start(workspace, workspace.window(), unused, null, () -> {
        });
    }

    // ------------------------------------------------------------------ customers (WhatsApp)

    /** Links, changes or removes the customer's number of one card. */
    private void linkCustomer(String routerKey, RouterUser card) {
        java.util.Optional<org.netpilot.model.Customer> current;
        try {
            current = context.customerRepository().find(routerKey, card.name());
        } catch (java.io.IOException e) {
            Dialogs.error(workspace.window(), Messages.get("notify.saveFailed"), String.valueOf(e.getMessage()));
            return;
        }
        CustomerContact.ask(workspace, card.name(), current).ifPresent(answer -> {
            if (answer instanceof CustomerContact.Number n) {
                if (CustomerContact.link(workspace, routerKey, card.name(), n.phone())) {
                    workspace.toast("✓ " + Messages.get("notify.linked", card.name()));
                }
            } else {
                CustomerContact.unlink(workspace, routerKey, card.name());
                workspace.toast("✓ " + Messages.get("notify.unlinked", card.name()));
            }
        });
    }

    /**
     * Opens WhatsApp with the card's details for its customer; asks for (and links) a number first
     * when the card has none. The password is read from the router just for the message.
     */
    private void sendToCustomer(RouterClient target, String routerKey, RouterUser card) {
        java.util.Optional<String> phone;
        try {
            phone = context.customerRepository().find(routerKey, card.name())
                    .map(org.netpilot.model.Customer::phone);
        } catch (java.io.IOException e) {
            Dialogs.error(workspace.window(), Messages.get("notify.saveFailed"), String.valueOf(e.getMessage()));
            return;
        }
        if (phone.isEmpty()) {
            phone = CustomerContact.ask(workspace, card.name(), java.util.Optional.empty())
                    .filter(CustomerContact.Number.class::isInstance)
                    .map(a -> ((CustomerContact.Number) a).phone())
                    .filter(p -> CustomerContact.link(workspace, routerKey, card.name(), p));
        }
        phone.ifPresent(number -> run(() -> context.routerUserActions().voucherUsers(target, List.of(card)),
                users -> {
                    if (users.isEmpty()) {
                        Dialogs.error(workspace.window(), Messages.get("notify.cardGone"), card.name());
                    } else {
                        CustomerContact.sendCard(workspace, users.getFirst(), number);
                    }
                }));
    }

    /** {@code 1000M} → {@code 1000 Mbps}; empty → unlimited. */
    private static String speed(String rate) {
        if (rate.isEmpty()) {
            return Messages.get("common.unlimited");
        }
        char unit = rate.charAt(rate.length() - 1);
        return Character.isLetter(unit) ? rate.substring(0, rate.length() - 1) + " " + unit + "bps" : rate + " bps";
    }

    @FXML
    private void onNewProfile() {
        RouterClient target = client;
        if (target == null) {
            return;
        }
        ProfileEditor.show(workspace.window(), null).ifPresent(profile -> run(() -> {
            target.createHotspotProfile(profile);
            return clockUnsafe(target, profile);
        }, unsafe -> afterProfileChange(Messages.get("profile.added", profile.name()), unsafe)));
    }

    /**
     * Card validity counts on the router's clock. A router without a battery clock (like the hEX)
     * starts at a wrong time after a power cut, so without internet time cards could end early or
     * late. Only checked; NetPilot never changes this router setting.
     */
    private static boolean clockUnsafe(RouterClient target, HotspotProfile profile) {
        if (profile.validity().isEmpty()) {
            return false;
        }
        try {
            return !target.clockSynced();
        } catch (RouterException e) {
            LOG.log(System.Logger.Level.DEBUG, "Cannot read the NTP client setting", e);
            return false; // unknown: no warning rather than a wrong one
        }
    }

    @FXML
    private void onEditProfile() {
        RouterClient target = client;
        HotspotProfile selected = profilesTable.getSelectionModel().getSelectedItem();
        if (target == null || selected == null) {
            return;
        }
        ProfileEditor.show(workspace.window(), selected).ifPresent(profile -> run(() -> {
            target.updateHotspotProfile(profile);
            return clockUnsafe(target, profile);
        }, unsafe -> afterProfileChange(Messages.get("profile.saved", profile.name()), unsafe)));
    }

    @FXML
    private void onDeleteProfile() {
        RouterClient target = client;
        HotspotProfile selected = profilesTable.getSelectionModel().getSelectedItem();
        if (target == null || selected == null || selected.builtIn()) {
            return;
        }
        if (!Dialogs.confirm(workspace.window(), Messages.get("profile.delete.title", selected.name()),
                Messages.get("profile.delete.body"))) {
            return;
        }
        run(() -> {
            target.deleteHotspotProfile(selected.routerId());
            return selected;
        }, done -> afterProfileChange(Messages.get("profile.deleted", selected.name())));
    }

    /** Reloads the lists, so Create cards and Voucher design see the change too. */
    private void afterProfileChange(String message) {
        afterProfileChange(message, false);
    }

    /** @param clockUnsafe the profile has a card validity but the router does not set its clock from the internet */
    private void afterProfileChange(String message, boolean clockUnsafe) {
        workspace.router().refresh();
        workspace.toast("✓ " + message);
        if (clockUnsafe) {
            Dialogs.info(workspace.window(), Messages.get("profile.validity.ntp.title"),
                    Messages.get("profile.validity.ntp.body"));
        }
    }

    @FXML
    private void onRefreshActive() {
        readLive(true);
    }

    @FXML
    private void onDisconnectSessions() {
        RouterClient target = client;
        List<ActiveSession> selected = List.copyOf(activeTable.getSelectionModel().getSelectedItems());
        if (target == null || selected.isEmpty()) {
            return;
        }
        String names = selected.stream().map(ActiveSession::user).limit(10).collect(Collectors.joining(", "))
                + (selected.size() > 10 ? ", …" : "");
        if (!Dialogs.confirm(workspace.window(), Messages.get("hotspot.disconnect.title", selected.size()),
                Messages.get("hotspot.disconnect.body") + "\n\n" + names)) {
            return;
        }
        run(() -> context.routerUserActions().disconnect(target, selected), (BulkResult result) -> {
            if (!result.problems().isEmpty()) {
                Dialogs.error(workspace.window(), Messages.get("users.partlyDone", result.succeeded(), result.total()),
                        String.join("\n", result.problems()));
            }
            onRefreshActive();
        });
    }

    private <T> void run(java.util.concurrent.Callable<T> work, java.util.function.Consumer<T> onSuccess) {
        Task<T> task = new Task<>() {

            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnRunning(e -> setBusy(true));
        task.setOnSucceeded(e -> {
            setBusy(false);
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            setBusy(false);
            Throwable error = task.getException();
            LOG.log(error instanceof RouterException ? System.Logger.Level.WARNING : System.Logger.Level.ERROR,
                    "Hotspot action failed", error);
            Dialogs.error(workspace.window(), Messages.get("users.requestFailed"), String.valueOf(error.getMessage()));
        });
        Thread thread = new Thread(task, "netpilot-hotspot");
        thread.setDaemon(true);
        thread.start();
    }

    private void setBusy(boolean busy) {
        activeProgress.setVisible(busy);
        activeProgress.setManaged(busy);
        refreshActiveButton.setDisable(busy);
    }
}
