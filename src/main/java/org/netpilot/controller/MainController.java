package org.netpilot.controller;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.netpilot.AppContext;
import org.netpilot.NetPilotApp;
import org.netpilot.model.AppSettings;
import org.netpilot.model.GenerationSettings;
import org.netpilot.model.HotspotProfile;
import org.netpilot.model.HotspotServer;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.PasswordMode;
import org.netpilot.model.UsernameMode;
import org.netpilot.service.ExportService.ExportResult;
import org.netpilot.service.QrCodeService;
import org.netpilot.service.router.RouterClient;

/**
 * Main window: collects settings, previews generated users and exports exactly those users.
 */
public class MainController {

    private static final System.Logger LOG = System.getLogger(MainController.class.getName());

    private static final List<String> PROFILE_SUGGESTIONS = List.of("default", "1M", "2M", "3M", "4M", "5M", "10M",
            "20M", "30M", "40M");
    private static final List<String> UPTIME_SUGGESTIONS = List.of("30m", "1h", "2h", "4h", "8h", "1d", "7d", "30d");

    private final AppContext context;
    private final ObservableList<HotspotUser> users = FXCollections.observableArrayList();

    /** Last loaded/saved settings; the form is the source of truth for generation values. */
    private AppSettings appSettings;
    private boolean loadingForm;

    @FXML
    private Spinner<Integer> countSpinner;
    @FXML
    private ComboBox<UsernameMode> usernameModeBox;
    @FXML
    private Spinner<Integer> usernameLengthSpinner;
    @FXML
    private Spinner<Integer> usernameStartSpinner;

    @FXML
    private ComboBox<PasswordMode> passwordModeBox;
    @FXML
    private Spinner<Integer> passwordLengthSpinner;
    @FXML
    private Spinner<Integer> passwordStartSpinner;
    @FXML
    private TextField prefixField;
    @FXML
    private Label weakPasswordWarning;

    @FXML
    private ComboBox<String> profileBox;
    @FXML
    private ComboBox<String> serverBox;
    @FXML
    private ComboBox<String> uptimeBox;

    @FXML
    private TextField loginUrlField;
    @FXML
    private Spinner<Integer> perRowSpinner;
    @FXML
    private TextField outputDirField;

    @FXML
    private TableView<HotspotUser> usersTable;
    @FXML
    private TableColumn<HotspotUser, Integer> indexColumn;
    @FXML
    private TableColumn<HotspotUser, String> usernameColumn;
    @FXML
    private TableColumn<HotspotUser, String> passwordColumn;
    @FXML
    private TableColumn<HotspotUser, String> profileColumn;
    @FXML
    private TableColumn<HotspotUser, String> serverColumn;
    @FXML
    private TableColumn<HotspotUser, String> uptimeColumn;
    @FXML
    private Label usersCountLabel;

    @FXML
    private ProgressIndicator progressIndicator;
    @FXML
    private Label statusLabel;
    @FXML
    private Button generateButton;
    @FXML
    private Button exportButton;

    @FXML
    private Label routerStatusLabel;
    @FXML
    private MenuItem refreshRouterItem;
    @FXML
    private MenuItem disconnectRouterItem;
    @FXML
    private MenuItem manageUsersItem;

    /** The open Router Users window, if any (one at a time). */
    private Stage routerUsersStage;

    @FXML
    private Button sendButton;

    private RouterSession router;

    /** True while an export or upload runs. */
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    /** True when the preview shows users that were already sent, so they cannot be sent twice. */
    private final BooleanProperty sent = new SimpleBooleanProperty(false);
    private boolean showingSentUsers;

    public MainController(AppContext context) {
        this.context = context;
    }

