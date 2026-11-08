package org.netpilot.controller;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.stage.Window;
import org.netpilot.AppContext;
import org.netpilot.i18n.Messages;
import org.netpilot.model.ActiveSession;
import org.netpilot.model.DataSize;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.RouterDuration;
import org.netpilot.model.RouterUser;
import org.netpilot.model.UserFilter;
import org.netpilot.service.router.RouterClient;
import org.netpilot.service.router.RouterException;
import org.netpilot.service.router.RouterUserActions;
import org.netpilot.service.router.RouterUserActions.BulkResult;

/**
 * Hotspot users tab: lists the router's hotspot users grouped by NetPilot batch (like WinBox
 * groups by comment), with live online state, filters, and bulk actions (enable, disable,
 * disconnect, delete, reprint vouchers).
 *
 * <p>Everything is loaded once per refresh and filtered locally, so typing in the search box
 * never sends requests to the router.
 */
public class RouterUsersController {

    private static final System.Logger LOG = System.getLogger(RouterUsersController.class.getName());
    // read when the page is built, so a language switch (which rebuilds pages) shows the new language
    private final String allBatches = Messages.get("users.allBatches");
    private final String allProfiles = Messages.get("users.allProfiles");
    private static final String BATCH_PREFIX = "NetPilot ";

    /** One line of the tree: a batch header or a user. */
    sealed interface Row permits GroupRow, UserRow {
    }

    /** Header of a batch; {@code batch} is empty for users added by hand. */
    record GroupRow(String batch, int count) implements Row {
    }

    record UserRow(RouterUser user) implements Row {
    }

    private final AppContext context;
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final TreeItem<Row> root = new TreeItem<>();

    private List<RouterUser> users = List.of();
    private List<RouterUser> visible = List.of();
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
    private TreeTableView<Row> usersTable;
    @FXML
    private TreeTableColumn<Row, String> nameColumn;
    @FXML
    private TreeTableColumn<Row, Boolean> onlineColumn;
    @FXML
    private TreeTableColumn<Row, String> profileColumn;
    @FXML
    private TreeTableColumn<Row, String> serverColumn;
    @FXML
    private TreeTableColumn<Row, Duration> usedColumn;
    @FXML
    private TreeTableColumn<Row, String> limitColumn;
    @FXML
    private TreeTableColumn<Row, Long> uploadColumn;
    @FXML
    private TreeTableColumn<Row, Long> downloadColumn;
    @FXML
    private TreeTableColumn<Row, String> stateColumn;
    @FXML
    private TreeTableColumn<Row, String> commentColumn;
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
    @FXML
    private Button deleteExpiredButton;

    public RouterUsersController(AppContext context) {
        this.context = context;
    }

    @FXML
    private void initialize() {
        setUpTable();
        statusBox.getItems().setAll(UserFilter.Status.values());
        statusBox.setValue(UserFilter.Status.ALL);
        batchBox.getItems().setAll(allBatches);
        batchBox.setValue(allBatches);
        profileBox.getItems().setAll(allProfiles);
        profileBox.setValue(allProfiles);

        searchField.textProperty().addListener((obs, old, value) -> applyFilter());
        batchBox.valueProperty().addListener((obs, old, value) -> applyFilter());
        profileBox.valueProperty().addListener((obs, old, value) -> applyFilter());
        statusBox.valueProperty().addListener((obs, old, value) -> applyFilter());

        var noSelection = Bindings.isEmpty(usersTable.getSelectionModel().getSelectedItems());
        for (Button action : List.of(enableButton, disableButton, disconnectButton, deleteButton, printButton)) {
            action.disableProperty().bind(noSelection.or(busy));
        }
        refreshButton.disableProperty().bind(busy);
        deleteExpiredButton.disableProperty().bind(busy);
        usersTable.getSelectionModel().getSelectedItems()
                .addListener((ListChangeListener<TreeItem<Row>>) c -> updateSummary());
        enableButton.setGraphic(Icons.of(Icons.CHECK));
        disableButton.setGraphic(Icons.of(Icons.PAUSE));
        disconnectButton.setGraphic(Icons.of(Icons.UNPLUG));
        deleteButton.setGraphic(Icons.of(Icons.CROSS));
        deleteExpiredButton.setGraphic(Icons.of(Icons.CROSS));
        printButton.setGraphic(Icons.of(Icons.PRINTER));
        refreshButton.setGraphic(Icons.of(Icons.REFRESH));
    }

    /**
     * @param printer prints vouchers for the given users (the Create cards page export)
     */
    public void start(RouterClient routerClient, String routerName, Consumer<List<HotspotUser>> voucherPrinter) {
        this.client = routerClient;
        this.printer = voucherPrinter;
        onRefresh();
    }

