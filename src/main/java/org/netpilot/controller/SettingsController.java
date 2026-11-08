package org.netpilot.controller;

import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import org.netpilot.AppContext;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
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
    }

    @Override
    public void attach(Workspace shared) {
        this.workspace = shared;
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