    @FXML
    private void initialize() {
        setUpSpinner(countSpinner, 1, GenerationSettings.MAX_COUNT);
        setUpSpinner(usernameLengthSpinner, GenerationSettings.MIN_LENGTH, GenerationSettings.MAX_LENGTH);
        setUpSpinner(usernameStartSpinner, 0, Integer.MAX_VALUE);
        setUpSpinner(passwordLengthSpinner, GenerationSettings.MIN_LENGTH, GenerationSettings.MAX_LENGTH);
        setUpSpinner(passwordStartSpinner, 0, Integer.MAX_VALUE);
        setUpSpinner(perRowSpinner, AppSettings.MIN_PER_ROW, AppSettings.MAX_PER_ROW);

        usernameModeBox.getItems().setAll(UsernameMode.values());
        passwordModeBox.getItems().setAll(PasswordMode.values());
        profileBox.getItems().setAll(PROFILE_SUGGESTIONS);
        serverBox.getItems().setAll("all");
        uptimeBox.getItems().setAll(UPTIME_SUGGESTIONS);

        setUpTable();
        bindFieldStates();

        appSettings = context.settingsRepository().load();
        showSettings(appSettings);
        clearPreviewWhenSettingsChange();
        updateCountLabel();

        router = new RouterSession(context, routerStatusLabel, this::window, this::showRouterLists,
                this::showOfflineSuggestions);
        refreshRouterItem.disableProperty().bind(router.connectedProperty().not());
        disconnectRouterItem.disableProperty().bind(router.connectedProperty().not());
        manageUsersItem.disableProperty().bind(router.connectedProperty().not());

        BooleanBinding noUsers = Bindings.isEmpty(users);
        generateButton.disableProperty().bind(busy);
        exportButton.disableProperty().bind(noUsers.or(busy));
        sendButton.disableProperty().bind(noUsers.or(busy).or(sent).or(router.connectedProperty().not()));
        users.addListener((ListChangeListener<HotspotUser>) change -> {
            if (!showingSentUsers) {
                sent.set(false); // a new preview has not been sent yet
            }
        });
    }

    /** Ends the router session; called when the app closes. */
    public void close() {
        router.close();
    }

    // ------------------------------------------------------------------ router

    @FXML
    private void onConnectRouter() {
        router.showConnectDialog(appSettings.router()).ifPresent(routerSettings -> {
            appSettings = appSettings.withRouter(routerSettings);
            saveSettings(); // keeps the endpoint and trusted certificates, never the password
        });
    }

    @FXML
    private void onRefreshRouter() {
        router.refresh();
    }

    @FXML
    private void onDisconnectRouter() {
        router.disconnect();
    }

    /** Opens the Router Users window, or brings it to the front if it is already open. */
    @FXML
    private void onManageUsers() {
        Optional<RouterClient> client = router.client();
        if (client.isEmpty()) {
            return;
        }
        if (routerUsersStage != null && routerUsersStage.isShowing()) {
            routerUsersStage.toFront();
            return;
        }
        try {
            FXMLLoader loader = NetPilotApp.loader("view/router-users-view.fxml", context);
            Parent root = loader.load();
            RouterUsersController controller = loader.getController();
            Scene scene = new Scene(root);
            scene.getStylesheets().add(NetPilotApp.resource("css/styles.css").toExternalForm());
            routerUsersStage = new Stage();
            routerUsersStage.initOwner(window()); // not modal: it can stay open next to the main window
            routerUsersStage.setScene(scene);
            routerUsersStage.show();
            controller.start(client.get(), router.displayName(), this::exportUsers);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Cannot open Router Users", e);
            Dialogs.error(window(), "Cannot open Router Users", e.getMessage());
        }
    }

    @FXML
    private void onRouterStatusClicked() {
        onConnectRouter();
    }

    /** Offers the router's real profiles and servers; typing other values stays possible. */
    private void showRouterLists(RouterSession.RouterLists lists) {
        String profile = comboText(profileBox);
        String server = comboText(serverBox);

        List<String> profiles = lists.profiles().stream().map(HotspotProfile::name).toList();
        List<String> servers = new ArrayList<>();
        servers.add("all");
        lists.servers().stream().filter(s -> !s.disabled()).map(HotspotServer::name).forEach(servers::add);

        profileBox.getItems().setAll(profiles);
        serverBox.getItems().setAll(servers);
        profileBox.setValue(profile);
        serverBox.setValue(server);

        if (!profiles.contains(profile)) {
            setStatus("Profile '" + profile + "' does not exist on the router. Choose one from the list.", true);
        } else if (!servers.contains(server)) {
            setStatus("Server '" + server + "' does not exist on the router. Choose one from the list.", true);
        } else {
            setStatus("Loaded " + plural(profiles.size(), "profile") + " and "
                    + plural(servers.size() - 1, "server") + " from the router.", false);
        }
    }

