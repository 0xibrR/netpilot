package org.netpilot.controller;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Collectors;
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
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.netpilot.AppContext;
import org.netpilot.model.ActiveSession;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.RouterDuration;
import org.netpilot.model.RouterUser;
import org.netpilot.model.UserFilter;
import org.netpilot.service.router.RouterClient;
import org.netpilot.service.router.RouterException;
import org.netpilot.service.router.RouterUserActions.BulkResult;

/**
 * "Router Users" window: lists the router's hotspot users with live online state, filters them,
 * and runs bulk actions (enable, disable, disconnect, delete, reprint vouchers).
 *
 * <p>Everything is loaded once per refresh and filtered locally, so typing in the search box
 * never sends requests to the router.
 */
public class RouterUsersController {

    private static final System.Logger LOG = System.getLogger(RouterUsersController.class.getName());
    private static final String ALL_BATCHES = "All batches";
    private static final String ALL_PROFILES = "All profiles";
    private static final String BATCH_PREFIX = "NetPilot ";

    private final AppContext context;
    private final ObservableList<RouterUser> users = FXCollections.observableArrayList();
    private final FilteredList<RouterUser> filtered = new FilteredList<>(users, u -> true);
    private final BooleanProperty busy = new SimpleBooleanProperty(false);

    private RouterClient client;
    private Consumer<List<HotspotUser>> printer;
    private Map<String, List<ActiveSession>> sessionsByUser = Map.of();

    @FXML
    private TextField searchField;
    @FXML
    private ComboBox<String> batchBox;
    @FXML
    private ComboBox<String> profileBox;
    @FXML
    private ComboBox<UserFilter.Status> statusBox;
    @FXML
    private ProgressIndicator progressIndicator;
    @FXML
    private Button refreshButton;
    @FXML
    private TableView<RouterUser> usersTable;
    @FXML
    private TableColumn<RouterUser, String> onlineColumn;
    @FXML
    private TableColumn<RouterUser, String> nameColumn;
    @FXML
    private TableColumn<RouterUser, String> profileColumn;
    @FXML
    private TableColumn<RouterUser, String> serverColumn;
    @FXML
    private TableColumn<RouterUser, java.time.Duration> usedColumn;
    @FXML
    private TableColumn<RouterUser, String> limitColumn;
    @FXML
    private TableColumn<RouterUser, Long> dataColumn;
    @FXML
    private TableColumn<RouterUser, String> stateColumn;
    @FXML
    private TableColumn<RouterUser, String> commentColumn;
    @FXML
    private Label placeholderLabel;
    @FXML
    private Label summaryLabel;
    @FXML
    private Button enableButton;
    @FXML
    private Button disableButton;
    @FXML
    private Button disconnectButton;
    @FXML
    private Button deleteButton;
    @FXML
    private Button printButton;

    public RouterUsersController(AppContext context) {
        this.context = context;
    }

    @FXML
    private void initialize() {
        setUpTable();
        statusBox.getItems().setAll(UserFilter.Status.values());
        statusBox.setValue(UserFilter.Status.ALL);
        batchBox.getItems().setAll(ALL_BATCHES);
        batchBox.setValue(ALL_BATCHES);
        profileBox.getItems().setAll(ALL_PROFILES);
        profileBox.setValue(ALL_PROFILES);

        searchField.textProperty().addListener((obs, old, value) -> applyFilter());
        batchBox.valueProperty().addListener((obs, old, value) -> applyFilter());
        profileBox.valueProperty().addListener((obs, old, value) -> applyFilter());
        statusBox.valueProperty().addListener((obs, old, value) -> applyFilter());

        var noSelection = Bindings.isEmpty(usersTable.getSelectionModel().getSelectedItems());
        for (Button action : List.of(enableButton, disableButton, disconnectButton, deleteButton, printButton)) {
            action.disableProperty().bind(noSelection.or(busy));
        }
        refreshButton.disableProperty().bind(busy);
        usersTable.getSelectionModel().getSelectedItems()
                .addListener((javafx.collections.ListChangeListener<RouterUser>) c -> updateSummary());
    }

