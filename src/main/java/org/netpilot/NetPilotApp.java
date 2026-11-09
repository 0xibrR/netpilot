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
import javafx.util.Callback;
import org.netpilot.controller.DashboardController;
import org.netpilot.controller.GenerateController;
import org.netpilot.controller.HistoryController;
import org.netpilot.controller.HotspotController;
import org.netpilot.controller.MainController;
import org.netpilot.controller.PingController;
import org.netpilot.controller.ReportsController;
import org.netpilot.controller.ResellersController;
import org.netpilot.controller.RouterConnectionController;
import org.netpilot.controller.RouterUsersController;
import org.netpilot.controller.SettingsController;
import org.netpilot.controller.Styling;
import org.netpilot.controller.UiSession;
import org.netpilot.controller.UploadController;
import org.netpilot.controller.VoucherDesignController;
import org.netpilot.i18n.Messages;
import org.netpilot.model.UiPreferences;
import org.netpilot.repository.AppPaths;

/**
 * JavaFX application: loads the main view and hands the {@link AppContext} to controllers.
 */
public class NetPilotApp extends Application {

    public static final String NAME = "NetPilot";
    public static final String VERSION = "1.1.0";
    public static final String REPOSITORY_URL = "https://github.com/0xibrR/netpilot";

    private static final System.Logger LOG = System.getLogger(NetPilotApp.class.getName());

    private MainController mainController;
    private final UiSession uiSession = new UiSession();
    private AppContext context;

    @Override
    public void init() {
        AppLogging.configure(AppPaths.logDirectory());
        AppLogging.installUncaughtExceptionHandler();
        LOG.log(System.Logger.Level.INFO, "Starting {0} {1} on Java {2}, {3}", NAME, VERSION, Runtime.version(),
                org.netpilot.platform.PlatformInfo.current().describe());
        try {
            // before anything reads the data: a restore cut short (power cut) is undone here
            if (new org.netpilot.service.BackupService(AppPaths.dataDirectory(), java.time.Clock.systemDefaultZone(),
                    VERSION).recoverInterruptedRestore()) {
                LOG.log(System.Logger.Level.WARNING, "An interrupted restore was undone; the previous data is back");
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Cannot check for an interrupted restore", e);
        }
    }

    @Override
    public void start(Stage stage) throws IOException {
        AppContext context = AppContext.create(getHostServices());

        // language and theme must be known before the first window is built
        UiPreferences ui = context.settingsRepository().load().ui();
        Messages.use(ui.language().locale());
        Styling.init(ui);

        this.context = context;
        uiSession.setReloader(() -> reloadUi(stage));
        Parent root = loadMainView();

        // fit small/laptop screens: never open taller or wider than the usable desktop area
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double width = Math.min(1280, screen.getWidth() - 40);
        double height = Math.min(760, screen.getHeight() - 60);
        Scene scene = new Scene(root, width, height);
        Styling.apply(scene);

        stage.setTitle(NAME);
        // title bar and taskbar; dialogs owned by this window show it too
        for (int size : new int[]{16, 32, 48, 64, 128, 256}) {
            stage.getIcons().add(new javafx.scene.image.Image(
                    resource("icons/netpilot-" + size + ".png").toExternalForm()));
        }
        stage.setMinWidth(Math.min(980, width));
        stage.setMinHeight(Math.min(560, height));
        stage.setScene(scene);
        stage.centerOnScreen();
        stage.show();
    }

    /** The main window's view; its controller shares the run's {@link UiSession}. */
    private Parent loadMainView() throws IOException {
        FXMLLoader loader = loader("view/main-view.fxml", context);
        Callback<Class<?>, Object> pages = loader.getControllerFactory();
        loader.setControllerFactory(
                type -> type == MainController.class ? new MainController(context, uiSession) : pages.call(type));
        Parent root = loader.load();
        mainController = loader.getController();
        return root;
    }

    /**
     * Rebuilds the main window in the language now saved in the settings. The router stays
     * connected and the same page reopens, so switching language needs no restart.
     */
    private void reloadUi(Stage stage) {
        try {
            mainController.detach();
            UiPreferences ui = context.settingsRepository().load().ui();
            Messages.use(ui.language().locale());
            Styling.setAppearance(ui.theme(), ui.accent()); // a restore may bring another theme
            Parent root = loadMainView();
            stage.getScene().setRoot(root);
            Styling.apply(stage.getScene());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Cannot rebuild the window in the new language", e);
        }
    }

    @Override
    public void stop() {
        if (mainController != null) {
            mainController.close();
        }
        LOG.log(System.Logger.Level.INFO, "{0} stopped", NAME);
    }

    /** Creates a loader whose controllers receive the shared context and whose texts use the UI language. */
    public static FXMLLoader loader(String view, AppContext context) {
        FXMLLoader loader = new FXMLLoader(resource(view), Messages.bundle());
        loader.setControllerFactory(type -> {
            // MainController is created by loadMainView, which gives it the run's UiSession
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
            if (type == VoucherDesignController.class) {
                return new VoucherDesignController(context);
            }
            if (type == ReportsController.class) {
                return new ReportsController(context);
            }
            if (type == GenerateController.class) {
                return new GenerateController(context);
            }
            if (type == DashboardController.class) {
                return new DashboardController(context);
            }
            if (type == HotspotController.class) {
                return new HotspotController(context);
            }
            if (type == PingController.class) {
                return new PingController(context);
            }
            if (type == ResellersController.class) {
                return new ResellersController(context);
            }
            if (type == SettingsController.class) {
                return new SettingsController(context);
            }
            throw new IllegalArgumentException("Unknown controller: " + type);
        });
        return loader;
    }

    public static URL resource(String path) {
        return Objects.requireNonNull(NetPilotApp.class.getResource(path), "Missing resource: " + path);
    }
}
