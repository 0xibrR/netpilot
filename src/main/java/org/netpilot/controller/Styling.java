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
import org.netpilot.model.UiPreferences;

/**
 * One place that gives every window the same look: stylesheets, light / semi-dark / dark theme,
 * the accent colour, and right-to-left layout with the Cairo font for Arabic.
 *
 * <p>Call {@link #init} once at start-up (after {@link Messages#use}), then {@link #apply} on every
 * new scene or dialog. {@link #setAppearance} restyles all open windows at once.
 */
public final class Styling {

    private static final System.Logger LOG = System.getLogger(Styling.class.getName());
    private static final String BASE_CSS = NetPilotApp.resource("css/styles.css").toExternalForm();
    private static final String DARK_CSS = NetPilotApp.resource("css/dark.css").toExternalForm();
    private static final String ARABIC_CLASS = "arabic";
    private static final String DARK_CHROME_CLASS = "chrome-dark";

    private static volatile Theme theme = Theme.SYSTEM;
    private static volatile String accent = UiPreferences.DEFAULT_ACCENT;

    private Styling() {
    }

    /** Loads the Arabic font when needed and follows the system colour scheme for {@link Theme#SYSTEM}. */
    public static void init(UiPreferences ui) {
        theme = ui.theme();
        accent = ui.accent();
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
        setAppearance(newTheme, accent);
    }

    /** Switches theme and accent colour of every open window. */
    public static void setAppearance(Theme newTheme, String newAccent) {
        theme = newTheme;
        accent = newAccent;
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
        boolean systemDark = systemIsDark();
        List<String> sheets = new ArrayList<>(List.of(BASE_CSS));
        if (theme.isDark(systemDark)) {
            sheets.add(DARK_CSS);
        }
        root.getStylesheets().setAll(sheets);
        toggleClass(root, DARK_CHROME_CLASS, theme.darkChrome(systemDark));
        // the hover shade is derived in CSS from -np-accent
        root.setStyle("-np-accent: " + accent + ";");
        boolean arabic = Messages.isArabic();
        root.setNodeOrientation(arabic ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
        toggleClass(root, ARABIC_CLASS, arabic);
    }

    private static void toggleClass(Parent node, String styleClass, boolean on) {
        if (on && !node.getStyleClass().contains(styleClass)) {
            node.getStyleClass().add(styleClass);
        } else if (!on) {
            node.getStyleClass().remove(styleClass);
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