    private boolean liveRunning;

    /**
     * Reloads users and sessions without any busy indicator or dialog, keeping filters, selection
     * and collapsed batches; skipped while an action runs.
     */
    void liveRefresh() {
        RouterClient target = client;
        if (target == null || busy.get() || liveRunning) {
            return;
        }
        liveRunning = true;
        Task<Snapshot> task = new Task<>() {

            @Override
            protected Snapshot call() throws Exception {
                return new Snapshot(target.hotspotUsers(), target.activeSessions());
            }
        };
        task.setOnSucceeded(e -> {
            liveRunning = false;
            if (client == target && !busy.get() && changed(task.getValue())) {
                show(task.getValue());
            }
        });
        task.setOnFailed(e -> {
            liveRunning = false;
            LOG.log(System.Logger.Level.DEBUG, "Live refresh of users failed", task.getException());
        });
        Thread thread = new Thread(task, "netpilot-users-live");
        thread.setDaemon(true);
        thread.start();
    }

    /** Forgets the router's users after a disconnect. */
    void stop() {
        client = null;
        sessionsByUser = Map.of();
        users = List.of();
        applyFilter();
    }

    // ------------------------------------------------------------------ actions

    @FXML
    private void onRefresh() {
        RouterClient target = client;
        if (target == null) {
            return;
        }
        run(Messages.get("users.loading"), () -> new Snapshot(target.hotspotUsers(), target.activeSessions()),
                this::show);
    }

    @FXML
    private void onClearFilters() {
        searchField.clear();
        batchBox.setValue(allBatches);
        profileBox.setValue(allProfiles);
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
        bulk(Messages.get("users.enabling", selected.size()),
                () -> context.routerUserActions().enable(client, selected));
    }

    @FXML
    private void onDisable() {
        List<RouterUser> selected = selected();
        bulk(Messages.get("users.disabling", selected.size()),
                () -> context.routerUserActions().disable(client, selected));
    }

    @FXML
    private void onDisconnect() {
        List<ActiveSession> sessions = selected().stream()
                .flatMap(u -> sessionsByUser.getOrDefault(u.name(), List.of()).stream())
                .toList();
        if (sessions.isEmpty()) {
            Dialogs.info(stage(), Messages.get("users.noneOnline.title"), Messages.get("users.noneOnline.body"));
            return;
        }
        bulk(Messages.get("users.disconnecting", sessions.size()),
                () -> context.routerUserActions().disconnect(client, sessions));
    }

    @FXML
    private void onDelete() {
        List<RouterUser> selected = selected();
        String names = selected.stream().limit(8).map(RouterUser::name).collect(Collectors.joining(", "))
                + (selected.size() > 8 ? ", …" : "");
        if (!Dialogs.confirm(stage(), Messages.get("users.delete.title", selected.size()),
                names + "\n\n" + Messages.get("users.delete.body"))) {
            return;
        }
        bulk(Messages.get("users.deleting", selected.size()),
                () -> context.routerUserActions().delete(client, selected));
    }

    /**
     * Deletes every card that used up its time. Their sales are saved first, so the Earnings page
     * still counts them after they are gone from the router.
     */
    @FXML
    private void onDeleteExpired() {
        List<RouterUser> expired = RouterUserActions.expired(users);
        if (expired.isEmpty()) {
            Dialogs.info(stage(), Messages.get("users.deleteExpired.none.title"),
                    Messages.get("users.deleteExpired.none.body"));
            return;
        }
        if (!Dialogs.confirm(stage(), Messages.get("users.deleteExpired.title", expired.size()),
                Messages.get("users.deleteExpired.body"))) {
            return;
        }
        List<RouterUser> all = users;
        bulk(Messages.get("users.deleting", expired.size()), () -> {
            String router = client.endpoint().address();
            context.usageRepository().saveAll(context.reportService().track(router, all,
                    context.usageRepository().findAll(), LocalDate.now()));
            return context.routerUserActions().delete(client, expired);
        });
    }

