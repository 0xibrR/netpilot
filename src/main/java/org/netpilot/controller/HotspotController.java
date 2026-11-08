package org.netpilot.controller;

import java.util.List;
import java.util.Set;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.netpilot.AppContext;
import org.netpilot.i18n.Messages;
import org.netpilot.model.ActiveSession;
import org.netpilot.model.HotspotProfile;
import org.netpilot.model.HotspotServer;
import org.netpilot.model.RouterDuration;
import org.netpilot.service.router.RouterClient;
import org.netpilot.service.router.RouterException;
import org.netpilot.service.router.RouterUserActions.BulkResult;

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
    private Label activeSummary;
    @FXML
    private TableView<ActiveSession> activeTable;
    @FXML
    private TableColumn<ActiveSession, String> activeUserColumn;
    @FXML
    private TableColumn<ActiveSession, String> activeAddressColumn;
    @FXML
    private TableColumn<ActiveSession, String> activeMacColumn;
    @FXML
    private TableColumn<ActiveSession, java.time.Duration> activeUptimeColumn;

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
        activeTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        activeUserColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().user()));
        activeUserColumn.getStyleClass().add("monospace");
        activeAddressColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().address()));
        activeMacColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().macAddress()));
        activeUptimeColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().uptime()));
        activeUptimeColumn.setCellFactory(column -> new TableCell<>() {

            @Override
            protected void updateItem(java.time.Duration item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : RouterDuration.clock(item));
            }
        });
        kickButton.disableProperty().bind(Bindings.isEmpty(activeTable.getSelectionModel().getSelectedItems()));

        profileNameColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name()));
        profileDownloadColumn
                .setCellValueFactory(c -> new ReadOnlyStringWrapper(speed(c.getValue().speed().download())));
        profileUploadColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(speed(c.getValue().speed().upload())));
        profileSharedColumn.setCellValueFactory(
                c -> new ReadOnlyStringWrapper(String.valueOf(c.getValue().sharedUsers())));
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

    private boolean activeLive;

    /** Every few seconds: users, online sessions (when that tab is open) and the profile lists. */
    @Override
    public void tick() {
        RouterClient target = client;
        if (target == null) {
            return;
        }
        usersController.liveRefresh(); // profiles and servers are refreshed for every page by the main window
        if (tabs.getSelectionModel().getSelectedItem() == activeTab && !activeLive) {
            activeLive = true;
            Task<List<ActiveSession>> task = new Task<>() {

                @Override
                protected List<ActiveSession> call() throws Exception {
                    return target.activeSessions();
                }
            };
            task.setOnSucceeded(e -> {
                activeLive = false;
                if (client == target && !task.getValue().equals(activeTable.getItems())) {
                    Set<String> selected = activeTable.getSelectionModel().getSelectedItems().stream()
                            .map(ActiveSession::routerId).collect(java.util.stream.Collectors.toSet());
                    activeTable.getItems().setAll(task.getValue());
                    activeTable.getItems().stream().filter(s -> selected.contains(s.routerId()))
                            .forEach(s -> activeTable.getSelectionModel().select(s));
                    activeSummary.setText(Messages.get("hotspot.activeCount", task.getValue().size()));
                }
            });
            task.setOnFailed(e -> activeLive = false);
            Thread thread = new Thread(task, "netpilot-active-live");
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Override
    public void routerConnected(RouterClient routerClient, String name) {
        this.client = routerClient;
        activeLoaded = false;
        activeTable.getItems().clear();
        usersController.start(routerClient, name, workspace::printVouchers);
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
        usersController.stop();
        activeTable.getItems().clear();
        profilesTable.getItems().clear();
        serversTable.getItems().clear();
    }

    // ------------------------------------------------------------------ profiles (speeds are the user's)

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
        ProfileEditor.show(workspace.window(), null)
                .ifPresent(profile -> run(() -> target.createHotspotProfile(profile),
                        id -> afterProfileChange(Messages.get("profile.added", profile.name()))));
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
            return profile;
        }, done -> afterProfileChange(Messages.get("profile.saved", profile.name()))));
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
        workspace.router().refresh();
        workspace.toast("✓ " + message);
    }

    @FXML
    private void onRefreshActive() {
        RouterClient target = client;
        if (target == null) {
            return;
        }
        run(target::activeSessions, sessions -> {
            activeLoaded = true;
            activeTable.getItems().setAll(sessions);
            activeSummary.setText(Messages.get("hotspot.activeCount", sessions.size()));
        });
    }

    @FXML
    private void onDisconnectSessions() {
        RouterClient target = client;
        List<ActiveSession> selected = List.copyOf(activeTable.getSelectionModel().getSelectedItems());
        if (target == null || selected.isEmpty()) {
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
