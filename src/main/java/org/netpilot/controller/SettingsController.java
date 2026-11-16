package org.netpilot.controller;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import org.netpilot.AppContext;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.model.CleanupSettings;
import org.netpilot.model.Theme;
import org.netpilot.model.UiLanguage;
import org.netpilot.model.UiPreferences;

/**
 * "Settings" page: interface language, theme and accent colour, plus About.
 *
 * <p>Everything applies at once: theme and colour restyle the open windows, and a new language
 * rebuilds the main window while the router stays connected.
 */
public class SettingsController implements Page {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter LAST_RUN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private final AppContext context;
    private final ToggleGroup accents = new ToggleGroup();

    private Workspace workspace;
    private boolean loading;

    @FXML
    private ComboBox<UiLanguage> languageBox;
    @FXML
    private ComboBox<Theme> themeBox;
    @FXML
    private HBox accentBox;

    @FXML
    private Label aboutLabel;
    @FXML
    private CheckBox cleanupCheck;
    @FXML
    private ComboBox<LocalTime> cleanupTimeBox;
    @FXML
    private Label cleanupLastLabel;
    @FXML
    private javafx.scene.control.Spinner<Integer> lowStockSpinner;
    @FXML
    private CheckBox autoBackupCheck;
    @FXML
    private Label backupFolderLabel;
    @FXML
    private Label lastBackupLabel;
    private BackupActions backups;

    @FXML
    private void onBackupNow() {
        backups.backUpNow();
    }

    @FXML
    private void onRestore() {
        backups.restore();
    }

    @FXML
    private void onChooseBackupFolder() {
        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle(Messages.get("backup.chooseFolder"));
        java.io.File folder = chooser.showDialog(workspace.window());
        if (folder != null) {
            workspace.updateSettings(s -> s.withBackup(s.backup().withAutomatic(s.backup().enabled(),
                    folder.getAbsolutePath())));
            showBackup();
        }
    }

    /** Automatic backup needs a folder: choosing one is offered when it is turned on without. */
    private void saveAutoBackup(boolean on) {
        if (loading || workspace == null) {
            return;
        }
        if (on && workspace.settings().backup().folder().isEmpty()) {
            onChooseBackupFolder();
            if (workspace.settings().backup().folder().isEmpty()) {
                loading = true;
                autoBackupCheck.setSelected(false);
                loading = false;
                return;
            }
        }
        workspace.updateSettings(s -> s.withBackup(s.backup().withAutomatic(on, s.backup().folder())));
        showBackup();
    }

    private void showBackup() {
        org.netpilot.model.BackupSettings b = workspace.settings().backup();
        boolean wasLoading = loading;
        loading = true;
        autoBackupCheck.setSelected(b.enabled());
        loading = wasLoading;
        backupFolderLabel.setText(b.folder().isEmpty() ? Messages.get("backup.noFolder") : b.folder());
        lastBackupLabel.setText(b.lastRun()
                .map(when -> Messages.get("backup.last", when.format(LAST_RUN)))
                .orElse(Messages.get("backup.never")));
    }

    public SettingsController(AppContext context) {
        this.context = context;
    }

