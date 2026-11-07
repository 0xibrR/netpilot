package org.netpilot.controller;
import java.io.IOException;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.stage.Window;
import org.netpilot.AppContext;
import org.netpilot.i18n.Messages;
import org.netpilot.model.HistoryRecord;

/**
 * "Export history" page.
 */
public class HistoryController implements Page {

    private static final System.Logger LOG = System.getLogger(HistoryController.class.getName());
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm");

    private final AppContext context;

    @FXML
    private TableView<HistoryRecord> historyTable;
    @FXML
    private TableColumn<HistoryRecord, String> dateColumn;
    @FXML
    private TableColumn<HistoryRecord, Integer> countColumn;
    @FXML
    private TableColumn<HistoryRecord, String> profileColumn;
    @FXML
    private TableColumn<HistoryRecord, String> folderColumn;
    @FXML
    private Label summaryLabel;
    @FXML
    private Button clearButton;
    @FXML
    private Button openButton;

    public HistoryController(AppContext context) {
        this.context = context;
    }

    @FXML
    private void initialize() {
        dateColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().createdAt().format(DATE)));
        countColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().userCount()));
        profileColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().profile()));
        folderColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().folder().toString()));

        historyTable.setRowFactory(table -> {
            TableRow<HistoryRecord> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    openFolder(row.getItem());
                }
            });
            return row;
        });
        openButton.disableProperty().bind(historyTable.getSelectionModel().selectedItemProperty().isNull());

        reload();
    }

    @FXML
    private void onOpenFolder() {
        HistoryRecord selected = historyTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            openFolder(selected);
        }
    }

    @FXML
    private void onClear() {
        if (!Dialogs.confirm(stage(), Messages.get("history.clear.title"), Messages.get("history.clear.body"))) {
            return;
        }
        try {
            context.historyRepository().clear();
            reload();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Cannot clear history", e);
            Dialogs.error(stage(), Messages.get("history.clear.failed"), e.getMessage());
        }
    }

    @Override
    public void attach(Workspace workspace) {
        // the history repository comes from the context; nothing else is shared
    }

    /** Exports made since the page was last shown appear at once. */
    @Override
    public void shown() {
        reload();
    }

    private void reload() {
        try {
            List<HistoryRecord> records = context.historyRepository().findAll();
            historyTable.getItems().setAll(records);
            int totalUsers = records.stream().mapToInt(HistoryRecord::userCount).sum();
            summaryLabel.setText(Messages.get("history.summary", records.size(), totalUsers));
            clearButton.setDisable(records.isEmpty());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Cannot read history", e);
            summaryLabel.setText(Messages.get("history.readFailed", e.getMessage()));
        }
    }

    private void openFolder(HistoryRecord record) {
        if (!Files.isDirectory(record.folder())) {
            Dialogs.info(stage(), Messages.get("history.folderMissing.title"),
                    Messages.get("history.folderMissing.body", record.folder()));
            return;
        }
        context.hostServices().showDocument(record.folder().toUri().toString());
    }

    private Window stage() {
        return historyTable.getScene().getWindow();
    }
}
