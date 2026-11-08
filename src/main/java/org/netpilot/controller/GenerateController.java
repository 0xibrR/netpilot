package org.netpilot.controller;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
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
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.netpilot.AppContext;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.model.AppSettings;
import org.netpilot.model.CodeFormat;
import org.netpilot.model.GenerationSettings;
import org.netpilot.model.HotspotProfile;
import org.netpilot.model.HotspotServer;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.PasswordStyle;
import org.netpilot.model.TimeLimit;
import org.netpilot.service.ExportService.ExportResult;
import org.netpilot.service.QrCodeService;
import org.netpilot.service.router.RouterClient;

/**
 * "Create cards" page: collects settings, previews generated users, exports exactly those users
 * and sends them to the connected router.
 */
public class GenerateController implements Page {

    private static final System.Logger LOG = System.getLogger(GenerateController.class.getName());

    private final AppContext context;
    private final ObservableList<HotspotUser> users = FXCollections.observableArrayList();

    private Workspace workspace;
    private boolean loadingForm;

    @FXML
    private Spinner<Integer> countSpinner;
    @FXML
    private ComboBox<CodeFormat> formatBox;
    @FXML
    private TextField prefixField;
    @FXML
    private TextField suffixField;
    @FXML
    private ComboBox<Integer> codeLengthBox;
    @FXML
    private ComboBox<PasswordChoice> passwordBox;
    @FXML
    private VBox sharedPasswordBox;
    @FXML
    private TextField sharedPasswordField;

    /** One line of the password list: no password, a length, the card number, or one shared password. */
    record PasswordChoice(PasswordStyle style, int length) {

        @Override
        public String toString() {
            return switch (style) {
                case NONE -> Messages.get("password.choice.NONE");
                case RANDOM -> String.valueOf(length);
                case SAME_AS_CODE -> Messages.get("password.choice.SAME_AS_CODE");
                case SHARED -> Messages.get("password.choice.SHARED");
            };
        }
    }
    @FXML
    private Label exampleLabel;

    @FXML
    private ComboBox<String> profileBox;
    @FXML
    private ComboBox<String> serverBox;
    @FXML
    private TextField uptimeAmountField;
    @FXML
    private ComboBox<TimeLimit.Unit> uptimeUnitBox;

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
    private Button sendButton;
    @FXML
    private Button openFolderButton;

    private RouterSession router;

    /** True while an export or upload runs. */
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    /** True when the preview shows users that were already sent, so they cannot be sent twice. */
    private final BooleanProperty sent = new SimpleBooleanProperty(false);
    private boolean showingSentUsers;

    public GenerateController(AppContext context) {
        this.context = context;
    }

    @FXML
    private void initialize() {
        setUpSpinner(countSpinner, 1, GenerationSettings.MAX_COUNT);
        setUpLengthAndPasswordLists();
        for (TextField affix : List.of(prefixField, suffixField)) {
            affix.setTextFormatter(new TextFormatter<>(change -> change.getControlNewText()
                    .matches("[A-Za-z0-9]{0," + GenerationSettings.MAX_AFFIX + "}") ? change : null));
        }
        setUpSpinner(perRowSpinner, AppSettings.MIN_PER_ROW, AppSettings.MAX_PER_ROW);

        formatBox.getItems().setAll(CodeFormat.values());
        serverBox.getItems().setAll("all");
        uptimeUnitBox.getItems().setAll(TimeLimit.Unit.values());
        uptimeUnitBox.setValue(TimeLimit.Unit.HOURS);
        uptimeAmountField.setTextFormatter(new TextFormatter<>(
                change -> change.getControlNewText().matches("\\d{0,5}") ? change : null));

        setUpTable();
        bindFieldStates();

        users.addListener((javafx.collections.ListChangeListener<HotspotUser>) change -> updateCountLabel());
        updateCountLabel();
        generateButton.setGraphic(Icons.of(Icons.TICKET));
        exportButton.setGraphic(Icons.of(Icons.DOWNLOAD));
        sendButton.setGraphic(Icons.of(Icons.SEND));
        openFolderButton.setGraphic(Icons.of(Icons.FOLDER));
        generateButton.disableProperty().bind(busy);
        exportButton.disableProperty().bind(Bindings.isEmpty(users).or(busy));
        users.addListener((ListChangeListener<HotspotUser>) change -> {
            if (!showingSentUsers) {
                sent.set(false); // a new preview has not been sent yet
            }
        });
    }

    @Override
    public void attach(Workspace shared) {
        this.workspace = shared;
        this.router = shared.router();
        shared.setPrinter(this::exportUsers);
        showSettings(shared.settings());
        profileBox.getItems().setAll(offlineProfiles());
        clearPreviewWhenSettingsChange();
        BooleanBinding noUsers = Bindings.isEmpty(users);
        sendButton.disableProperty().bind(noUsers.or(busy).or(sent).or(router.connectedProperty().not()));
    }