    @FXML
    private void initialize() {
        languageBox.getItems().setAll(UiLanguage.values());
        themeBox.getItems().setAll(Theme.values());
        for (String color : UiPreferences.ACCENTS) {
            RadioButton swatch = new RadioButton();
            swatch.getStyleClass().setAll("accent-swatch");
            swatch.setStyle("-swatch-color: " + color + ";");
            swatch.setUserData(color);
            swatch.setToggleGroup(accents);
            swatch.setTooltip(new Tooltip(color));
            accentBox.getChildren().add(swatch);
        }
        languageBox.valueProperty().addListener((obs, old, language) -> save());
        themeBox.valueProperty().addListener((obs, old, theme) -> save());
        accents.selectedToggleProperty().addListener((obs, old, toggle) -> save());
        aboutLabel.setText(NetPilotApp.NAME + " " + NetPilotApp.VERSION + "\n\n" + Messages.get("about.body"));

        for (int hour = 0; hour < 24; hour++) {
            cleanupTimeBox.getItems().add(LocalTime.of(hour, 0));
        }
        cleanupTimeBox.setConverter(new javafx.util.StringConverter<>() {

            @Override
            public String toString(LocalTime time) {
                return time == null ? "" : time.format(HOUR);
            }

            @Override
            public LocalTime fromString(String text) {
                return LocalTime.parse(text, HOUR);
            }
        });
        lowStockSpinner.setValueFactory(new javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory(0,
                org.netpilot.model.AppSettings.MAX_LOW_STOCK, org.netpilot.model.AppSettings.DEFAULT_LOW_STOCK));
        // typed numbers count without pressing Enter
        lowStockSpinner.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                lowStockSpinner.increment(0);
            }
        });
        lowStockSpinner.valueProperty().addListener((obs, old, below) -> {
            if (!loading && workspace != null && below != null) {
                workspace.updateSettings(s -> s.withLowStockBelow(below));
            }
        });
        cleanupCheck.selectedProperty().addListener((obs, old, on) -> saveCleanup());
        autoBackupCheck.selectedProperty().addListener((obs, old, on) -> saveAutoBackup(on));
        cleanupTimeBox.valueProperty().addListener((obs, old, time) -> saveCleanup());
        initNotifications();
        initAlerts();
    }

    // ------------------------------------------------------------------ router alerts

    @FXML
    private javafx.scene.layout.VBox alertsBox;
    private final java.util.Map<org.netpilot.model.Alert.Kind, javafx.scene.control.CheckBox> alertChecks = new java.util.EnumMap<>(
            org.netpilot.model.Alert.Kind.class);
    private javafx.scene.control.Spinner<Integer> cpuSpinner;
    private javafx.scene.control.Spinner<Integer> cpuMinutesSpinner;
    private javafx.scene.control.Spinner<Integer> memorySpinner;
    private javafx.scene.control.Spinner<Integer> backgroundSpinner;
    private javafx.scene.control.CheckBox desktopCheck;

    /** One check box per alert, the thresholds, Windows notifications and the background check interval. */
    private void initAlerts() {
        javafx.scene.layout.FlowPane kinds = new javafx.scene.layout.FlowPane(16, 8);
        for (org.netpilot.model.Alert.Kind kind : org.netpilot.model.Alert.Kind.values()) {
            javafx.scene.control.CheckBox box = new javafx.scene.control.CheckBox(
                    Messages.get("alert.settings.kind." + kind.name()));
            box.selectedProperty().addListener((obs, old, on) -> saveAlerts());
            alertChecks.put(kind, box);
            kinds.getChildren().add(box);
        }
        cpuSpinner = alertSpinner(50, 100, org.netpilot.model.AlertSettings.DEFAULT_CPU);
        cpuMinutesSpinner = alertSpinner(1, 60, org.netpilot.model.AlertSettings.DEFAULT_CPU_MINUTES);
        memorySpinner = alertSpinner(1, 50, org.netpilot.model.AlertSettings.DEFAULT_MEMORY);
        backgroundSpinner = alertSpinner(1, 120, org.netpilot.model.AlertSettings.DEFAULT_BACKGROUND_MINUTES);
        desktopCheck = new javafx.scene.control.CheckBox(Messages.get("alert.settings.desktop"));
        desktopCheck.selectedProperty().addListener((obs, old, on) -> saveAlerts());
        if (!org.netpilot.platform.DesktopNotifier.supported()) {
            desktopCheck.setDisable(true);
        }
        Label watchHint = new Label(Messages.get("alert.settings.watchHint"));
        watchHint.getStyleClass().add("muted");
        watchHint.setWrapText(true);
        alertsBox.getChildren().setAll(kinds,
                row(Messages.get("alert.settings.cpu"), cpuSpinner, Messages.get("alert.settings.cpuFor"),
                        cpuMinutesSpinner, Messages.get("alert.settings.minutes")),
                row(Messages.get("alert.settings.memory"), memorySpinner, "%"),
                desktopCheck,
                row(Messages.get("alert.settings.background"), backgroundSpinner,
                        Messages.get("alert.settings.minutes")),
                watchHint);
    }

    private javafx.scene.control.Spinner<Integer> alertSpinner(int min, int max, int value) {
        javafx.scene.control.Spinner<Integer> spinner = new javafx.scene.control.Spinner<>(min, max, value);
        spinner.setEditable(true);
        spinner.setPrefWidth(90);
        spinner.setMinWidth(90);
        spinner.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                spinner.increment(0); // typed numbers count without pressing Enter
            }
        });
        spinner.valueProperty().addListener((obs, old, v) -> saveAlerts());
        return spinner;
    }

    /** A row of labels and controls; strings become labels that never shrink. */
    private static javafx.scene.layout.HBox row(Object... parts) {
        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(8);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        for (Object part : parts) {
            if (part instanceof String text) {
                Label label = new Label(text);
                label.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
                row.getChildren().add(label);
            } else {
                row.getChildren().add((javafx.scene.Node) part);
            }
        }
        return row;
    }

    private void showAlerts() {
        org.netpilot.model.AlertSettings a = workspace.settings().alerts();
        alertChecks.forEach((kind, box) -> box.setSelected(a.on(kind)));
        cpuSpinner.getValueFactory().setValue(a.cpuPercent());
        cpuMinutesSpinner.getValueFactory().setValue(a.cpuMinutes());
        memorySpinner.getValueFactory().setValue(a.memoryPercent());
        backgroundSpinner.getValueFactory().setValue(a.backgroundMinutes());
        desktopCheck.setSelected(a.desktop());
    }

    private void saveAlerts() {
        if (loading || workspace == null) {
            return;
        }
        java.util.Set<org.netpilot.model.Alert.Kind> on = java.util.EnumSet.noneOf(org.netpilot.model.Alert.Kind.class);
        alertChecks.forEach((kind, box) -> {
            if (box.isSelected()) {
                on.add(kind);
            }
        });
        workspace.updateSettings(s -> s.withAlerts(new org.netpilot.model.AlertSettings(on, cpuSpinner.getValue(),
                cpuMinutesSpinner.getValue(), memorySpinner.getValue(), desktopCheck.isSelected(),
                backgroundSpinner.getValue(), s.alerts().watched())));
    }

    // ------------------------------------------------------------------ customer messages

    @FXML
    private javafx.scene.control.Spinner<Integer> remindSpinner;
    @FXML
    private javafx.scene.control.TextArea cardTemplateArea;
    @FXML
    private javafx.scene.control.TextArea reminderTemplateArea;
    @FXML
    private Label fieldsLabel;

    private void initNotifications() {
        remindSpinner.setValueFactory(new javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory(1,
                org.netpilot.model.NotificationSettings.MAX_HOURS,
                org.netpilot.model.NotificationSettings.DEFAULT_HOURS));
        remindSpinner.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                remindSpinner.increment(0);
            }
        });
        remindSpinner.valueProperty().addListener((obs, old, hours) -> saveNotifications());
        // the texts are saved when leaving the field, not on every key
        for (javafx.scene.control.TextInputControl input : java.util.List.of(cardTemplateArea,
                reminderTemplateArea)) {
            input.focusedProperty().addListener((obs, was, focused) -> {
                if (!focused) {
                    saveNotifications();
                }
            });
        }
        // the built-in texts show as hints, so an empty field visibly means "use this"
        cardTemplateArea.setPromptText(Messages.get("notify.template.card", ""));
        fieldsLabel.setText(Messages.get("notify.settings.fields", ""));
        reminderTemplateArea.setPromptText(Messages.get("notify.template.reminder", ""));
    }

    private void showNotifications() {
        org.netpilot.model.NotificationSettings n = workspace.settings().notifications();
        remindSpinner.getValueFactory().setValue(n.remindHours());
        cardTemplateArea.setText(n.cardTemplate());
        reminderTemplateArea.setText(n.reminderTemplate());
    }

    private void saveNotifications() {
        if (loading || workspace == null) {
            return;
        }
        String code = workspace.settings().notifications().countryCode(); // chosen next to each number
        org.netpilot.model.NotificationSettings n = new org.netpilot.model.NotificationSettings(code,
                cardTemplateArea.getText(), reminderTemplateArea.getText(),
                remindSpinner.getValue() == null
                        ? org.netpilot.model.NotificationSettings.DEFAULT_HOURS
                        : remindSpinner.getValue());
        workspace.updateSettings(s -> s.withNotifications(n));
    }

    @FXML
    private void onDefaultTemplates() {
        cardTemplateArea.clear();
        reminderTemplateArea.clear();
        saveNotifications();
    }

    /** The automatic clean-up applies at once, like the appearance. */
    private void saveCleanup() {
        if (loading || workspace == null || cleanupTimeBox.getValue() == null) {
            return;
        }
        workspace.updateSettings(
                s -> s.withCleanup(s.cleanup().withSchedule(cleanupCheck.isSelected(), cleanupTimeBox.getValue())));
        cleanupTimeBox.setDisable(!cleanupCheck.isSelected());
    }

    private void showCleanup(CleanupSettings cleanup) {
        cleanupCheck.setSelected(cleanup.enabled());
        // a time saved by hand between full hours stays choosable
        if (!cleanupTimeBox.getItems().contains(cleanup.at())) {
            cleanupTimeBox.getItems().add(cleanup.at());
            cleanupTimeBox.getItems().sort(null);
        }
        cleanupTimeBox.setValue(cleanup.at());
        cleanupTimeBox.setDisable(!cleanup.enabled());
        cleanupLastLabel.setText(cleanup.lastRun()
                .map(when -> Messages.get("cleanup.last", when.format(LAST_RUN), cleanup.lastDeleted()))
                .orElse(Messages.get("cleanup.never")));
    }

    @Override
    public void attach(Workspace shared) {
        this.workspace = shared;
        this.backups = new BackupActions(shared, this::showBackup);
        shown();
    }

    /** Shows the saved values (the theme can also change from the top bar). */
    @Override
    public void shown() {
        loading = true;
        try {
            UiPreferences ui = workspace.settings().ui();
            languageBox.setValue(ui.language());
            themeBox.setValue(ui.theme());
            accents.getToggles().stream()
                    .filter(t -> ui.accent().equals(t.getUserData()))
                    .findFirst()
                    .ifPresent(t -> t.setSelected(true));
            showCleanup(workspace.settings().cleanup());
            lowStockSpinner.getValueFactory().setValue(workspace.settings().lowStockBelow());
            showBackup();
            showUpdates();
            showNotifications();
            showAlerts();
        } finally {
            loading = false;
        }
    }

    // ------------------------------------------------------------------ updates

    @FXML
    private javafx.scene.control.CheckBox autoUpdateCheck;
    @FXML
    private javafx.scene.control.Button checkUpdateButton;
    @FXML
    private javafx.scene.control.ProgressIndicator updateProgress;
    @FXML
    private Label updateStatusLabel;
    private boolean updateWired;

    private void showUpdates() {
        org.netpilot.service.update.UpdatePreferences preferences = org.netpilot.service.update.UpdatePreferences
                .load(org.netpilot.repository.AppPaths.updateFile());
        autoUpdateCheck.setSelected(preferences.automatic());
        updateStatusLabel.setText(preferences.lastCheck()
                .map(day -> Messages.get("update.settings.lastCheck", day))
                .orElse(Messages.get("update.settings.never")));
        if (!updateWired) {
            updateWired = true;
            autoUpdateCheck.selectedProperty().addListener((obs, old, on) -> {
                if (!loading) {
                    UpdateFlow.savePreferences(org.netpilot.service.update.UpdatePreferences
                            .load(org.netpilot.repository.AppPaths.updateFile()).withAutomatic(on));
                }
            });
        }
    }

    /** Checks now, whatever the daily setting or a skipped version says. */
    @FXML
    private void onCheckUpdate() {
        org.netpilot.service.update.UpdateChecker checker = org.netpilot.service.update.UpdateChecker
                .gitHub(NetPilotApp.VERSION);
        javafx.concurrent.Task<java.util.Optional<org.netpilot.service.update.Release>> task = new javafx.concurrent.Task<>() {

            @Override
            protected java.util.Optional<org.netpilot.service.update.Release> call() throws Exception {
                return checker.newer();
            }
        };
        task.setOnRunning(e -> updateBusy(true));
        task.setOnSucceeded(e -> {
            updateBusy(false);
            java.time.LocalDate today = java.time.LocalDate.now();
            UpdateFlow.savePreferences(org.netpilot.service.update.UpdatePreferences
                    .load(org.netpilot.repository.AppPaths.updateFile()).checked(today));
            if (task.getValue().isPresent()) {
                updateStatusLabel.setText(Messages.get("update.settings.found", task.getValue().get().version()));
                UpdateFlow.offer(workspace, task.getValue().get());
            } else {
                updateStatusLabel.setText(Messages.get("update.settings.latest", NetPilotApp.VERSION));
            }
        });
        task.setOnFailed(e -> {
            updateBusy(false);
            updateStatusLabel.setText(Messages.get("update.settings.failed"));
            System.getLogger(SettingsController.class.getName()).log(System.Logger.Level.WARNING,
                    "Update check failed", task.getException());
        });
        Thread thread = new Thread(task, "netpilot-update-check");
        thread.setDaemon(true);
        thread.start();
    }

    private void updateBusy(boolean busy) {
        checkUpdateButton.setDisable(busy);
        updateProgress.setVisible(busy);
        updateProgress.setManaged(busy);
    }

    @FXML
    private void onOpenRepository() {
        context.hostServices().showDocument(NetPilotApp.REPOSITORY_URL);
    }

    private void save() {
        if (loading || workspace == null) {
            return;
        }
        String accent = accents.getSelectedToggle() == null
                ? UiPreferences.DEFAULT_ACCENT
                : (String) accents.getSelectedToggle().getUserData();
        UiPreferences ui = new UiPreferences(languageBox.getValue(), themeBox.getValue(), accent);
        workspace.updateSettings(s -> s.withUi(ui));
        Styling.setAppearance(ui.theme(), ui.accent());
        if (!ui.language().locale().getLanguage().equals(Messages.locale().getLanguage())) {
            // rebuilt after this event: the whole window switches language, the router stays connected
            javafx.application.Platform.runLater(workspace::reloadLanguage);
        }
    }

}