    private void showOfflineSuggestions() {
        if (routerUsersStage != null) {
            routerUsersStage.close(); // its session is gone
            routerUsersStage = null;
        }
        String profile = comboText(profileBox);
        String server = comboText(serverBox);
        profileBox.getItems().setAll(PROFILE_SUGGESTIONS);
        serverBox.getItems().setAll("all");
        profileBox.setValue(profile);
        serverBox.setValue(server);
        setStatus("Disconnected from the router.", false);
    }

    /** Creates the previewed users on the connected router, then offers Print and Undo. */
    @FXML
    private void onSendToRouter() {
        Optional<RouterClient> client = router.client();
        if (client.isEmpty() || users.isEmpty()) {
            return;
        }
        List<HotspotUser> batch = List.copyOf(users);
        HotspotUser first = batch.getFirst();
        Optional<String> problem = router.checkProfileAndServer(first.profile(), first.server());
        if (problem.isPresent()) {
            Dialogs.error(window(), "Cannot send these users", problem.get());
            return;
        }

        String httpWarning = client.get().endpoint().https()
                ? ""
                : "\n\n⚠ The connection uses HTTP: the voucher passwords travel unencrypted.";
        String uptime = first.limitUptime().isBlank() ? "unlimited" : first.limitUptime();
        if (!Dialogs.confirm(window(), "Create " + batch.size() + " users on " + router.displayName() + "?",
                "Profile: " + first.profile() + "    Server: " + first.server() + "    Uptime: " + uptime
                        + "\n\nNames that already exist on the router get a new random name (random modes)"
                        + " or are skipped (sequential modes)." + httpWarning)) {
            return;
        }

        try {
            FXMLLoader loader = NetPilotApp.loader("view/upload-view.fxml", context);
            Parent root = loader.load();
            UploadController upload = loader.getController();
            Scene scene = new Scene(root);
            scene.getStylesheets().add(NetPilotApp.resource("css/styles.css").toExternalForm());
            Stage stage = new Stage();
            stage.initOwner(window());
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setTitle("Send to Router - " + NetPilotApp.NAME);
            stage.setScene(scene);
            stage.show();
            upload.start(client.get(), router.displayName(), batch, readGenerationSettings());
            busy.set(true);
            stage.setOnHidden(e -> {
                busy.set(false);
                afterUpload(upload);
            });
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Cannot open upload window", e);
            Dialogs.error(window(), "Cannot open the upload window", e.getMessage());
        }
    }

    /** The preview now shows exactly the users that exist on the router (or nothing after Undo). */
    private void afterUpload(UploadController upload) {
        upload.report().ifPresent(report -> {
            showingSentUsers = true;
            try {
                users.setAll(upload.undone() ? List.of() : report.createdUsers());
            } finally {
                showingSentUsers = false;
            }
            sent.set(!upload.undone() && !users.isEmpty());
            setStatus(upload.undone()
                    ? "The batch was removed from the router."
                    : report.created().size() + " users are on the router (" + report.batchComment() + ").", false);
            if (upload.printRequested()) {
                exportUsers(report.createdUsers());
            }
        });
    }

    // ------------------------------------------------------------------ actions

    @FXML
    private void onGenerate() {
        GenerationSettings settings = readGenerationSettings();
        List<String> errors = settings.validate();
        if (!errors.isEmpty()) {
            Dialogs.error(window(), "Please fix these settings", String.join("\n", errors));
            return;
        }
        try {
            users.setAll(context.userGenerator().generate(settings));
            setStatus(users.size() + " users generated. Review them, then export.", false);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Generation failed", e);
            Dialogs.error(window(), "Could not generate users", e.getMessage());
        }
    }

    @FXML
    private void onExport() {
        exportUsers(List.copyOf(users));
    }

