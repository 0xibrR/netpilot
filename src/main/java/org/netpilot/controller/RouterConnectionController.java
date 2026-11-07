package org.netpilot.controller;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.stage.Stage;
import org.netpilot.AppContext;
import org.netpilot.i18n.Messages;
import org.netpilot.model.AddressInput;
import org.netpilot.model.Neighbor;
import org.netpilot.model.RouterDuration;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.model.RouterSettings;
import org.netpilot.service.router.CertificateFingerprints;
import org.netpilot.service.router.RouterConnector;
import org.netpilot.service.router.RouterException;

/**
 * "Connect to Router" dialog. Tests or opens a session, and asks the user to confirm the
 * router's HTTPS certificate the first time (trust on first use).
 *
 * <p>Use: load the view, call {@link #prepare(RouterSettings)}, show the stage and wait, then read
 * {@link #connection()} and {@link #settings()}.
 */
public class RouterConnectionController {

    private static final System.Logger LOG = System.getLogger(RouterConnectionController.class.getName());
    private static final java.time.Duration SCAN_TIME = java.time.Duration.ofSeconds(3);

    private final AppContext context;

    private RouterSettings settings = RouterSettings.defaults();
    private RouterConnector.Connection connection;

    @FXML
    private TableView<Neighbor> neighborsTable;
    @FXML
    private TableColumn<Neighbor, String> neighborAddressColumn;
    @FXML
    private TableColumn<Neighbor, String> neighborMacColumn;
    @FXML
    private TableColumn<Neighbor, String> neighborIdentityColumn;
    @FXML
    private TableColumn<Neighbor, String> neighborVersionColumn;
    @FXML
    private TableColumn<Neighbor, String> neighborBoardColumn;
    @FXML
    private TableColumn<Neighbor, String> neighborUptimeColumn;
    @FXML
    private Label neighborsPlaceholder;
    @FXML
    private Button scanButton;
    @FXML
    private ProgressIndicator scanProgress;
    @FXML
    private TextField hostField;
    @FXML
    private TextField portField;
    @FXML
    private CheckBox httpsCheck;
    @FXML
    private TextField usernameField;
    @FXML
    private PasswordField passwordField;
    @FXML
    private Label httpWarning;
    @FXML
    private Label certificateLabel;
    @FXML
    private Button forgetCertificateButton;
    @FXML
    private ProgressIndicator progressIndicator;
    @FXML
    private Label resultLabel;
    @FXML
    private Button testButton;
    @FXML
    private Button connectButton;

    public RouterConnectionController(AppContext context) {
        this.context = context;
    }