    /**
     * @param printer prints vouchers for the given users (the main window's export)
     */
    public void start(RouterClient routerClient, String routerName, Consumer<List<HotspotUser>> voucherPrinter) {
        this.client = routerClient;
        this.printer = voucherPrinter;
        stage().setTitle("Router Users - " + routerName);
        onRefresh();
    }

    // ------------------------------------------------------------------ actions

    @FXML
    private void onRefresh() {
        run("Loading users…", () -> new Snapshot(client.hotspotUsers(), client.activeSessions()), this::show);
    }

    @FXML
    private void onClearFilters() {
        searchField.clear();
        batchBox.setValue(ALL_BATCHES);
        profileBox.setValue(ALL_PROFILES);
        statusBox.setValue(UserFilter.Status.ALL);
    }

    @FXML
    private void onSelectAll() {
        usersTable.getSelectionModel().selectAll();
        usersTable.requestFocus();
    }

    @FXML
    private void onEnable() {
        List<RouterUser> selected = selected();
        bulk("Enabling " + selected.size() + " users…", () -> context.routerUserActions().enable(client, selected));
    }

    @FXML
    private void onDisable() {
        List<RouterUser> selected = selected();
        bulk("Disabling " + selected.size() + " users…", () -> context.routerUserActions().disable(client, selected));
    }

    @FXML
    private void onDisconnect() {
        List<ActiveSession> sessions = selected().stream()
                .flatMap(u -> sessionsByUser.getOrDefault(u.name(), List.of()).stream())
                .toList();
        if (sessions.isEmpty()) {
            Dialogs.info(stage(), "Nobody to disconnect", "None of the selected users is online right now.");
            return;
        }
        bulk("Disconnecting " + sessions.size() + " sessions…",
                () -> context.routerUserActions().disconnect(client, sessions));
    }

    @FXML
    private void onDelete() {
        List<RouterUser> selected = selected();
        String names = selected.stream().limit(8).map(RouterUser::name).collect(Collectors.joining(", "))
                + (selected.size() > 8 ? ", …" : "");
        if (!Dialogs.confirm(stage(), "Delete " + selected.size() + " users from the router?",
                names + "\n\nThis cannot be undone. Online users lose their connection.")) {
            return;
        }
        bulk("Deleting " + selected.size() + " users…", () -> context.routerUserActions().delete(client, selected));
    }

    @FXML
    private void onPrint() {
        List<RouterUser> selected = selected();
        run("Reading passwords for " + selected.size() + " vouchers…",
                () -> context.routerUserActions().voucherUsers(client, selected), vouchers -> {
                    if (vouchers.isEmpty()) {
                        Dialogs.info(stage(), "Nothing to print", "The selected users no longer exist on the router.");
                    } else {
                        printer.accept(vouchers);
                    }
                });
    }

    // ------------------------------------------------------------------ data

    /** Everything one refresh reads from the router. */
    private record Snapshot(List<RouterUser> users, List<ActiveSession> sessions) {
    }

    private void show(Snapshot snapshot) {
        sessionsByUser = snapshot.sessions().stream().collect(Collectors.groupingBy(ActiveSession::user));
        users.setAll(snapshot.users());
        keepChoices(batchBox, ALL_BATCHES, snapshot.users().stream()
                .map(RouterUser::comment)
                .filter(c -> c.startsWith(BATCH_PREFIX))
                .distinct()
                .sorted(Comparator.reverseOrder()) // newest batch first
                .toList());
        keepChoices(profileBox, ALL_PROFILES,
                snapshot.users().stream().map(RouterUser::profile).distinct().sorted().toList());
        placeholderLabel.setText("No users match the filters.");
        applyFilter();
    }

    /** Replaces the choices but keeps the current selection if it still exists. */
    private static void keepChoices(ComboBox<String> box, String all, List<String> values) {
        String current = box.getValue();
        box.getItems().setAll(all);
        box.getItems().addAll(values);
        box.setValue(box.getItems().contains(current) ? current : all);
    }