    // ------------------------------------------------------------------ router

    /** Offers the router's real profiles and servers; typing other values stays possible. */
    @Override
    public void routerListsLoaded(RouterSession.RouterLists lists) {
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
            setStatus(Messages.get("main.status.profileMissing", profile), true);
        } else if (!servers.contains(server)) {
            setStatus(Messages.get("main.status.serverMissing", server), true);
        } else {
            setStatus(Messages.get("main.status.loadedLists", profiles.size(), servers.size() - 1), false);
        }
    }

    @Override
    public void routerDisconnected() {
        String profile = comboText(profileBox);
        String server = comboText(serverBox);
        profileBox.getItems().setAll(offlineProfiles());
        serverBox.getItems().setAll("all");
        profileBox.setValue(profile);
        serverBox.setValue(server);
        setStatus(Messages.get("main.status.disconnected"), false);
    }

    /**
     * Without a router, offer only profiles the user already uses (priced in Voucher design, or
     * typed last time); speeds and names are the user's choice, so nothing is invented here.
     */
    private List<String> offlineProfiles() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        names.add("default");
        names.addAll(workspace.settings().voucherTemplate().prices().keySet());
        String current = comboText(profileBox);
        if (!current.isEmpty()) {
            names.add(current);
        }
        return List.copyOf(names);
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
            Dialogs.error(window(), Messages.get("send.cannotSend"), problem.get());
            return;
        }

        String httpWarning = client.get().endpoint().https()
                ? ""
                : "\n\n" + Messages.get("send.httpWarning");
        String uptime = first.limitUptime().isBlank() ? Messages.get("common.unlimited") : first.limitUptime();
        if (!Dialogs.confirm(window(), Messages.get("send.confirm.title", batch.size(), router.displayName()),
                Messages.get("send.confirm.body", first.profile(), first.server(), uptime) + httpWarning)) {
            return;
        }

        try {
            FXMLLoader loader = NetPilotApp.loader("view/upload-view.fxml", context);
            Parent root = loader.load();
            UploadController upload = loader.getController();
            Scene scene = new Scene(root);
            Styling.apply(scene);
            Stage stage = new Stage();
            stage.initOwner(window());
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setTitle(Messages.get("window.send") + " - " + NetPilotApp.NAME);
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
            Dialogs.error(window(), Messages.get("error.openWindow"), e.getMessage());
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
                    ? Messages.get("send.status.undone")
                    : Messages.get("send.status.done", report.created().size(), report.batchComment()), false);
            if (upload.printRequested()) {
                exportUsers(report.createdUsers());
            }
            if (upload.receiptsRequested()) {
                workspace.printReceipts(report.createdUsers());
            }
        });
    }

    // ------------------------------------------------------------------ actions

    @FXML
    private void onGenerate() {
        GenerationSettings settings = readGenerationSettings();
        List<String> errors = settings.validate();
        if (!errors.isEmpty()) {
            Dialogs.error(window(), Messages.get("error.fixSettings"), String.join("\n", errors));
            return;
        }
        try {
            users.setAll(context.userGenerator().generate(settings));
            setStatus(Messages.get("main.status.generated", users.size()), false);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Generation failed", e);
            Dialogs.error(window(), Messages.get("error.generate"), e.getMessage());
        }
    }

    @FXML
    private void onExport() {
        exportUsers(List.copyOf(users));
    }

    /** Writes CSV, RSC and PDF vouchers for exactly these users, in the background. */
    void exportUsers(List<HotspotUser> batch) {
        if (batch.isEmpty()) {
            return;
        }
        String loginUrl = loginUrlField.getText().strip();
        if (!loginUrl.isEmpty()) {
            try {
                QrCodeService.validateLoginUrl(loginUrl);
            } catch (IllegalArgumentException e) {
                Dialogs.error(window(), Messages.get("error.invalidLoginUrl"), e.getMessage());
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
        task.setOnRunning(e -> setBusy(true, Messages.get("main.status.exporting", batch.size())));
        task.setOnSucceeded(e -> {
            setBusy(false, null);
            ExportResult result = task.getValue();
            setStatus(Messages.get("main.status.exported", result.userCount(), result.folder().getFileName()), false);
            saveSettings();
            showExportDone(result);
        });
        task.setOnFailed(e -> {
            setBusy(false, null);
            Throwable error = task.getException();
            LOG.log(System.Logger.Level.ERROR, "Export failed", error);
            setStatus(Messages.get("error.exportFailed"), true);
            Dialogs.error(window(), Messages.get("error.exportFailed"), String.valueOf(error.getMessage()));
        });

        Thread thread = new Thread(task, "netpilot-export");
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void onOpenOutputFolder() {
        Path folder = Path.of(outputDirField.getText());
        if (!Files.isDirectory(folder)) {
            Dialogs.info(window(), Messages.get("main.noExports.title"), Messages.get("main.noExports.body", folder));
            return;
        }
        open(folder);
    }

    @FXML
    private void onBrowseOutputFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(Messages.get("main.chooseFolder"));
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

    /** Resets this page's fields; router, voucher design and appearance are kept. */
    @FXML
    private void onResetSettings() {
        if (Dialogs.confirm(window(), Messages.get("main.reset.title"), Messages.get("main.reset.body"))) {
            AppSettings defaults = context.settingsRepository().defaults();
            showSettings(defaults);
            saveSettings();
            setStatus(Messages.get("main.status.reset"), false);
        }
    }

    // ------------------------------------------------------------------ settings <-> form

    /** Stores this page's fields in the shared settings. */
    public void saveSettings() {
        if (workspace == null) {
            return;
        }
        GenerationSettings generation = readGenerationSettings();
        workspace.updateSettings(s -> new AppSettings(generation, loginUrlField.getText(), perRowSpinner.getValue(),
                Path.of(outputDirField.getText()), s.router(), s.voucherTemplate(), s.ui(), s.receipt()));
    }

    /** The shared settings with this page's current fields. */
    private AppSettings currentAppSettings() {
        AppSettings s = workspace.settings();
        return new AppSettings(readGenerationSettings(), loginUrlField.getText(), perRowSpinner.getValue(),
                Path.of(outputDirField.getText()), s.router(), s.voucherTemplate(), s.ui(), s.receipt());
    }

    private GenerationSettings readGenerationSettings() {
        commitSpinners();
        return new GenerationSettings(
                countSpinner.getValue(),
                formatBox.getValue(),
                prefixField.getText(),
                suffixField.getText(),
                codeLength(),
                passwordBox.getValue().style(),
                passwordBox.getValue().length(),
                sharedPasswordField.getText(),
                comboText(profileBox),
                comboText(serverBox),
                uptimeText());
    }

    private void showSettings(AppSettings s) {
        loadingForm = true;
        try {
            GenerationSettings g = s.generation();
            countSpinner.getValueFactory().setValue(Math.clamp(g.count(), 1, GenerationSettings.MAX_COUNT));
            formatBox.setValue(g.format());
            prefixField.setText(g.prefix());
            suffixField.setText(g.suffix());
            codeLengthBox.setValue(clampLength(g.codeLength()));
            passwordBox.setValue(passwordBox.getItems().stream()
                    .filter(c -> c.style() == g.passwordStyle()
                            && (c.style() != PasswordStyle.RANDOM || c.length() == g.passwordLength()))
                    .findFirst().orElse(passwordBox.getItems().getFirst()));
            sharedPasswordField.setText(g.sharedPassword());
            Platform.runLater(this::showExample);
            profileBox.setValue(g.profile());
            serverBox.setValue(g.server());
            Optional<TimeLimit> limit = TimeLimit.parse(g.limitUptime());
            uptimeAmountField.setText(limit.map(l -> String.valueOf(l.amount())).orElse(""));
            uptimeUnitBox.setValue(limit.map(TimeLimit::unit).orElse(TimeLimit.Unit.HOURS));
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
                c.getValue().limitUptime().isBlank() ? Messages.get("common.unlimited") : c.getValue().limitUptime()));
        usernameColumn.getStyleClass().add("monospace");
        passwordColumn.getStyleClass().add("monospace");
    }

    /** Says what the chosen password means, and shows the shared password field when needed. */
    private void bindFieldStates() {
        passwordBox.valueProperty().addListener((obs, old, choice) -> showSharedPasswordField());
        showSharedPasswordField();
        // typing in Starts with / Ends with makes room for them instead of failing later
        for (TextField affix : List.of(prefixField, suffixField)) {
            affix.textProperty().addListener((obs, old, text) -> makeRoomForAffixes());
        }
        for (ObservableValue<?> input : List.of(formatBox.valueProperty(), prefixField.textProperty(),
                suffixField.textProperty(), codeLengthBox.valueProperty(), passwordBox.valueProperty(),
                sharedPasswordField.textProperty(), countSpinner.valueProperty())) {
            input.addListener((obs, old, value) -> showExample());
        }
    }

    /**
     * Card length: chosen from the list only (5 to 32), never typed. Password: no password, any
     * length, the card number again, or one shared password for the whole batch.
     */
    private void setUpLengthAndPasswordLists() {
        for (int length = GenerationSettings.MIN_LENGTH; length <= GenerationSettings.MAX_LENGTH; length++) {
            codeLengthBox.getItems().add(length);
        }

        List<PasswordChoice> choices = new ArrayList<>();
        choices.add(new PasswordChoice(PasswordStyle.NONE, 0));
        for (int length = 1; length <= GenerationSettings.MAX_LENGTH; length++) {
            choices.add(new PasswordChoice(PasswordStyle.RANDOM, length));
        }
        choices.add(new PasswordChoice(PasswordStyle.SAME_AS_CODE, 0));
        choices.add(new PasswordChoice(PasswordStyle.SHARED, 0));
        passwordBox.getItems().setAll(choices);
        passwordBox.setValue(choices.getFirst());
    }

    /** The card length chosen in the list. */
    private int codeLength() {
        Integer value = codeLengthBox.getValue();
        return value == null ? GenerationSettings.defaults().codeLength() : value;
    }

    private void makeRoomForAffixes() {
        if (loadingForm) {
            return;
        }
        int needed = Math.max(GenerationSettings.MIN_LENGTH,
                prefixField.getText().length() + suffixField.getText().length() + GenerationSettings.MIN_RANDOM);
        if (codeLengthBox.getValue() != null && codeLengthBox.getValue() < needed) {
            codeLengthBox.setValue(Math.min(needed, GenerationSettings.MAX_LENGTH));
        }
    }

    /** A real example number for the current fields, or the first problem in plain words. */
    private void showExample() {
        if (formatBox.getValue() == null || codeLengthBox.getValue() == null || passwordBox.getValue() == null) {
            return;
        }
        GenerationSettings s = new GenerationSettings(countSpinner.getValue(), formatBox.getValue(),
                prefixField.getText(), suffixField.getText(), codeLengthBox.getValue(),
                passwordBox.getValue().style(), passwordBox.getValue().length(), sharedPasswordField.getText(), "x",
                "all", "");
        List<String> problems = s.validate();
        exampleLabel.getStyleClass().remove("status-error");
        if (!problems.isEmpty()) {
            exampleLabel.setText("⚠ " + problems.getFirst());
            exampleLabel.getStyleClass().add("status-error");
            return;
        }
        exampleLabel.setText(Messages.get("main.example", context.userGenerator().example(s)));
    }

    /** The shared password field is shown only for "one password for all cards". */
    private void showSharedPasswordField() {
        boolean shared = passwordBox.getValue() != null && passwordBox.getValue().style() == PasswordStyle.SHARED;
        sharedPasswordBox.setVisible(shared);
        sharedPasswordBox.setManaged(shared);
    }

    /** A preview must always match what gets exported, so any change invalidates it. */
    private void clearPreviewWhenSettingsChange() {
        List<ObservableValue<?>> inputs = List.of(
                countSpinner.valueProperty(), formatBox.valueProperty(), prefixField.textProperty(),
                suffixField.textProperty(), codeLengthBox.valueProperty(), passwordBox.valueProperty(),
                sharedPasswordField.textProperty(), profileBox.valueProperty(), profileBox.getEditor().textProperty(),
                serverBox.valueProperty(), serverBox.getEditor().textProperty(),
                uptimeAmountField.textProperty(), uptimeUnitBox.valueProperty());
        for (ObservableValue<?> input : inputs) {
            input.addListener((obs, old, value) -> {
                if (!loadingForm && !users.isEmpty()) {
                    users.clear();
                    setStatus(Messages.get("main.status.settingsChanged"), false);
                }
            });
        }
    }

    private void updateCountLabel() {
        usersCountLabel.setText(users.isEmpty()
                ? Messages.get("main.count.none")
                : Messages.get("main.count.users", users.size()));
    }

    private void showExportDone(ExportResult result) {
        ButtonType openPdf = new ButtonType(Messages.get("export.openPdf"), ButtonBar.ButtonData.YES);
        ButtonType openFolder = new ButtonType(Messages.get("main.openFolder"), ButtonBar.ButtonData.OTHER);
        ButtonType close = new ButtonType(Messages.get("button.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = Dialogs.alert(Alert.AlertType.INFORMATION, window(), Messages.get("export.done.title"),
                Messages.get("export.done.body", result.userCount(), result.folder().toAbsolutePath(),
                        result.rscFile().getFileName()));
        alert.getButtonTypes().setAll(openPdf, openFolder, close);
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
        for (Spinner<Integer> s : List.of(countSpinner, perRowSpinner)) {
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

    /** RouterOS {@code limit-uptime} from the amount and unit; empty amount means no limit. */
    private String uptimeText() {
        String amount = uptimeAmountField.getText().strip();
        if (amount.isEmpty() || Long.parseLong(amount) == 0) {
            return "";
        }
        return new TimeLimit(Long.parseLong(amount), uptimeUnitBox.getValue()).routerText();
    }

    private static int clampLength(int length) {
        return Math.clamp(length, GenerationSettings.MIN_LENGTH, GenerationSettings.MAX_LENGTH);
    }

}
