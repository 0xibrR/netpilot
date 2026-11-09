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
        } finally {
            loading = false;
        }
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