    private void applyFilter() {
        String batch = ALL_BATCHES.equals(batchBox.getValue()) ? "" : batchBox.getValue();
        String profile = ALL_PROFILES.equals(profileBox.getValue()) ? "" : profileBox.getValue();
        UserFilter filter = new UserFilter(searchField.getText(), batch, profile, statusBox.getValue());
        Set<String> online = sessionsByUser.keySet();
        filtered.setPredicate(user -> filter.matches(user, online));
        updateSummary();
    }

    private void updateSummary() {
        long online = users.stream().filter(u -> sessionsByUser.containsKey(u.name())).count();
        long expired = users.stream().filter(RouterUser::expired).count();
        int selectedCount = usersTable.getSelectionModel().getSelectedItems().size();
        summaryLabel.setText(users.size() + " users · " + online + " online · " + expired + " expired · showing "
                + filtered.size() + (selectedCount > 0 ? " · " + selectedCount + " selected" : ""));
    }

    private List<RouterUser> selected() {
        return List.copyOf(usersTable.getSelectionModel().getSelectedItems());
    }

    // ------------------------------------------------------------------ background work

    private void bulk(String message, Callable<BulkResult> work) {
        run(message, work, result -> {
            if (!result.problems().isEmpty()) {
                Dialogs.error(stage(), result.succeeded() + " of " + result.total() + " done",
                        String.join("\n", result.problems()));
            }
            onRefresh();
        });
    }

    private <T> void run(String message, Callable<T> work, Consumer<T> onSuccess) {
        Task<T> task = new Task<>() {

            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnRunning(e -> setBusy(true, message));
        task.setOnSucceeded(e -> {
            setBusy(false, null);
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            setBusy(false, null);
            Throwable error = task.getException();
            LOG.log(error instanceof RouterException ? System.Logger.Level.WARNING : System.Logger.Level.ERROR,
                    "Router users action failed", error);
            placeholderLabel.setText("Could not load users.");
            Dialogs.error(stage(), "The router did not complete the request", String.valueOf(error.getMessage()));
        });
        Thread thread = new Thread(task, "netpilot-router-users");
        thread.setDaemon(true);
        thread.start();
    }

    private void setBusy(boolean value, String message) {
        busy.set(value);
        progressIndicator.setVisible(value);
        progressIndicator.setManaged(value);
        if (message != null) {
            summaryLabel.setText(message);
        } else {
            updateSummary();
        }
    }

    // ------------------------------------------------------------------ table

    private void setUpTable() {
        SortedList<RouterUser> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(usersTable.comparatorProperty());
        usersTable.setItems(sorted);
        usersTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        onlineColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                sessionsByUser.containsKey(c.getValue().name()) ? "●" : ""));
        onlineColumn.getStyleClass().add("online-dot");
        nameColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name()));
        nameColumn.getStyleClass().add("monospace");
        profileColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().profile()));
        serverColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().server()));

        usedColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().uptime()));
        usedColumn.setCellFactory(column -> new FormattedCell<>(RouterDuration::format));

        limitColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().limit().map(RouterDuration::format).orElse("∞")));

        dataColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().totalBytes()));
        dataColumn.setCellFactory(column -> new FormattedCell<>(RouterUsersController::formatBytes));

        stateColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(state(c.getValue())));
        commentColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().comment()));
    }

    private static String state(RouterUser user) {
        if (user.disabled()) {
            return "Disabled";
        }
        if (user.expired()) {
            return "Expired";
        }
        return user.neverUsed() ? "New" : "In use";
    }

    /** {@code 0 B}, {@code 512 KB}, {@code 1.2 GB}. */
    static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return (value < 10 ? String.format("%.1f", value) : String.format("%.0f", value)) + " " + units[unit];
    }

    /** Shows a sortable value (duration, bytes) as formatted text. */
    private static final class FormattedCell<T> extends javafx.scene.control.TableCell<RouterUser, T> {

        private final java.util.function.Function<T, String> format;

        FormattedCell(java.util.function.Function<T, String> format) {
            this.format = format;
        }

        @Override
        protected void updateItem(T item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : format.apply(item));
        }
    }

    private Stage stage() {
        return (Stage) usersTable.getScene().getWindow();
    }
}
