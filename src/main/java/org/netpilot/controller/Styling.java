package org.netpilot.controller;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.geometry.NodeOrientation;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Window;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.model.Theme;

/**
 * One place that gives every window the same look: stylesheets, light or dark theme, and
 * right-to-left layout with the Cairo font for Arabic.
 *
 * <p>Call {@link #init} once at start-up (after {@link Messages#use}), then {@link #apply} on every
 * new scene or dialog. {@link #setTheme} restyles all open windows at once.
 */
public final class Styling {

    private static final System.Logger LOG = System.getLogger(Styling.class.getName());
    private static final String BASE_CSS = NetPilotApp.resource("css/styles.css").toExternalForm();
    private static final String DARK_CSS = NetPilotApp.resource("css/dark.css").toExternalForm();
    private static final String ARABIC_CLASS = "arabic";

    private static volatile Theme theme = Theme.SYSTEM;

    private Styling() {
    }

    /** Loads the Arabic font when needed and follows the system colour scheme for {@link Theme#SYSTEM}. */
    public static void init(Theme initial) {
        theme = initial;
        if (Messages.isArabic()) {
            loadCairo();
        }
        Platform.getPreferences().colorSchemeProperty().addListener((obs, old, scheme) -> {
            if (theme == Theme.SYSTEM) {
                refreshAll();
            }
        });
    }

    /** Switches the theme of every open window. */
    public static void setTheme(Theme newTheme) {
        theme = newTheme;
        refreshAll();
    }

    public static Theme theme() {
        return theme;
    }

    public static void apply(Scene scene) {
        apply(scene.getRoot());
    }

    /** Styles a scene root or a dialog pane. */
    public static void apply(Parent root) {
        List<String> sheets = new ArrayList<>(List.of(BASE_CSS));
        if (theme.isDark(systemIsDark())) {
            sheets.add(DARK_CSS);
        }
        root.getStylesheets().setAll(sheets);
        boolean arabic = Messages.isArabic();
        root.setNodeOrientation(arabic ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
        if (arabic && !root.getStyleClass().contains(ARABIC_CLASS)) {
            root.getStyleClass().add(ARABIC_CLASS);
        }
    }

    private static void refreshAll() {
        for (Window window : List.copyOf(Window.getWindows())) {
            Scene scene = window.getScene();
            if (scene != null && scene.getRoot() != null) {
                apply(scene);
            }
        }
    }

    private static boolean systemIsDark() {
        return Platform.getPreferences().getColorScheme() == ColorScheme.DARK;
    }

    private static void loadCairo() {
        try (InputStream in = NetPilotApp.class.getResourceAsStream("fonts/Cairo.ttf")) {
            Font font = in == null ? null : Font.loadFont(in, 13);
            if (font == null) {
                LOG.log(System.Logger.Level.WARNING, "Cairo font could not be loaded; using the system font");
            } else {
                LOG.log(System.Logger.Level.DEBUG, "Loaded font family {0}", font.getFamily());
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cairo font could not be loaded", e);
        }
    }
}