    /** Writes CSV, RSC and PDF vouchers for exactly these users, in the background. */
    private void exportUsers(List<HotspotUser> batch) {
        if (batch.isEmpty()) {
            return;
        }
        String loginUrl = loginUrlField.getText().strip();
        if (!loginUrl.isEmpty()) {
            try {
                QrCodeService.validateLoginUrl(loginUrl);
            } catch (IllegalArgumentException e) {
                Dialogs.error(window(), "Invalid QR login URL", e.getMessage());
                return;
            }
        }

        AppSettings settings = currentAppSettings();

        Task<ExportResult> task = new Task<>() {

            @Override
            protected ExportResult call() throws IOException {
                return context.exportService().export(batch, settings);
            }
        };
        task.setOnRunning(e -> setBusy(true, "Exporting " + batch.size() + " users…"));
        task.setOnSucceeded(e -> {
            setBusy(false, null);
            ExportResult result = task.getValue();
            setStatus("Exported " + result.userCount() + " users to " + result.folder().getFileName(), false);
            saveSettings();
            showExportDone(result);
        });
        task.setOnFailed(e -> {
            setBusy(false, null);
            Throwable error = task.getException();
            LOG.log(System.Logger.Level.ERROR, "Export failed", error);
            setStatus("Export failed", true);
            Dialogs.error(window(), "Export failed", String.valueOf(error.getMessage()));
        });

        Thread thread = new Thread(task, "netpilot-export");
        thread.setDaemon(true);
        thread.start();
    }

    /** Edits how vouchers look; the router's profiles (when connected) get a price field each. */
    @FXML
    private void onVoucherDesign() {
        try {
            FXMLLoader loader = NetPilotApp.loader("view/voucher-design-view.fxml", context);
            Parent root = loader.load();
            VoucherDesignController design = loader.getController();
            Scene scene = new Scene(root);
            scene.getStylesheets().add(NetPilotApp.resource("css/styles.css").toExternalForm());
            Stage stage = new Stage();
            stage.initOwner(window());
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setTitle("Voucher Design - " + NetPilotApp.NAME);
            stage.setScene(scene);
            design.prepare(currentAppSettings(), List.copyOf(profileBox.getItems()));
            stage.showAndWait();
            design.result().ifPresent(template -> {
                appSettings = appSettings.withVoucherTemplate(template);
                saveSettings();
                setStatus("Voucher design saved.", false);
            });
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Cannot open Voucher Design", e);
            Dialogs.error(window(), "Cannot open Voucher Design", e.getMessage());
        }
    }

    @FXML
    private void onShowHistory() {
        try {
            FXMLLoader loader = NetPilotApp.loader("view/history-view.fxml", context);
            Parent root = loader.load();
            Scene scene = new Scene(root);
            scene.getStylesheets().add(NetPilotApp.resource("css/styles.css").toExternalForm());

            Stage stage = new Stage();
            stage.initOwner(window());
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setTitle("Export History - " + NetPilotApp.NAME);
            stage.setScene(scene);
            stage.showAndWait();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Cannot open history", e);
            Dialogs.error(window(), "Cannot open history", e.getMessage());
        }
    }

    @FXML
    private void onOpenOutputFolder() {
        Path folder = Path.of(outputDirField.getText());
        if (!Files.isDirectory(folder)) {
            Dialogs.info(window(), "Nothing exported yet",
                    "The output folder will be created on the first export:\n" + folder);
            return;
        }
        open(folder);
    }

