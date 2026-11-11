package org.netpilot.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import org.netpilot.AppContext;
import org.netpilot.i18n.Messages;
import org.netpilot.model.LogEntry;
import org.netpilot.service.router.RouterClient;
import org.netpilot.service.router.RouterException;
import org.netpilot.service.tools.LogFeed;

/**
 * "Router log" page (developer tools): the router's log, read only. New lines are appended every
 * few seconds while the page is open; the table follows the newest line unless the user scrolled
 * up to read. Errors are red, warnings amber; lines can be filtered, searched and saved to a file.
 */
public class RouterLogController implements Page {

    private static final System.Logger LOG = System.getLogger(RouterLogController.class.getName());
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private Workspace workspace;
    private RouterClient client;
    private final LogFeed feed = new LogFeed();
    private final ObservableList<LogEntry> lines = FXCollections.observableArrayList();
    private final FilteredList<LogEntry> shown = new FilteredList<>(lines);
    private boolean reading;

    @FXML
    private ComboBox<LogEntry.Filter> filterBox;
    @FXML
    private TextField searchField;
    @FXML
    private ProgressIndicator progress;
    @FXML
    private Label countLabel;
    @FXML
    private Button refreshButton;
    @FXML
    private Button saveButton;
    @FXML
    private Label problemLabel;
    @FXML
    private Label placeholderLabel;
    @FXML
    private TableView<LogEntry> logTable;
    @FXML
    private TableColumn<LogEntry, String> timeColumn;
    @FXML
    private TableColumn<LogEntry, String> topicsColumn;
    @FXML
    private TableColumn<LogEntry, String> messageColumn;

    public RouterLogController(AppContext context) {
        // the router comes from the shared connection; nothing else is needed
    }

    @FXML
    private void initialize() {
        logTable.setItems(shown);
        timeColumn.setCellValueFactory(c -> text(c.getValue().time()));
        topicsColumn.setCellValueFactory(c -> text(String.join(", ", c.getValue().topics())));
        messageColumn.setCellValueFactory(c -> text(c.getValue().message()));
        logTable.setRowFactory(table -> new TableRow<>() {

            @Override
            protected void updateItem(LogEntry item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("log-error", "log-warning");
                if (!empty && item != null) {
                    switch (item.severity()) {
                        case ERROR -> getStyleClass().add("log-error");
                        case WARNING -> getStyleClass().add("log-warning");
                        default -> {
                            // an ordinary line keeps the table's colours
                        }
                    }
                }
            }
        });

        filterBox.getItems().setAll(LogEntry.Filter.values());
        filterBox.setConverter(new StringConverter<>() {

            @Override
            public String toString(LogEntry.Filter filter) {
                return filter == null ? "" : Messages.get("log.filter." + filter.name());
            }

            @Override
            public LogEntry.Filter fromString(String text) {
                return LogEntry.Filter.ALL;
            }
        });
        filterBox.setValue(LogEntry.Filter.ALL);
        filterBox.valueProperty().addListener((obs, old, filter) -> applyFilter());
        searchField.textProperty().addListener((obs, old, text) -> applyFilter());
        saveButton.setGraphic(Icons.of(Icons.DOWNLOAD));
        updateState();
    }

    @Override
    public void attach(Workspace shared) {
        this.workspace = shared;
        shared.router().client().ifPresent(c -> client = c);
        updateState();
    }

    @Override
    public void shown() {
        read(true);
    }

    /** Every few seconds while the page is open: append the new lines. */
    @Override
    public void tick() {
        read(false);
    }

    @Override
    public void routerConnected(RouterClient routerClient, String name) {
        client = routerClient;
        feed.clear();
        lines.clear();
        updateState();
    }

    @Override
    public void routerDisconnected() {
        client = null;
        updateState();
    }

    @FXML
    private void onRefresh() {
        read(true);
    }

