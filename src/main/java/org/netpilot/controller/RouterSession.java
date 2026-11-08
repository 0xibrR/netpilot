package org.netpilot.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.netpilot.AppContext;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.model.HotspotProfile;
import org.netpilot.model.HotspotServer;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.model.RouterInfo;
import org.netpilot.model.RouterSettings;
import org.netpilot.service.router.RouterClient;
import org.netpilot.service.router.RouterConnector;
import org.netpilot.service.router.RouterException;

/**
 * The application's router session: opens the connection dialog, keeps the connected client,
 * loads hotspot profiles/servers in the background and shows the state in the top bar.
 *
 * <p>Pages register a {@link Listener} to react when a router connects, its lists load, or it
 * disconnects.
 */
final class RouterSession {

    private static final System.Logger LOG = System.getLogger(RouterSession.class.getName());

    /** Hotspot data read from the router. */
    record RouterLists(List<HotspotProfile> profiles, List<HotspotServer> servers) {
    }

    /** Connection events, always delivered on the JavaFX thread. */
    interface Listener {

        default void routerConnected(RouterClient client, String name) {
        }

        default void routerListsLoaded(RouterLists lists) {
        }

        default void routerDisconnected() {
        }
    }

    private final AppContext context;
    private Label statusLabel;
    private Supplier<Window> owner;
    private final List<Listener> listeners = new ArrayList<>();
    private final ReadOnlyBooleanWrapper connected = new ReadOnlyBooleanWrapper(false);

    private RouterClient client;
    private RouterInfo info;
    private RouterLists lists;

    RouterSession(AppContext context, Label statusLabel, Supplier<Window> owner) {
        this.context = context;
        this.statusLabel = statusLabel;
        this.owner = owner;
        showState();
    }

    /**
     * Moves the session into a rebuilt main window (language switch): new status label and
     * owner, and the old window's listeners are dropped.
     */
    RouterSession rebind(Label label, Supplier<Window> window) {
        this.statusLabel = label;
        this.owner = window;
        listeners.clear();
        showState();
        return this;
    }

    void addListener(Listener listener) {
        listeners.add(listener);
    }

    ReadOnlyBooleanProperty connectedProperty() {
        return connected.getReadOnlyProperty();
    }

    Optional<RouterClient> client() {
        return Optional.ofNullable(client);
    }

    Optional<RouterInfo> info() {
        return Optional.ofNullable(info);
    }

    /** Profiles and servers loaded from the connected router, if loaded. */
    Optional<RouterLists> lists() {
        return Optional.ofNullable(lists);
    }

    /** e.g. {@code hEX (10.0.0.1:80)}; only valid while connected. */
    String displayName() {
        String name = info.identity().isEmpty() ? info.boardName() : info.identity();
        return name + " (" + client.endpoint().address() + ")";
    }

    /**
     * @return a message if the router does not have this profile or server, based on the lists
     *         loaded at connect time; empty if they exist or the lists are not loaded
     */
    Optional<String> checkProfileAndServer(String profile, String server) {
        if (lists == null) {
            return Optional.empty();
        }
        if (lists.profiles().stream().noneMatch(p -> p.name().equals(profile))) {
            return Optional.of(Messages.get("main.status.profileMissing", profile));
        }
        boolean serverExists = server.equals("all")
                || lists.servers().stream().anyMatch(s -> s.name().equals(server) && !s.disabled());
        if (!serverExists) {
            return Optional.of(Messages.get("send.serverMissing", server));
        }
        return Optional.empty();
    }