    @FXML
    private void onBrowseOutputFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose output folder");
        Path current = Path.of(outputDirField.getText());
        if (Files.isDirectory(current)) {
            chooser.setInitialDirectory(current.toFile());
        }
        File chosen = chooser.showDialog(window());
        if (chosen != null) {
            outputDirField.setText(chosen.getAbsolutePath());
            saveSettings();
        }
    }

    @FXML
    private void onResetSettings() {
        if (Dialogs.confirm(window(), "Reset all settings?", "All fields return to their default values.")) {
            appSettings = context.settingsRepository().defaults();
            showSettings(appSettings);
            saveSettings();
            setStatus("Settings reset", false);
        }
    }

    @FXML
    private void onOpenRepository() {
        context.hostServices().showDocument(NetPilotApp.REPOSITORY_URL);
    }

    @FXML
    private void onAbout() {
        Dialogs.info(window(), NetPilotApp.NAME + " " + NetPilotApp.VERSION,
                """
                        Network management toolkit.

                        • Generate MikroTik Hotspot users with unique names
                        • Printable A4 vouchers with QR auto-login
                        • RouterOS import scripts (.rsc) and CSV export
                        • Export history

                        Developed by Ibrahim Ramadan
                        """ + NetPilotApp.REPOSITORY_URL);
    }

    @FXML
    private void onExit() {
        ((Stage) window()).close();
    }

    // ------------------------------------------------------------------ settings <-> form

    public void saveSettings() {
        try {
            appSettings = currentAppSettings();
            context.settingsRepository().save(appSettings);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot save settings", e);
        }
    }

    private AppSettings currentAppSettings() {
        return new AppSettings(
                readGenerationSettings(),
                loginUrlField.getText(),
                perRowSpinner.getValue(),
                Path.of(outputDirField.getText()),
                appSettings.router(), // edited only through the router connection dialog
                appSettings.voucherTemplate()); // edited only through the Voucher Design window
    }

    private GenerationSettings readGenerationSettings() {
        commitSpinners();
        return new GenerationSettings(
                countSpinner.getValue(),
                usernameModeBox.getValue(),
                usernameLengthSpinner.getValue(),
                usernameStartSpinner.getValue(),
                passwordModeBox.getValue(),
                passwordLengthSpinner.getValue(),
                passwordStartSpinner.getValue(),
                prefixField.getText(),
                comboText(profileBox),
                comboText(serverBox),
                comboText(uptimeBox));
    }

    private void showSettings(AppSettings s) {
        loadingForm = true;
        try {
            GenerationSettings g = s.generation();
            countSpinner.getValueFactory().setValue(Math.clamp(g.count(), 1, GenerationSettings.MAX_COUNT));
            usernameModeBox.setValue(g.usernameMode());
            usernameLengthSpinner.getValueFactory().setValue(clampLength(g.usernameLength()));
            usernameStartSpinner.getValueFactory().setValue(Math.max(0, g.usernameStart()));
            passwordModeBox.setValue(g.passwordMode());
            passwordLengthSpinner.getValueFactory().setValue(clampLength(g.passwordLength()));
            passwordStartSpinner.getValueFactory().setValue(Math.max(0, g.passwordStart()));
            prefixField.setText(g.passwordPrefix());
            profileBox.setValue(g.profile());
            serverBox.setValue(g.server());
            uptimeBox.setValue(g.limitUptime());
            loginUrlField.setText(s.loginUrl());
            perRowSpinner.getValueFactory().setValue(s.vouchersPerRow());
            outputDirField.setText(s.outputDirectory().toString());
        } finally {
            loadingForm = false;
        }
        users.clear();
    }

    // ------------------------------------------------------------------ UI wiring

    private void setUpTable() {
        usersTable.setItems(users);
        indexColumn.setCellFactory(column -> new javafx.scene.control.TableCell<>() {

            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || getTableRow() == null || getTableRow().getItem() == null
                        ? null
                        : String.valueOf(getIndex() + 1));
            }
        });
        indexColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(0));
        usernameColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().username()));
        passwordColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().password()));
        profileColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().profile()));
        serverColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().server()));
        uptimeColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().limitUptime().isBlank() ? "unlimited" : c.getValue().limitUptime()));
        usernameColumn.getStyleClass().add("monospace");
        passwordColumn.getStyleClass().add("monospace");
    }

    /** Only enable inputs that the selected modes actually use. */
    private void bindFieldStates() {
        usernameModeBox.valueProperty().addListener((obs, old, mode) -> {
            usernameLengthSpinner.setDisable(mode == null || !mode.usesLength());
            usernameStartSpinner.setDisable(mode == null || !mode.usesSequence());
        });
        passwordModeBox.valueProperty().addListener((obs, old, mode) -> {
            passwordLengthSpinner.setDisable(mode == null || !mode.usesLength());
            passwordStartSpinner.setDisable(mode == null || !mode.usesSequence());
            prefixField.setDisable(mode == null || !mode.usesPrefix());
            boolean weak = mode != null && mode.isWeak();
            weakPasswordWarning.setVisible(weak);
            weakPasswordWarning.setManaged(weak);
        });
    }

    /** A preview must always match what gets exported, so any change invalidates it. */
    private void clearPreviewWhenSettingsChange() {
        List<ObservableValue<?>> inputs = List.of(
                countSpinner.valueProperty(), usernameModeBox.valueProperty(),
                usernameLengthSpinner.valueProperty(), usernameStartSpinner.valueProperty(),
                passwordModeBox.valueProperty(), passwordLengthSpinner.valueProperty(),
                passwordStartSpinner.valueProperty(), prefixField.textProperty(),
                profileBox.valueProperty(), profileBox.getEditor().textProperty(),
                serverBox.valueProperty(), serverBox.getEditor().textProperty(),
                uptimeBox.valueProperty(), uptimeBox.getEditor().textProperty());
        for (ObservableValue<?> input : inputs) {
            input.addListener((obs, old, value) -> {
                if (!loadingForm && !users.isEmpty()) {
                    users.clear();
                    setStatus("Settings changed - press Generate again.", false);
                }
            });
        }
        users.addListener((javafx.collections.ListChangeListener<HotspotUser>) change -> updateCountLabel());
    }

    private void updateCountLabel() {
        usersCountLabel.setText(users.isEmpty() ? "No users generated" : users.size() + " users");
    }

    private void showExportDone(ExportResult result) {
        ButtonType openPdf = new ButtonType("Open PDF", ButtonBar.ButtonData.YES);
        ButtonType openFolder = new ButtonType("Open Folder", ButtonBar.ButtonData.OTHER);
        Alert alert = Dialogs.alert(Alert.AlertType.INFORMATION, window(), "Export complete",
                result.userCount() + " users were saved to:\n" + result.folder().toAbsolutePath()
                        + "\n\nImport on the router with:\n/import file-name=" + result.rscFile().getFileName());
        alert.getButtonTypes().setAll(openPdf, openFolder, ButtonType.CLOSE);
        Optional<ButtonType> answer = alert.showAndWait();
        if (answer.isPresent() && answer.get() == openPdf) {
            open(result.pdfFile());
        } else if (answer.isPresent() && answer.get() == openFolder) {
            open(result.folder());
        }
    }

    private void open(Path path) {
        context.hostServices().showDocument(path.toAbsolutePath().toUri().toString());
    }

    private void setBusy(boolean value, String message) {
        busy.set(value);
        progressIndicator.setVisible(value);
        progressIndicator.setManaged(value);
        if (message != null) {
            setStatus(message, false);
        }
    }

    private void setStatus(String message, boolean error) {
        statusLabel.setText(message);
        statusLabel.getStyleClass().remove("status-error");
        if (error) {
            statusLabel.getStyleClass().add("status-error");
        }
    }

    private Window window() {
        return usersTable.getScene().getWindow();
    }

    // ------------------------------------------------------------------ helpers

    private static void setUpSpinner(Spinner<Integer> spinner, int min, int max) {
        SpinnerValueFactory.IntegerSpinnerValueFactory factory = new SpinnerValueFactory.IntegerSpinnerValueFactory(min,
                max, min);
        spinner.setValueFactory(factory);
        // digits only, and commit typed text when focus leaves (JavaFX does not by default)
        spinner.getEditor().setTextFormatter(
                new TextFormatter<>(change -> change.getControlNewText().matches("\\d{0,10}") ? change : null));
        spinner.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                commit(spinner);
            }
        });
    }

    private void commitSpinners() {
        for (Spinner<Integer> s : List.of(countSpinner, usernameLengthSpinner, usernameStartSpinner,
                passwordLengthSpinner, passwordStartSpinner, perRowSpinner)) {
            commit(s);
        }
    }

    private static void commit(Spinner<Integer> spinner) {
        SpinnerValueFactory.IntegerSpinnerValueFactory f = (SpinnerValueFactory.IntegerSpinnerValueFactory) spinner
                .getValueFactory();
        String text = spinner.getEditor().getText();
        int value;
        try {
            value = Math.clamp(Long.parseLong(text), f.getMin(), f.getMax());
        } catch (NumberFormatException e) {
            value = f.getValue();
        }
        f.setValue(value);
        spinner.getEditor().setText(String.valueOf(value));
    }

    /** Editable combo boxes only update their value on Enter; read the editor text instead. */
    private static String comboText(ComboBox<String> box) {
        String text = box.isEditable() ? box.getEditor().getText() : box.getValue();
        return text == null ? "" : text.strip();
    }

    private static int clampLength(int length) {
        return Math.clamp(length, GenerationSettings.MIN_LENGTH, GenerationSettings.MAX_LENGTH);
    }

    /** {@code 1 server}, {@code 3 servers}. */
    private static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
