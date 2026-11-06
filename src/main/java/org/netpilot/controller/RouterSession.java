package org.netpilot.controller;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
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
import org.netpilot.model.HotspotProfile;
import org.netpilot.model.HotspotServer;
import org.netpilot.model.RouterInfo;
import org.netpilot.model.RouterSettings;
import org.netpilot.service.router.RouterClient;
import org.netpilot.service.router.RouterConnector;
import org.netpilot.service.router.RouterException;

/**
 * The main window's router session: opens the connection dialog, keeps the connected client,
 * loads hotspot profiles/servers in the background and shows the state in the status bar.
 *
 * <p>Kept out of {@link MainController} so that class stays about generating and exporting.
 */
final class RouterSession {

    private static final System.Logger LOG = System.getLogger(RouterSession.class.getName());

    /** Hotspot data read from the router. */
    record RouterLists(List<HotspotProfile> profiles, List<HotspotServer> servers) {
    }

    private final AppContext context;
    private final Label statusLabel;
    private final Supplier<Window> owner;
    private final Consumer<RouterLists> onListsLoaded;
    private final Runnable onDisconnected;
    private final ReadOnlyBooleanWrapper connected = new ReadOnlyBooleanWrapper(false);

    private RouterClient client;
    private RouterInfo info;
    private RouterLists lists;

    RouterSession(AppContext context, Label statusLabel, Supplier<Window> owner,
            Consumer<RouterLists> onListsLoaded, Runnable onDisconnected) {
        this.context = context;
        this.statusLabel = statusLabel;
        this.owner = owner;
        this.onListsLoaded = onListsLoaded;
        this.onDisconnected = onDisconnected;
        showState();
    }

    ReadOnlyBooleanProperty connectedProperty() {
        return connected.getReadOnlyProperty();
    }

    Optional<RouterClient> client() {
        return Optional.ofNullable(client);
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
            return Optional.of("Profile '" + profile + "' does not exist on the router. Choose one from the list.");
        }
        boolean serverExists = server.equals("all")
                || lists.servers().stream().anyMatch(s -> s.name().equals(server) && !s.disabled());
        if (!serverExists) {
            return Optional.of("Server '" + server + "' does not exist on the router or is disabled.");
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
            scene.getStylesheets().add(NetPilotApp.resource("css/styles.css").toExternalForm());
            Stage stage = new Stage();
            stage.initOwner(owner.get());
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setTitle("Connect to Router - " + NetPilotApp.NAME);
            stage.setResizable(false);
            stage.setScene(scene);
            dialog.prepare(current);
            stage.showAndWait();

            dialog.connection().ifPresent(this::useConnection);
            return Optional.of(dialog.settings());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Cannot open router dialog", e);
            Dialogs.error(owner.get(), "Cannot open the router dialog", e.getMessage());
            return Optional.empty();
        }
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
        task.setOnRunning(e -> statusLabel.setText("Loading hotspot profiles…"));
        task.setOnSucceeded(e -> {
            showState();
            lists = task.getValue();
            onListsLoaded.accept(lists);
        });
        task.setOnFailed(e -> {
            showState();
            Throwable error = task.getException();
            LOG.log(System.Logger.Level.WARNING, "Cannot load hotspot data", error);
            Dialogs.error(owner.get(), "Cannot load hotspot profiles", String.valueOf(error.getMessage()));
        });
        Thread thread = new Thread(task, "netpilot-router-refresh");
        thread.setDaemon(true);
        thread.start();
    }

    void disconnect() {
        if (client != null) {
            LOG.log(System.Logger.Level.INFO, "Disconnected from {0}", client.endpoint().address());
            client.close();
        }
        client = null;
        info = null;
        lists = null;
        connected.set(false);
        showState();
        onDisconnected.run();
    }

    /** Ends the session when the app closes. */
    void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    private void useConnection(RouterConnector.Connection connection) {
        close();
        client = connection.client();
        info = connection.info();
        connected.set(true);
        showState();
        refresh();
    }

    private void showState() {
        statusLabel.getStyleClass().removeAll("router-connected", "router-disconnected");
        if (client == null) {
            statusLabel.setText("● Router: not connected");
            statusLabel.getStyleClass().add("router-disconnected");
        } else {
            statusLabel.setText("● " + info.summary() + " (" + client.endpoint().address() + ")");
            statusLabel.getStyleClass().add("router-connected");
        }
    }
}