    @FXML
    private void initialize() {
        portField.setTextFormatter(new TextFormatter<>(change -> change.getControlNewText().matches("\\d{0,5}")
                ? change
                : null));
        httpsCheck.selectedProperty().addListener((obs, was, https) -> {
            switchDefaultPort(https);
            showHttpWarning(!https);
            updateCertificateLabel();
        });
        hostField.textProperty().addListener((obs, old, value) -> updateCertificateLabel());
        hostField.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                normalizeAddressField();
            }
        });
        portField.textProperty().addListener((obs, old, value) -> updateCertificateLabel());
        setUpNeighbors();
    }

    // ------------------------------------------------------------------ neighbors (like WinBox)

    private void setUpNeighbors() {
        neighborAddressColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().reachable() ? c.getValue().address() : Messages.get("connect.neighbor.noIp")));
        neighborMacColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().macAddress()));
        neighborMacColumn.getStyleClass().add("monospace");
        neighborIdentityColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().identity()));
        neighborVersionColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().version().split(" ")[0]));
        neighborBoardColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().board()));
        neighborUptimeColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                RouterDuration.format(c.getValue().uptime())));
        neighborsTable.getSelectionModel().selectedItemProperty().addListener((obs, old, neighbor) -> {
            if (neighbor != null && neighbor.reachable()) {
                hostField.setText(neighbor.address());
            }
        });
        neighborsTable.setRowFactory(table -> {
            TableRow<Neighbor> row = new TableRow<>() {

                @Override
                protected void updateItem(Neighbor item, boolean empty) {
                    super.updateItem(item, empty);
                    getStyleClass().remove("unreachable");
                    if (!empty && item != null && !item.reachable()) {
                        getStyleClass().add("unreachable");
                    }
                }
            };
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty() && row.getItem().reachable()) {
                    hostField.setText(row.getItem().address());
                    (usernameField.getText().isBlank() ? usernameField : passwordField).requestFocus();
                }
            });
            return row;
        });
    }

    /** Looks for routers for 3 seconds; the dialog stays usable meanwhile. */
    @FXML
    private void onScan() {
        Task<List<Neighbor>> task = new Task<>() {

            @Override
            protected List<Neighbor> call() throws IOException {
                return context.neighborDiscovery().scan(SCAN_TIME);
            }
        };
        task.setOnRunning(e -> setScanning(true));
        task.setOnSucceeded(e -> {
            setScanning(false);
            neighborsTable.getItems().setAll(task.getValue());
            neighborsPlaceholder.setText(Messages.get("connect.neighbors.none"));
        });
        task.setOnFailed(e -> {
            setScanning(false);
            LOG.log(System.Logger.Level.WARNING, "Neighbor discovery failed", task.getException());
            neighborsPlaceholder.setText(Messages.get("connect.neighbors.failed"));
        });
        Thread thread = new Thread(task, "netpilot-neighbors");
        thread.setDaemon(true);
        thread.start();
    }

    private void setScanning(boolean scanning) {
        scanProgress.setVisible(scanning);
        scanProgress.setManaged(scanning);
        scanButton.setDisable(scanning);
        if (scanning) {
            neighborsPlaceholder.setText(Messages.get("connect.neighbors.searching"));
        }
    }

    /** Fills the form from the saved settings. */
    public void prepare(RouterSettings routerSettings) {
        this.settings = routerSettings;
        RouterEndpoint last = routerSettings.lastEndpoint();
        hostField.setText(last.host());
        httpsCheck.setSelected(last.https());
        portField.setText(String.valueOf(last.port()));
        usernameField.setText(last.username());
        showHttpWarning(!last.https());
        updateCertificateLabel();
        onScan();
    }

    /** The open session if the user pressed Connect successfully. */
    public Optional<RouterConnector.Connection> connection() {
        return Optional.ofNullable(connection);
    }

    /** Settings including the last endpoint and any certificate trusted in this dialog. */
    public RouterSettings settings() {
        return settings;
    }

    // ------------------------------------------------------------------ actions

    @FXML
    private void onTest() {
        attempt(false);
    }

    @FXML
    private void onConnect() {
        attempt(true);
    }

    @FXML
    private void onCancel() {
        passwordField.clear();
        stage().close();
    }

    @FXML
    private void onForgetCertificate() {
        readEndpoint().ifPresent(endpoint -> {
            settings = settings.withoutTrustedFingerprint(endpoint);
            updateCertificateLabel();
            showResult(Messages.get("connect.forgotten"), false);
        });
    }

    // ------------------------------------------------------------------ connection flow

    private void attempt(boolean keepSession) {
        Optional<RouterEndpoint> parsed = readEndpoint();
        if (parsed.isEmpty()) {
            return;
        }
        RouterEndpoint endpoint = parsed.get();
        List<String> errors = endpoint.validate();
        if (!errors.isEmpty()) {
            showResult(String.join("\n", errors), true);
            return;
        }
        if (passwordField.getText().isEmpty()) {
            showResult(Messages.get("connect.enterPassword"), true);
            return;
        }

        char[] password = passwordField.getText().toCharArray(); // wiped by RouterConnector
        String trusted = settings.trustedFingerprint(endpoint).orElse(null);

        Task<RouterConnector.Connection> task = new Task<>() {

            @Override
            protected RouterConnector.Connection call() throws RouterException {
                return context.routerConnector().connect(endpoint, password, trusted);
            }
        };
        task.setOnRunning(e -> setBusy(true));
        task.setOnSucceeded(e -> {
            setBusy(false);
            onConnected(endpoint, task.getValue(), keepSession);
        });
        task.setOnFailed(e -> {
            setBusy(false);
            onFailed(endpoint, task.getException(), keepSession);
        });
        Thread thread = new Thread(task, "netpilot-router-connect");
        thread.setDaemon(true);
        thread.start();
    }

    private void onConnected(RouterEndpoint endpoint, RouterConnector.Connection result, boolean keepSession) {
        settings = settings.withLastEndpoint(endpoint);
        LOG.log(System.Logger.Level.INFO, "Connected to {0} ({1})", endpoint.address(), result.info().summary());
        if (keepSession) {
            connection = result;
            passwordField.clear();
            stage().close();
        } else {
            result.client().close();
            showResult(Messages.get("connect.connected", result.info().summary()), false);
        }
    }

    private void onFailed(RouterEndpoint endpoint, Throwable error, boolean keepSession) {
        if (!(error instanceof RouterException routerError)) {
            LOG.log(System.Logger.Level.ERROR, "Unexpected error while connecting", error);
            showResult(String.valueOf(error.getMessage()), true);
            return;
        }
        LOG.log(System.Logger.Level.WARNING, "Connection to {0} failed: {1} ({2})",
                endpoint.address(), routerError.kind(), routerError.getMessage());
        switch (routerError.kind()) {
            case CERTIFICATE_UNTRUSTED, CERTIFICATE_CHANGED -> askToTrust(endpoint, routerError, keepSession);
            default -> showResult(routerError.getMessage(), true);
        }
    }

    /** Trust on first use; a changed certificate needs an explicit, discouraged extra step. */
    private void askToTrust(RouterEndpoint endpoint, RouterException error, boolean keepSession) {
        String fingerprint = error.presentedFingerprint().orElse("");
        boolean changed = error.kind() == RouterException.Kind.CERTIFICATE_CHANGED;

        ButtonType trust = new ButtonType(Messages.get(changed ? "connect.trustNew" : "connect.trust"),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(Messages.get("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = Dialogs.alert(changed ? Alert.AlertType.WARNING : Alert.AlertType.CONFIRMATION, stage(),
                Messages.get(changed ? "connect.changed.title" : "connect.trust.title"),
                error.getMessage() + "\n\n" + Messages.get("connect.fingerprint") + "\n"
                        + CertificateFingerprints.readable(fingerprint) + "\n\n" + Messages.get("connect.compare"));
        alert.getButtonTypes().setAll(cancel, trust);
        ((Button) alert.getDialogPane().lookupButton(cancel)).setDefaultButton(changed);
        ((Button) alert.getDialogPane().lookupButton(trust)).setDefaultButton(!changed);

        if (alert.showAndWait().orElse(cancel) == trust) {
            settings = settings.withTrustedFingerprint(endpoint, fingerprint);
            updateCertificateLabel();
            attempt(keepSession);
        } else {
            showResult(Messages.get("connect.notTrusted"), true);
        }
    }

    // ------------------------------------------------------------------ form helpers

    /** Turns a pasted URL such as {@code http://10.0.0.1/} into host, HTTPS and port fields. */
    private void normalizeAddressField() {
        String text = hostField.getText();
        AddressInput input = AddressInput.parse(text);
        if (!input.hadExtras(text)) {
            return;
        }
        input.https().ifPresent(httpsCheck::setSelected); // may switch the default port
        input.port().ifPresent(port -> portField.setText(String.valueOf(port)));
        hostField.setText(input.host());
    }

    private Optional<RouterEndpoint> readEndpoint() {
        normalizeAddressField();
        int port;
        try {
            port = Integer.parseInt(portField.getText());
        } catch (NumberFormatException e) {
            showResult(Messages.get("connect.enterPort"), true);
            return Optional.empty();
        }
        return Optional.of(new RouterEndpoint(hostField.getText(), port, httpsCheck.isSelected(),
                usernameField.getText()));
    }

    private void switchDefaultPort(boolean https) {
        String from = String.valueOf(https ? RouterEndpoint.DEFAULT_HTTP_PORT : RouterEndpoint.DEFAULT_HTTPS_PORT);
        if (portField.getText().isEmpty() || portField.getText().equals(from)) {
            portField.setText(
                    String.valueOf(https ? RouterEndpoint.DEFAULT_HTTPS_PORT : RouterEndpoint.DEFAULT_HTTP_PORT));
        }
    }

    private void updateCertificateLabel() {
        if (!httpsCheck.isSelected()) {
            certificateLabel.setText(Messages.get("connect.certificate.http"));
            forgetCertificateButton.setDisable(true);
            return;
        }
        Optional<String> trusted = Optional.empty();
        try {
            trusted = settings.trustedFingerprint(new RouterEndpoint(hostField.getText(),
                    Integer.parseInt(portField.getText()), true, usernameField.getText()));
        } catch (NumberFormatException e) {
            // incomplete port while typing
        }
        certificateLabel.setText(trusted
                .map(fp -> Messages.get("connect.certificate.trusted",
                        CertificateFingerprints.readable(fp).replace('\n', ' ')))
                .orElse(Messages.get("connect.certificate.none")));
        forgetCertificateButton.setDisable(trusted.isEmpty());
    }

    private void showHttpWarning(boolean show) {
        httpWarning.setVisible(show);
        httpWarning.setManaged(show);
    }

    private void setBusy(boolean busy) {
        progressIndicator.setVisible(busy);
        progressIndicator.setManaged(busy);
        testButton.setDisable(busy);
        connectButton.setDisable(busy);
        if (busy) {
            showResult(Messages.get("connect.connecting"), false);
        }
    }

    private void showResult(String message, boolean error) {
        resultLabel.setText(message);
        resultLabel.getStyleClass().remove("status-error");
        if (error) {
            resultLabel.getStyleClass().add("status-error");
        }
        stage().sizeToScene();
    }

    private Stage stage() {
        return (Stage) hostField.getScene().getWindow();
    }
}
