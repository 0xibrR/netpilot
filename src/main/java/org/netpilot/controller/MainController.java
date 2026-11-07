package org.netpilot.controller;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;
import org.netpilot.AppContext;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.model.RouterDuration;
import org.netpilot.model.RouterResource;
import org.netpilot.model.Theme;
import org.netpilot.service.router.RouterClient;
import org.netpilot.service.router.RouterException;

/**
 * Main window: top bar with the router connection, side menu, the page area and a status bar
 * with the router's live load, laid out like WinBox.
 *
 * <p>Pages are loaded the first time they are opened and then kept. This class only switches
 * pages and forwards router events to them; each page does its own work.
 */
public class MainController implements RouterSession.Listener {

    private static final System.Logger LOG = System.getLogger(MainController.class.getName());
    private static final Duration RESOURCE_INTERVAL = Duration.seconds(5);

    private final AppContext context;
    private final Map<PageId, LoadedPage> pages = new EnumMap<>(PageId.class);
    private final Map<PageId, ToggleButton> menuButtons = new EnumMap<>(PageId.class);
    private final ToggleGroup menuGroup = new ToggleGroup();
    private final Timeline resourcePoller = new Timeline(new KeyFrame(RESOURCE_INTERVAL, e -> pollResources()));

    private RouterSession router;
    private Workspace workspace;
    private PageId current;
    private boolean polling;

    @FXML
    private BorderPane root;
    @FXML
    private Label routerChip;
    @FXML
    private MenuButton routerMenu;
    @FXML
    private MenuItem reloadItem;
    @FXML
    private MenuItem disconnectItem;
    @FXML
    private Button themeButton;
    @FXML
    private VBox sideMenu;
    @FXML
    private StackPane pageHost;
    @FXML
    private VBox notConnectedPane;
    @FXML
    private Label routerInfoLabel;
    @FXML
    private Label cpuLabel;
    @FXML
    private Label memoryLabel;
    @FXML
    private Label uptimeLabel;
    @FXML
    private Label versionLabel;

    /** A loaded page: its view and its controller. */
    private record LoadedPage(Node view, Page controller) {
    }

    public MainController(AppContext context) {
        this.context = context;
    }