    /**
     * Shows the connection dialog.
     *
     * @return router settings to save (last endpoint, trusted certificates), even when the user
     *         cancelled after trusting a certificate
     */
    Optional<RouterSettings> showConnectDialog(RouterSettings current) {
        try {
            FXMLLoader loader = NetPilotApp.loader("view/router-connection-view.fxml", context);
            Parent root = loader.load();
            RouterConnectionController dialog = loader.getController();

            Scene scene = new Scene(root);
            Styling.apply(scene);
            Stage stage = new Stage();
            stage.initOwner(owner.get());
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setTitle(Messages.get("window.connect") + " - " + NetPilotApp.NAME);
            stage.setResizable(false);
            stage.setScene(scene);
            // never taller or wider than the screen, so the buttons stay reachable on small laptops
            javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
            stage.setMaxWidth(screen.getWidth());
            stage.setMaxHeight(screen.getHeight());
            dialog.prepare(current);
            stage.showAndWait();

            dialog.connection().ifPresent(this::useConnection);
            return Optional.of(dialog.settings());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Cannot open router dialog", e);
            Dialogs.error(owner.get(), Messages.get("error.openWindow"), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Connects to the last router in the background when the user turned on automatic connection
     * and its password is remembered. Problems are only reported, never asked about: a changed
     * certificate, for example, still needs the Connect dialog.
     *
     * @param report receives a short message for the user
     */
    void autoConnect(RouterSettings settings, java.util.function.Consumer<String> report) {
        RouterEndpoint endpoint = settings.lastEndpoint();
        if (!settings.autoConnect() || client != null) {
            return;
        }
        Optional<char[]> password = context.passwordVault().load(endpoint);
        if (password.isEmpty()) {
            return;
        }
        String trusted = settings.trustedFingerprint(endpoint).orElse(null);
        Task<RouterConnector.Connection> task = new Task<>() {

            @Override
            protected RouterConnector.Connection call() throws RouterException {
                return context.routerConnector().connect(endpoint, password.get(), trusted); // wipes the password
            }
        };
        task.setOnRunning(e -> statusLabel.setText(Messages.get("connect.connecting")));
        task.setOnSucceeded(e -> {
            if (client != null) {
                task.getValue().client().close(); // the user connected by hand meanwhile
                return;
            }
            useConnection(task.getValue());
            report.accept(Messages.get("router.autoConnect.done", displayName()));
        });
        task.setOnFailed(e -> {
            showState();
            LOG.log(System.Logger.Level.WARNING, "Automatic connection to {0} failed: {1}", endpoint.address(),
                    task.getException().getMessage());
            report.accept(Messages.get("router.autoConnect.failed", String.valueOf(task.getException().getMessage())));
        });
        Thread thread = new Thread(task, "netpilot-auto-connect");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean quietRefreshRunning;

    /**
     * Reloads profiles and servers in the background without any message, and tells listeners
     * only when something changed (e.g. a profile added in WinBox).
     */
    void refreshQuietly() {
        RouterClient current = client;
        if (current == null || quietRefreshRunning) {
            return;
        }
        quietRefreshRunning = true;
        Task<RouterLists> task = new Task<>() {

            @Override
            protected RouterLists call() throws RouterException {
                return new RouterLists(current.hotspotProfiles(), current.hotspotServers());
            }
        };
        task.setOnSucceeded(e -> {
            quietRefreshRunning = false;
            if (client == current && !task.getValue().equals(lists)) {
                lists = task.getValue();
                List.copyOf(listeners).forEach(l -> l.routerListsLoaded(lists));
            }
        });
        task.setOnFailed(e -> {
            quietRefreshRunning = false;
            LOG.log(System.Logger.Level.DEBUG, "Live refresh of hotspot lists failed", task.getException());
        });
        Thread thread = new Thread(task, "netpilot-router-live");
        thread.setDaemon(true);
        thread.start();
    }

    /** Reloads hotspot profiles and servers from the connected router. */
    void refresh() {
        RouterClient current = client;
        if (current == null) {
            return;
        }
        Task<RouterLists> task = new Task<>() {

            @Override
            protected RouterLists call() throws RouterException {
                return new RouterLists(current.hotspotProfiles(), current.hotspotServers());
            }
        };
        task.setOnRunning(e -> statusLabel.setText(Messages.get("router.loadingLists")));
        task.setOnSucceeded(e -> {
            showState();
            if (client != current) {
                return; // disconnected or switched router meanwhile
            }
            lists = task.getValue();
            List.copyOf(listeners).forEach(l -> l.routerListsLoaded(lists));
        });
        task.setOnFailed(e -> {
            showState();
            Throwable error = task.getException();
            LOG.log(System.Logger.Level.WARNING, "Cannot load hotspot data", error);
            Dialogs.error(owner.get(), Messages.get("router.loadListsFailed"), String.valueOf(error.getMessage()));
        });
        Thread thread = new Thread(task, "netpilot-router-refresh");
        thread.setDaemon(true);
        thread.start();
    }

    void disconnect() {
        if (client == null) {
            return;
        }
        LOG.log(System.Logger.Level.INFO, "Disconnected from {0}", client.endpoint().address());
        client.close();
        client = null;
        info = null;
        lists = null;
        connected.set(false);
        showState();
        List.copyOf(listeners).forEach(Listener::routerDisconnected);
    }

    /** Ends the session when the app closes. */
    void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    private void useConnection(RouterConnector.Connection connection) {
        if (client != null) {
            disconnect();
        }
        client = connection.client();
        info = connection.info();
        connected.set(true);
        showState();
        String name = displayName();
        List.copyOf(listeners).forEach(l -> l.routerConnected(client, name));
        refresh();
    }

    private void showState() {
        statusLabel.getStyleClass().removeAll("router-connected", "router-disconnected");
        if (client == null) {
            statusLabel.setText("● " + Messages.get("router.notConnected"));
            statusLabel.getStyleClass().add("router-disconnected");
        } else {
            statusLabel.setText("● " + displayName());
            statusLabel.getStyleClass().add("router-connected");
        }
    }
}