    @FXML
    private void onPrint() {
        List<RouterUser> selected = selected();
        run(Messages.get("users.readingPasswords", selected.size()),
                () -> context.routerUserActions().voucherUsers(client, selected), vouchers -> {
                    if (vouchers.isEmpty()) {
                        Dialogs.info(stage(), Messages.get("users.nothingToPrint.title"),
                                Messages.get("users.nothingToPrint.body"));
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
        users = List.copyOf(snapshot.users());
        keepChoices(batchBox, allBatches, users.stream()
                .map(RouterUser::comment)
                .filter(c -> c.startsWith(BATCH_PREFIX))
                .distinct()
                .sorted(Comparator.reverseOrder()) // newest batch first
                .toList());
        keepChoices(profileBox, allProfiles, users.stream().map(RouterUser::profile).distinct().sorted().toList());
        placeholderLabel.setText(Messages.get("users.noMatch"));
        applyFilter();
    }

    /** Nothing changed: leave the table alone (no flicker). */
    private boolean changed(Snapshot snapshot) {
        return !snapshot.users().equals(users) || !snapshot.sessions().stream()
                .collect(Collectors.groupingBy(ActiveSession::user)).equals(sessionsByUser);
    }

    /** Replaces the choices but keeps the current selection if it still exists. */
    private static void keepChoices(ComboBox<String> box, String all, List<String> values) {
        String current = box.getValue();
        box.getItems().setAll(all);
        box.getItems().addAll(values);
        box.setValue(box.getItems().contains(current) ? current : all);
    }

    private void applyFilter() {
        String batch = allBatches.equals(batchBox.getValue()) ? "" : batchBox.getValue();
        String profile = allProfiles.equals(profileBox.getValue()) ? "" : profileBox.getValue();
        UserFilter filter = new UserFilter(searchField.getText(), batch, profile, statusBox.getValue());
        Set<String> online = sessionsByUser.keySet();
        visible = users.stream().filter(user -> filter.matches(user, online)).toList();
        rebuildTree();
        updateSummary();
    }

    /** Newest NetPilot batch first; users added by hand (or by other tools) last. */
    private void rebuildTree() {
        Map<String, List<RouterUser>> groups = new LinkedHashMap<>();
        visible.stream()
                .map(RouterUsersController::batchOf)
                .distinct()
                .sorted(Comparator.comparing(String::isEmpty).thenComparing(Comparator.reverseOrder()))
                .forEach(batch -> groups.put(batch, new ArrayList<>()));
        visible.forEach(user -> groups.get(batchOf(user)).add(user));

        List<TreeItem<Row>> items = new ArrayList<>();
        groups.forEach((batch, members) -> {
            TreeItem<Row> group = new TreeItem<>(new GroupRow(batch, members.size()));
            group.setExpanded(true);
            members.forEach(user -> group.getChildren().add(new TreeItem<>(new UserRow(user))));
            items.add(group);
        });
        // keep what the user selected and which batches they folded
        Set<String> selectedNames = selected().stream().map(RouterUser::name).collect(Collectors.toSet());
        Set<String> selectedBatches = new java.util.HashSet<>();
        Set<String> collapsed = new java.util.HashSet<>();
        for (TreeItem<Row> group : root.getChildren()) {
            if (group.getValue() instanceof GroupRow(String batch, int count)) {
                if (!group.isExpanded()) {
                    collapsed.add(batch);
                }
                if (usersTable.getSelectionModel().getSelectedItems().contains(group)) {
                    selectedBatches.add(batch);
                }
            }
        }
        usersTable.getSelectionModel().clearSelection();
        root.getChildren().setAll(items);
        usersTable.sort();
        for (TreeItem<Row> group : items) {
            String batch = ((GroupRow) group.getValue()).batch();
            group.setExpanded(!collapsed.contains(batch));
            if (selectedBatches.contains(batch)) {
                usersTable.getSelectionModel().select(group);
            }
            for (TreeItem<Row> child : group.getChildren()) {
                if (child.getValue() instanceof UserRow(RouterUser user) && selectedNames.contains(user.name())
                        && !selectedBatches.contains(batch)) {
                    usersTable.getSelectionModel().select(child);
                }
            }
        }
    }

    private static String batchOf(RouterUser user) {
        return user.comment().startsWith(BATCH_PREFIX) ? user.comment() : "";
    }

    private void updateSummary() {
        long online = users.stream().filter(u -> sessionsByUser.containsKey(u.name())).count();
        long expired = users.stream().filter(RouterUser::expired).count();
        int selectedCount = selected().size();
        String summary = Messages.get("users.summary", users.size(), online, expired, visible.size());
        summaryLabel
                .setText(selectedCount > 0 ? summary + " · " + Messages.get("users.selected", selectedCount) : summary);
    }

    /** Selected users; a selected batch header stands for all its users. */
    private List<RouterUser> selected() {
        Set<RouterUser> result = new LinkedHashSet<>();
        for (TreeItem<Row> item : usersTable.getSelectionModel().getSelectedItems()) {
            if (item == null) {
                continue;
            }
            if (item.getValue() instanceof UserRow(RouterUser user)) {
                result.add(user);
            } else {
                item.getChildren().forEach(child -> {
                    if (child.getValue() instanceof UserRow(RouterUser user)) {
                        result.add(user);
                    }
                });
            }
        }
        return List.copyOf(result);
    }

    // ------------------------------------------------------------------ background work

    private void bulk(String message, Callable<BulkResult> work) {
        run(message, work, result -> {
            if (!result.problems().isEmpty()) {
                Dialogs.error(stage(), Messages.get("users.partlyDone", result.succeeded(), result.total()),
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
            placeholderLabel.setText(Messages.get("users.loadFailed"));
            Dialogs.error(stage(), Messages.get("users.requestFailed"), String.valueOf(error.getMessage()));
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
        usersTable.setRoot(root);
        usersTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        usersTable.setRowFactory(table -> new TreeTableRow<>() {

            @Override
            protected void updateItem(Row item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("group-row");
                if (!empty && item instanceof GroupRow) {
                    getStyleClass().add("group-row");
                }
            }
        });

        nameColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(switch (c.getValue().getValue()) {
            case GroupRow g ->
                (g.batch().isEmpty() ? Messages.get("users.manualGroup") : g.batch()) + "  ·  " + g.count();
            case UserRow u -> u.user().name();
        }));
        // "● Online" in green or "● Offline" in red; empty on batch rows. Sorting puts online first.
        onlineColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                user(c).map(u -> sessionsByUser.containsKey(u.name())).orElse(null)));
        onlineColumn.setCellFactory(column -> new TreeTableCell<>() {

            @Override
            protected void updateItem(Boolean online, boolean empty) {
                super.updateItem(online, empty);
                getStyleClass().removeAll("state-online", "state-offline");
                if (empty || online == null) {
                    setText(null);
                    return;
                }
                setText("● " + Messages.get(online ? "users.online" : "users.offline"));
                getStyleClass().add(online ? "state-online" : "state-offline");
            }
        });
        onlineColumn.setComparator(java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()));
        profileColumn.setCellValueFactory(c -> text(c, RouterUser::profile));
        serverColumn.setCellValueFactory(c -> text(c, RouterUser::server));

        usedColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(user(c).map(RouterUser::uptime).orElse(null)));
        usedColumn.setCellFactory(column -> new FormattedCell<>(RouterDuration::clock));

        limitColumn.setCellValueFactory(c -> text(c, u -> u.limit().map(RouterDuration::format).orElse("∞")));

        // like WinBox: Bytes In is what the customer sent (upload), Bytes Out what they received
        uploadColumn.setCellValueFactory(
                c -> new ReadOnlyObjectWrapper<>(user(c).map(RouterUser::bytesIn).orElse(null)));
        uploadColumn.setCellFactory(column -> new FormattedCell<>(DataSize::format));
        downloadColumn.setCellValueFactory(
                c -> new ReadOnlyObjectWrapper<>(user(c).map(RouterUser::bytesOut).orElse(null)));
        downloadColumn.setCellFactory(column -> new FormattedCell<>(DataSize::format));
        stateColumn.setCellValueFactory(c -> text(c, RouterUsersController::state));
        commentColumn.setCellValueFactory(c -> text(c, RouterUser::comment));
    }

    private static java.util.Optional<RouterUser> user(TreeTableColumn.CellDataFeatures<Row, ?> cell) {
        return cell.getValue().getValue() instanceof UserRow(RouterUser user)
                ? java.util.Optional.of(user)
                : java.util.Optional.empty();
    }

    private static ReadOnlyStringWrapper text(TreeTableColumn.CellDataFeatures<Row, String> cell,
            Function<RouterUser, String> value) {
        return new ReadOnlyStringWrapper(user(cell).map(value).orElse(""));
    }

    private static String state(RouterUser user) {
        if (user.builtIn()) {
            return Messages.get("users.state.builtIn");
        }
        if (user.disabled()) {
            return Messages.get("users.state.disabled");
        }
        if (user.expired()) {
            return Messages.get("users.state.expired");
        }
        return Messages.get(user.neverUsed() ? "users.state.new" : "users.state.inUse");
    }

    /** {@code 0 B}, {@code 512 KB}, {@code 1.2 GB}. */
    static String formatBytes(long bytes) {
        return DataSize.format(bytes);
    }

    /** Shows a sortable value (duration, bytes) as formatted text. */
    private static final class FormattedCell<T> extends TreeTableCell<Row, T> {

        private final Function<T, String> format;

        FormattedCell(Function<T, String> format) {
            this.format = format;
        }

        @Override
        protected void updateItem(T item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : format.apply(item));
        }
    }

    private Window stage() {
        return usersTable.getScene().getWindow();
    }
}
