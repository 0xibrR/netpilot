package org.netpilot;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.netpilot.controller.HistoryController;
import org.netpilot.controller.MainController;
import org.netpilot.controller.RouterConnectionController;
import org.netpilot.controller.RouterUsersController;
import org.netpilot.controller.UploadController;
import org.netpilot.repository.AppPaths;

/**
 * JavaFX application: loads the main view and hands the {@link AppContext} to controllers.
 */
public class NetPilotApp extends Application {

    public static final String NAME = "NetPilot";
    public static final String VERSION = "1.0.0";
    public static final String REPOSITORY_URL = "https://github.com/0xibrR/netpilot";

    private static final System.Logger LOG = System.getLogger(NetPilotApp.class.getName());

    private MainController mainController;

    @Override
    public void init() {
        AppLogging.configure(AppPaths.logDirectory());
        AppLogging.installUncaughtExceptionHandler();
        LOG.log(System.Logger.Level.INFO, "Starting {0} {1} on Java {2}", NAME, VERSION, Runtime.version());
    }

    @Override
    public void start(Stage stage) throws IOException {
        AppContext context = AppContext.create(getHostServices());

        FXMLLoader loader = loader("view/main-view.fxml", context);
        Parent root = loader.load();
        mainController = loader.getController();

        // fit small/laptop screens: never open taller or wider than the usable desktop area
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double width = Math.min(1180, screen.getWidth() - 40);
        double height = Math.min(760, screen.getHeight() - 60);
        Scene scene = new Scene(root, width, height);
        scene.getStylesheets().add(resource("css/styles.css").toExternalForm());

        stage.setTitle(NAME);
        stage.setMinWidth(Math.min(900, width));
        stage.setMinHeight(Math.min(560, height));
        stage.setScene(scene);
        stage.centerOnScreen();
        stage.show();
    }

    @Override
    public void stop() {
        if (mainController != null) {
            mainController.saveSettings();
            mainController.close();
        }
        LOG.log(System.Logger.Level.INFO, "{0} stopped", NAME);
    }

    /** Creates a loader whose controllers receive the shared context. */
    public static FXMLLoader loader(String view, AppContext context) {
        FXMLLoader loader = new FXMLLoader(resource(view));
        loader.setControllerFactory(type -> {
            if (type == MainController.class) {
                return new MainController(context);
            }
            if (type == HistoryController.class) {
                return new HistoryController(context);
            }
            if (type == RouterConnectionController.class) {
                return new RouterConnectionController(context);
            }
            if (type == UploadController.class) {
                return new UploadController(context);
            }
            if (type == RouterUsersController.class) {
                return new RouterUsersController(context);
            }
            throw new IllegalArgumentException("Unknown controller: " + type);
        });
        return loader;
    }

    public static URL resource(String path) {
        return Objects.requireNonNull(NetPilotApp.class.getResource(path), "Missing resource: " + path);
    }
}