    @FXML
    private void initialize() {
        router = new RouterSession(context, routerChip, this::window);
        router.addListener(this);
        workspace = new Workspace(context, router, this::window, context.settingsRepository().load());
        workspace.setNavigator(this::show);
        Toast toast = new Toast(pageHost);
        workspace.setToaster(toast::show);
        workspace.setConnector(this::onConnectRouter);

        reloadItem.disableProperty().bind(router.connectedProperty().not());
        disconnectItem.disableProperty().bind(router.connectedProperty().not());
        routerMenu.setGraphic(Icons.of(Icons.ROUTER));
        themeButton.setGraphic(Icons.of(Icons.MOON));
        versionLabel.setText(NetPilotApp.NAME + " " + NetPilotApp.VERSION);
        resourcePoller.setCycleCount(Timeline.INDEFINITE);
        showResources(null);

        buildSideMenu();
        load(PageId.GENERATE); // always loaded: other pages print vouchers through it
        show(PageId.GENERATE);
        router.autoConnect(workspace.settings().router(), workspace::toast);
        root.sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) {
                addShortcuts();
            }
        });
    }

    /** Saves page state and ends the router session; called when the app closes. */
    public void close() {
        resourcePoller.stop();
        pages.values().forEach(p -> {
            if (p.controller() instanceof GenerateController generate) {
                generate.saveSettings();
            }
        });
        router.close();
    }

    // ------------------------------------------------------------------ navigation

    private void buildSideMenu() {
        for (PageId page : PageId.values()) {
            ToggleButton button = new ToggleButton(Messages.get(page.labelKey()), Icons.of(page.icon()));
            button.getStyleClass().add("nav-button");
            button.setToggleGroup(menuGroup);
            button.setMaxWidth(Double.MAX_VALUE);
            button.setOnAction(e -> show(page));
            menuButtons.put(page, button);
            sideMenu.getChildren().add(button);
        }
        // a selected menu entry stays selected when clicked again
        menuGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null && old != null) {
                old.setSelected(true);
            }
        });
    }

    private void addShortcuts() {
        PageId[] all = PageId.values();
        for (int i = 0; i < all.length && i < 9; i++) {
            PageId page = all[i];
            KeyCombination keys = new KeyCodeCombination(KeyCode.getKeyCode(String.valueOf(i + 1)),
                    KeyCombination.SHORTCUT_DOWN);
            root.getScene().getAccelerators().put(keys, () -> show(page));
        }
    }

    private void show(PageId page) {
        current = page;
        menuButtons.get(page).setSelected(true);
        boolean blocked = page.needsRouter() && router.client().isEmpty();
        notConnectedPane.setVisible(blocked);
        pages.values().forEach(p -> p.view().setVisible(false));
        if (blocked) {
            return;
        }
        Optional<LoadedPage> loaded = Optional.ofNullable(pages.get(page)).or(() -> load(page));
        loaded.ifPresent(p -> {
            p.view().setVisible(true);
            p.controller().shown();
        });
    }

    private Optional<LoadedPage> load(PageId page) {
        try {
            FXMLLoader loader = NetPilotApp.loader(page.view(), context);
            Parent view = loader.load();
            Page controller = loader.getController();
            view.setVisible(false);
            pageHost.getChildren().add(view);
            LoadedPage loaded = new LoadedPage(view, controller);
            pages.put(page, loaded);
            controller.attach(workspace);
            // catch up with a router that connected before this page existed
            router.client().ifPresent(client -> controller.routerConnected(client, router.displayName()));
            router.lists().ifPresent(controller::routerListsLoaded);
            return Optional.of(loaded);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Cannot open page " + page, e);
            Dialogs.error(window(), Messages.get("error.openWindow"), String.valueOf(e.getMessage()));
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ router

    @FXML
    private void onConnectRouter() {
        router.showConnectDialog(workspace.settings().router())
                .ifPresent(routerSettings -> workspace.updateSettings(s -> s.withRouter(routerSettings)));
    }

    @FXML
    private void onRouterChipClicked() {
        onConnectRouter();
    }

    @FXML
    private void onReloadRouter() {
        router.refresh();
    }

    @FXML
    private void onDisconnectRouter() {
        router.disconnect();
    }

    @Override
    public void routerConnected(RouterClient client, String name) {
        pages.values().forEach(p -> p.controller().routerConnected(client, name));
        router.info().ifPresent(info -> routerInfoLabel.setText(info.summary()));
        resourcePoller.play();
        pollResources();
        if (current != null && current.needsRouter()) {
            show(current);
        }
    }

    @Override
    public void routerListsLoaded(RouterSession.RouterLists lists) {
        pages.values().forEach(p -> p.controller().routerListsLoaded(lists));
    }

    @Override
    public void routerDisconnected() {
        resourcePoller.stop();
        showResources(null);
        pages.values().forEach(p -> p.controller().routerDisconnected());
        if (current != null && current.needsRouter()) {
            show(current);
        }
    }

    /** Reads CPU, memory and uptime in the background; skipped while the previous read runs. */
    private void pollResources() {
        Optional<RouterClient> client = router.client();
        if (client.isEmpty() || polling) {
            return;
        }
        polling = true;
        RouterClient target = client.get();
        Task<RouterResource> task = new Task<>() {

            @Override
            protected RouterResource call() throws RouterException {
                return target.resource();
            }
        };
        task.setOnSucceeded(e -> {
            polling = false;
            if (router.client().orElse(null) == target) {
                showResources(task.getValue());
            }
        });
        task.setOnFailed(e -> {
            polling = false;
            LOG.log(System.Logger.Level.DEBUG, "Cannot read router resources", task.getException());
        });
        Thread thread = new Thread(task, "netpilot-router-resources");
        thread.setDaemon(true);
        thread.start();
    }

    private void showResources(RouterResource resource) {
        boolean show = resource != null;
        for (Label label : new Label[]{cpuLabel, memoryLabel, uptimeLabel}) {
            label.setVisible(show);
            label.setManaged(show);
        }
        if (!show) {
            routerInfoLabel.setText(Messages.get("router.notConnected"));
            return;
        }
        cpuLabel.setText(Messages.get("status.cpu", resource.cpuLoad()));
        memoryLabel.setText(Messages.get("status.memory", mebibytes(resource.usedMemory()),
                mebibytes(resource.totalMemory())));
        uptimeLabel.setText(Messages.get("status.uptime",
                RouterDuration.parse(resource.uptime()).map(RouterDuration::format).orElse(resource.uptime())));
    }

    private static String mebibytes(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }

    // ------------------------------------------------------------------ theme

    /** Light → semi-dark → dark → light; Settings offers every choice. */
    @FXML
    private void onToggleTheme() {
        Theme next = switch (Styling.theme()) {
            case SYSTEM, LIGHT -> Theme.SEMI_DARK;
            case SEMI_DARK -> Theme.DARK;
            case DARK -> Theme.LIGHT;
        };
        workspace.updateSettings(s -> s.withUi(new org.netpilot.model.UiPreferences(s.ui().language(), next,
                s.ui().accent())));
        Styling.setTheme(next);
        pages.values().forEach(p -> {
            if (p.controller() instanceof SettingsController settings) {
                settings.shown();
            }
        });
    }

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }
}
