package org.netpilot.controller;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.netpilot.AppContext;
import org.netpilot.i18n.Messages;
import org.netpilot.model.DnsAnswer;
import org.netpilot.model.DnsSettings;
import org.netpilot.model.RouterDuration;
import org.netpilot.service.router.RouterClient;
import org.netpilot.service.router.RouterException;

/**
 * "DNS lookup" page (developer tools): the connected router looks a name up with its own DNS
 * settings, and the page shows what it found and those settings (read only).
 */
public class DnsController implements Page {

    private static final System.Logger LOG = System.getLogger(DnsController.class.getName());

    private RouterClient client;
    private boolean settingsLoaded;

    @FXML
    private TextField nameField;
    @FXML
    private Button lookupButton;
    @FXML
    private Button reloadButton;
    @FXML
    private ProgressIndicator progress;
    @FXML
    private Label resultLabel;
    @FXML
    private Label problemLabel;
    @FXML
    private Label serversValue;
    @FXML
    private Label dynamicValue;
    @FXML
    private Label remoteValue;
    @FXML
    private Label settingsHint;
    @FXML
    private TableView<DnsAnswer.Record> recordsTable;
    @FXML
    private TableColumn<DnsAnswer.Record, String> nameColumn;
    @FXML
    private TableColumn<DnsAnswer.Record, String> typeColumn;
    @FXML
    private TableColumn<DnsAnswer.Record, String> dataColumn;
    @FXML
    private TableColumn<DnsAnswer.Record, String> ttlColumn;

    public DnsController(AppContext context) {
        // the router comes from the shared connection; nothing else is needed
    }

    @FXML
    private void initialize() {
        nameColumn.setCellValueFactory(c -> text(c.getValue().name()));
        typeColumn.setCellValueFactory(c -> text(c.getValue().type()));
        dataColumn.setCellValueFactory(c -> text(c.getValue().data()));
        dataColumn.getStyleClass().add("monospace");
        ttlColumn.setCellValueFactory(c -> text(c.getValue().ttl().map(RouterDuration::clock).orElse("—")));
        lookupButton.setGraphic(Icons.of(Icons.GLOBE));
        updateButtons(false);
    }

    @Override
    public void attach(Workspace shared) {
        shared.router().client().ifPresent(c -> client = c);
        updateButtons(false);
    }

    @Override
    public void shown() {
        if (client != null && !settingsLoaded) {
            onReloadSettings();
        }
    }

    @Override
    public void routerConnected(RouterClient routerClient, String name) {
        client = routerClient;
        settingsLoaded = false;
        recordsTable.getItems().clear();
        resultLabel.setText("");
        updateButtons(false);
    }

    @Override
    public void routerDisconnected() {
        client = null;
        settingsLoaded = false;
        for (Label value : new Label[]{serversValue, dynamicValue, remoteValue}) {
            value.setText("—");
        }
        updateButtons(false);
    }

    @FXML
    private void onLookup() {
        RouterClient target = client;
        if (target == null) {
            return;
        }
        String name;
        try {
            name = DnsAnswer.validName(nameField.getText());
        } catch (IllegalArgumentException invalid) {
            showProblem(invalid.getMessage());
            return;
        }
        showProblem(null);
        recordsTable.getItems().clear();
        resultLabel.setText("");
        // no time is shown: the lookup waits for a ping reply too, so it would not be the DNS time
        run(() -> target.resolve(name), answer -> {
            recordsTable.getItems().setAll(answer.records());
            resultLabel.setText(answer.addresses().isEmpty()
                    ? Messages.get("dns.notFound", answer.name())
                    : Messages.get("dns.found", answer.addresses().size()));
        }, error -> showProblem(Messages.get("dns.failed", name, error)));
    }

    @FXML
    private void onReloadSettings() {
        RouterClient target = client;
        if (target == null) {
            return;
        }
        run(target::dnsSettings, this::showSettings,
                error -> showProblem(Messages.get("dns.settingsFailed", error)));
    }

    private void showSettings(DnsSettings settings) {
        settingsLoaded = true;
        serversValue.setText(settings.servers().isEmpty() ? "—" : String.join("\n", settings.servers()));
        dynamicValue.setText(settings.dynamicServers().isEmpty() ? "—" : String.join("\n", settings.dynamicServers()));
        remoteValue.setText(Messages.get(settings.remoteRequests() ? "dns.remote.on" : "dns.remote.off"));
        String hint = settings.noServer()
                ? Messages.get("dns.hint.noServer")
                : Messages.get(settings.remoteRequests() ? "dns.hint.remoteOn" : "dns.hint.remoteOff");
        settingsHint.setText(hint);
        settingsHint.setVisible(!hint.isEmpty());
        settingsHint.setManaged(!hint.isEmpty());
        // no DNS server is a real problem (amber); remote requests off is usually fine, so only a note
        settingsHint.getStyleClass().removeAll("warning", "muted");
        settingsHint.getStyleClass().add(settings.noServer() ? "warning" : "muted");
    }

    private <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<String> onError) {
        Task<T> task = new Task<>() {

            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnRunning(e -> updateButtons(true));
        task.setOnSucceeded(e -> {
            updateButtons(false);
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            updateButtons(false);
            Throwable error = task.getException();
            LOG.log(error instanceof RouterException ? System.Logger.Level.WARNING : System.Logger.Level.ERROR,
                    "DNS request failed", error);
            onError.accept(String.valueOf(error.getMessage()));
        });
        Thread thread = new Thread(task, "netpilot-dns");
        thread.setDaemon(true);
        thread.start();
    }

    private void updateButtons(boolean busy) {
        lookupButton.setDisable(busy || client == null);
        reloadButton.setDisable(busy || client == null);
        progress.setVisible(busy);
        progress.setManaged(busy);
        if (client == null && !busy) {
            showProblem(Messages.get("dns.noRouter"));
        } else if (Messages.get("dns.noRouter").equals(problemLabel.getText())) {
            showProblem(null);
        }
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
