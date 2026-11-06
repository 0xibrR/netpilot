package org.netpilot.controller;

import java.util.List;
import java.util.Optional;
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
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.stage.Stage;
import org.netpilot.AppContext;
import org.netpilot.model.AddressInput;
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

    private final AppContext context;

    private RouterSettings settings = RouterSettings.defaults();
    private RouterConnector.Connection connection;

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
            showResult("The saved certificate was removed. You will be asked to confirm it again.", false);
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
            showResult("Enter the password.", true);
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
            showResult("✓ Connected: " + result.info().summary(), false);
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

        ButtonType trust = new ButtonType(changed ? "Trust new certificate" : "Trust", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = Dialogs.alert(changed ? Alert.AlertType.WARNING : Alert.AlertType.CONFIRMATION, stage(),
                changed ? "Router certificate changed!" : "Trust this router?",
                error.getMessage() + "\n\nSHA-256 fingerprint:\n" + CertificateFingerprints.readable(fingerprint)
                        + "\n\nCompare it with the router: WinBox > System > Certificates > open the certificate"
                        + " used by www-ssl > Fingerprint.");
        alert.getButtonTypes().setAll(cancel, trust);
        ((Button) alert.getDialogPane().lookupButton(cancel)).setDefaultButton(changed);
        ((Button) alert.getDialogPane().lookupButton(trust)).setDefaultButton(!changed);

        if (alert.showAndWait().orElse(cancel) == trust) {
            settings = settings.withTrustedFingerprint(endpoint, fingerprint);
            updateCertificateLabel();
            attempt(keepSession);
        } else {
            showResult("Not connected: the certificate was not trusted.", true);
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
            showResult("Enter a port number.", true);
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
            certificateLabel.setText("Not used with HTTP.");
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
                .map(fp -> "Trusted: " + CertificateFingerprints.readable(fp).replace('\n', ' '))
                .orElse("Not trusted yet. You will be asked to confirm it on the first connection."));
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
            showResult("Connecting…", false);
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