    /** @param visible show the spinner (opening the page, Refresh) rather than reading quietly */
    private void read(boolean visible) {
        RouterClient target = client;
        if (target == null || reading) {
            return;
        }
        reading = true;
        Task<List<LogEntry>> task = new Task<>() {

            @Override
            protected List<LogEntry> call() throws Exception {
                return feed.add(target.log());
            }
        };
        if (visible) {
            task.setOnRunning(e -> showBusy(true));
        }
        task.setOnSucceeded(e -> {
            reading = false;
            showBusy(false);
            if (client == target) {
                showProblem(null);
                append(task.getValue());
            }
        });
        task.setOnFailed(e -> {
            reading = false;
            showBusy(false);
            Throwable error = task.getException();
            LOG.log(error instanceof RouterException ? System.Logger.Level.WARNING : System.Logger.Level.ERROR,
                    "Reading the router log failed", error);
            if (client == target) {
                showProblem(Messages.get("log.failed", String.valueOf(error.getMessage())));
            }
        });
        Thread thread = new Thread(task, "netpilot-router-log");
        thread.setDaemon(true);
        thread.start();
    }

    /** Adds new lines; follows the newest one only when the user was already looking at the end. */
    private void append(List<LogEntry> fresh) {
        if (fresh.isEmpty()) {
            updateState();
            return;
        }
        boolean follow = atEnd();
        lines.addAll(fresh);
        if (lines.size() > feed.limit()) {
            lines.remove(0, lines.size() - feed.limit());
        }
        updateState();
        if (follow && !shown.isEmpty()) {
            logTable.scrollTo(shown.size() - 1);
        }
    }

    private boolean atEnd() {
        if (shown.isEmpty()) {
            return true;
        }
        for (javafx.scene.Node node : logTable.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == javafx.geometry.Orientation.VERTICAL
                    && bar.isVisible()) {
                return bar.getValue() >= bar.getMax() - 0.01;
            }
        }
        return true; // no scroll bar: everything fits
    }

    private void applyFilter() {
        LogEntry.Filter filter = filterBox.getValue() == null ? LogEntry.Filter.ALL : filterBox.getValue();
        String query = searchField.getText();
        shown.setPredicate(entry -> filter.test(entry) && entry.matches(query));
        updateState();
        if (!shown.isEmpty()) {
            logTable.scrollTo(shown.size() - 1);
        }
    }

    @FXML
    private void onSave() {
        if (shown.isEmpty() || workspace == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("log.save"));
        chooser.setInitialFileName("router-log-" + LocalDateTime.now().format(FILE_STAMP) + ".txt");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(Messages.get("log.file"), "*.txt"));
        java.io.File file = chooser.showSaveDialog(workspace.window());
        if (file == null) {
            return;
        }
        Path path = file.toPath();
        try {
            Files.write(path, shown.stream().map(LogEntry::line).toList(), StandardCharsets.UTF_8);
            workspace.toast("✓ " + Messages.get("log.saved", shown.size(), path.getFileName()));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Saving the router log failed", e);
            Dialogs.error(workspace.window(), Messages.get("log.save"), String.valueOf(e.getMessage()));
        }
    }

    private void updateState() {
        boolean connected = client != null;
        refreshButton.setDisable(!connected);
        saveButton.setDisable(shown.isEmpty());
        countLabel.setText(lines.isEmpty() ? "" : Messages.get("log.count", shown.size(), lines.size()));
        placeholderLabel.setText(Messages.get(connected ? "log.empty" : "log.noRouter"));
    }

    private void showBusy(boolean busy) {
        progress.setVisible(busy);
        progress.setManaged(busy);
    }

    private void showProblem(String message) {
        boolean show = message != null && !message.isBlank();
        problemLabel.setText(show ? message : "");
        problemLabel.setVisible(show);
        problemLabel.setManaged(show);
    }

    private static ReadOnlyStringWrapper text(String value) {
        return new ReadOnlyStringWrapper(value);
    }
}
