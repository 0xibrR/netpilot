package org.netpilot.controller;

import java.util.Optional;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.netpilot.i18n.Messages;
import org.netpilot.model.Theme;
import org.netpilot.model.UiLanguage;
import org.netpilot.model.UiPreferences;

/**
 * "Preferences" window: interface language and theme.
 *
 * <p>The theme is previewed at once on every window and restored on Cancel. A new language is
 * only saved here; it is used from the next start, because every window is built in one language.
 */
public class PreferencesController {

    private UiPreferences original = UiPreferences.defaults();
    private UiPreferences result;

    @FXML
    private ComboBox<UiLanguage> languageBox;
    @FXML
    private ComboBox<Theme> themeBox;
    @FXML
    private Label restartNote;

    @FXML
    private void initialize() {
        languageBox.getItems().setAll(UiLanguage.values());
        themeBox.getItems().setAll(Theme.values());
        languageBox.valueProperty().addListener((obs, old, language) -> showRestartNote(language));
        themeBox.valueProperty().addListener((obs, old, theme) -> {
            if (theme != null) {
                Styling.setTheme(theme);
            }
        });
    }

    public void prepare(UiPreferences current) {
        this.original = current;
        languageBox.setValue(current.language());
        themeBox.setValue(current.theme());
        showRestartNote(current.language());
    }

    /** The new preferences, if the user pressed Save. */
    public Optional<UiPreferences> result() {
        return Optional.ofNullable(result);
    }

    @FXML
    private void onSave() {
        result = new UiPreferences(languageBox.getValue(), themeBox.getValue());
        stage().close();
    }

    @FXML
    private void onCancel() {
        Styling.setTheme(original.theme());
        stage().close();
    }

    /** Shown while the chosen language differs from the one this run uses. */
    private void showRestartNote(UiLanguage language) {
        boolean differs = language != null && !language.locale().getLanguage().equals(Messages.locale().getLanguage());
        restartNote.setVisible(differs);
        restartNote.setManaged(differs);
        if (restartNote.getScene() != null) {
            stage().sizeToScene();
        }
    }

    private Stage stage() {
        return (Stage) languageBox.getScene().getWindow();
    }
}
