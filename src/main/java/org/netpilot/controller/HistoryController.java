package org.netpilot.controller;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.netpilot.AppContext;
import org.netpilot.model.HistoryRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Export history window.
 */
public class HistoryController {

    private static final System.Logger LOG = System.getLogger(HistoryController.class.getName());
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm");

    private final AppContext context;

    @FXML private TableView<HistoryRecord> historyTable;
    @FXML private TableColumn<HistoryRecord, String> dateColumn;
    @FXML private TableColumn<HistoryRecord, Integer> countColumn;
    @FXML private TableColumn<HistoryRecord, String> profileColumn;
    @FXML private TableColumn<HistoryRecord, String> folderColumn;
    @FXML private Label summaryLabel;
    @FXML private Button clearButton;
    @FXML private Button openButton;

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
        if (!Dialogs.confirm(stage(), "Clear export history?",
                "History entries are removed. Exported files stay on disk.")) {
            return;
        }
        try {
            context.historyRepository().clear();
            reload();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Cannot clear history", e);
            Dialogs.error(stage(), "Cannot clear history", e.getMessage());
        }
    }

    @FXML
    private void onClose() {
        stage().close();
    }

    private void reload() {
        try {
            List<HistoryRecord> records = context.historyRepository().findAll();
            historyTable.getItems().setAll(records);
            int totalUsers = records.stream().mapToInt(HistoryRecord::userCount).sum();
            summaryLabel.setText(records.size() + " exports · " + totalUsers + " users in total");
            clearButton.setDisable(records.isEmpty());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Cannot read history", e);
            summaryLabel.setText("Cannot read history: " + e.getMessage());
        }
    }

    private void openFolder(HistoryRecord record) {
        if (!Files.isDirectory(record.folder())) {
            Dialogs.info(stage(), "Folder not found", "This export folder was moved or deleted:\n" + record.folder());
            return;
        }
        context.hostServices().showDocument(record.folder().toUri().toString());
    }

    private Stage stage() {
        return (Stage) historyTable.getScene().getWindow();
    }
}
