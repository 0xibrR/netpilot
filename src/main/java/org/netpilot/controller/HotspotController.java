package org.netpilot.controller;

import java.util.List;
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

    private final AppContext context;

    private Workspace workspace;
    private RouterClient client;
    private boolean activeLoaded;

    @FXML
    private TabPane tabs;
    @FXML
    private Tab activeTab;
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
    private TableColumn<HotspotProfile, String> profileRateColumn;
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
                setText(empty || item == null ? null : RouterDuration.format(item));
            }
        });
        kickButton.disableProperty().bind(Bindings.isEmpty(activeTable.getSelectionModel().getSelectedItems()));

        profileNameColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name()));
        profileRateColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().rateLimit().isEmpty() ? Messages.get("common.unlimited") : c.getValue().rateLimit()));
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

    /** Prices may have changed in Voucher Design. */
    @Override
    public void shown() {
        profilesTable.refresh();
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
        profilesTable.getItems().setAll(lists.profiles());
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
