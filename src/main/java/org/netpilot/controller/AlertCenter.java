package org.netpilot.controller;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Task;
import javafx.scene.control.Button;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.model.Alert;
import org.netpilot.model.AlertSettings;
import org.netpilot.model.Branch;
import org.netpilot.model.RouterResource;
import org.netpilot.platform.DesktopNotifier;
import org.netpilot.repository.AlertLog;
import org.netpilot.repository.AppPaths;
import org.netpilot.service.AlertMonitor;
import org.netpilot.service.router.RouterClient;
import org.netpilot.service.router.RouterConnector;
import org.netpilot.service.router.RouterException;

/**
 * Router alerts: feeds readings to the {@link AlertMonitor}, keeps the {@link AlertLog}, and tells
 * the user (the bell in the top bar, a toast, and a desktop notification). The connected router
 * is read with the status bar every few seconds, plus its interfaces and hotspot servers once a
 * minute; branches the user chose are checked in the background with their remembered password,
 * one short read-only connection each. Nothing on any router is changed.
 */
final class AlertCenter {

    private static final System.Logger LOG = System.getLogger(AlertCenter.class.getName());
    private static final javafx.util.Duration TICK = javafx.util.Duration.seconds(60);
    /** The DNS check reads the firewall: every ten minutes is plenty. */
    private static final Duration DNS_EVERY = Duration.ofMinutes(10);

    private final Workspace workspace;
    private final RouterSession router;
    private final Button bell;
    private final AlertMonitor monitor = new AlertMonitor();
    private final AlertLog log = new AlertLog(AppPaths.alertsFile());
    private final Timeline ticker = new Timeline();
    private int unread;
    private boolean detailRunning;
    private boolean backgroundRunning;
    private LocalDateTime lastDnsCheck = LocalDateTime.MIN;
    private LocalDateTime lastBackground = LocalDateTime.MIN;

    AlertCenter(Workspace workspace, RouterSession router, Button bell) {
        this.workspace = workspace;
        this.router = router;
        this.bell = bell;
    }

    /** Ends alerts left open by an earlier run and starts the minute ticker. */
    void start() {
        try {
            log.closeOpen(LocalDateTime.now());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot close old alerts: {0}", e.getMessage());
        }
        bell.setGraphic(Icons.of(Icons.BELL));
        bell.setOnAction(e -> openList());
        showCount();
        ticker.getKeyFrames().setAll(new KeyFrame(TICK, e -> tick()));
        ticker.setCycleCount(Animation.INDEFINITE);
        ticker.play();
    }

    void stop() {
        ticker.stop();
    }

    // ------------------------------------------------------------------ the connected router

    /** A status-bar reading of the connected router. */
    void resource(String address, RouterResource resource) {
        handle(monitor.observe(address, branchName(address), AlertMonitor.Reading.of(resource), settings(),
                LocalDateTime.now()));
    }

    /** A status-bar reading that failed. */
    void resourceFailed(String address) {
        handle(monitor.failed(address, branchName(address), AlertMonitor.CONNECTED_FAILURES, settings(),
                LocalDateTime.now()));
    }

    private void tick() {
        router.client().ifPresent(this::readDetails);
        AlertSettings s = settings();
        if (!s.watched().isEmpty() && !lastBackground.plusMinutes(s.backgroundMinutes()).isAfter(LocalDateTime.now())) {
            lastBackground = LocalDateTime.now();
            readBackground();
        }
    }

    /** Interfaces and hotspot servers of the connected router; the DNS check every ten minutes. */
    private void readDetails(RouterClient client) {
        if (detailRunning) {
            return;
        }
        detailRunning = true;
        String address = client.endpoint().address();
        boolean dnsDue = settings().on(Alert.Kind.DNS_OPEN)
                && !lastDnsCheck.plus(DNS_EVERY).isAfter(LocalDateTime.now());
        Task<AlertMonitor.Reading> task = new Task<>() {

            @Override
            protected AlertMonitor.Reading call() throws RouterException {
                Optional<Boolean> dnsOpen = Optional.empty();
                if (dnsDue) {
                    dnsOpen = Optional.of(client.dnsSettings().remoteRequests() && !client.firewallBlocksDns());
                }
                return new AlertMonitor.Reading(Optional.empty(), Optional.of(client.interfaces()),
                        Optional.of(client.hotspotServers()), dnsOpen);
            }
        };
        task.setOnSucceeded(e -> {
            detailRunning = false;
            if (dnsDue) {
                lastDnsCheck = LocalDateTime.now();
            }
            handle(monitor.observe(address, branchName(address), task.getValue(), settings(), LocalDateTime.now()));
        });
        task.setOnFailed(e -> {
            detailRunning = false;
            LOG.log(System.Logger.Level.DEBUG, "Alert details not read: {0}", task.getException().getMessage());
        });
        run(task, "netpilot-alerts");
    }

    // ------------------------------------------------------------------ branches in the background

    /** A short read-only look at each watched branch that is not the connected one. */
    private void readBackground() {
        if (backgroundRunning) {
            return;
        }
        String connected = router.client().map(c -> c.endpoint().address()).orElse("");
        List<Branch> branches = workspace.settings().router().branches().stream()
                .filter(b -> settings().watched().contains(b.id()) && !b.address().equals(connected))
                .toList();
        if (branches.isEmpty()) {
            return;
        }
        backgroundRunning = true;
        AlertSettings s = settings();
        Task<List<AlertMonitor.Event>> task = new Task<>() {

            @Override
            protected List<AlertMonitor.Event> call() {
                List<AlertMonitor.Event> events = new ArrayList<>();
                for (Branch b : branches) {
                    events.addAll(check(b, s));
                }
                return events;
            }
        };
        task.setOnSucceeded(e -> {
            backgroundRunning = false;
            handle(task.getValue());
        });
        task.setOnFailed(e -> backgroundRunning = false);
        run(task, "netpilot-alerts-branches");
    }

    /** One branch: connect with its remembered password, read, close. Runs on the background thread. */
    private List<AlertMonitor.Event> check(Branch branch, AlertSettings s) {
        Optional<char[]> password = workspace.context().passwordVault().load(branch.endpoint());
        if (password.isEmpty()) {
            return List.of(); // nothing remembered: it cannot be watched, and that is no alert
        }
        String trusted = workspace.settings().router().trustedFingerprint(branch.endpoint()).orElse(null);
        RouterConnector.Connection connection = null;
        try {
            connection = workspace.context().routerConnector().connect(branch.endpoint(), password.get(), trusted);
            RouterClient client = connection.client();
            AlertMonitor.Reading reading = new AlertMonitor.Reading(Optional.of(client.resource()),
                    Optional.of(client.interfaces()), Optional.of(client.hotspotServers()), Optional.empty());
            return monitor.observe(branch.address(), branch.name(), reading, s, LocalDateTime.now());
        } catch (RouterException | RuntimeException e) {
            LOG.log(System.Logger.Level.INFO, "Background check of {0} failed: {1}", branch.name(), e.getMessage());
            return monitor.failed(branch.address(), branch.name(), AlertMonitor.BACKGROUND_FAILURES, s,
                    LocalDateTime.now());
        } finally {
            if (connection != null) {
                connection.client().close();
            }
        }
    }

    // ------------------------------------------------------------------ telling the user

    private void handle(List<AlertMonitor.Event> events) {
        for (AlertMonitor.Event event : events) {
            Alert alert = event.alert();
            try {
                log.save(alert);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Cannot save alert: {0}", e.getMessage());
            }
            String text = describe(alert);
            if (event.raised()) {
                LOG.log(System.Logger.Level.WARNING, "Alert {0} on {1}: {2}", alert.kind(), alert.address(),
                        alert.detail());
                unread++;
                workspace.toast("⚠ " + alert.branch() + ": " + text);
                notifyDesktop(alert.branch() + ": " + text, DesktopNotifier.Level.WARNING);
            } else if (alert.kind() != Alert.Kind.REBOOTED) {
                LOG.log(System.Logger.Level.INFO, "Alert {0} on {1} resolved", alert.kind(), alert.address());
                notifyDesktop(Messages.get("alert.resolved", alert.branch(), text), DesktopNotifier.Level.INFO);
            }
        }
        if (!events.isEmpty()) {
            showCount();
        }
    }

    private void notifyDesktop(String message, DesktopNotifier.Level level) {
        if (settings().desktop()) {
            DesktopNotifier.show(NetPilotApp.NAME, message, level,
                    NetPilotApp.class.getResource("icons/netpilot-32.png"));
        }
    }

    /** "CPU load 92%", "Interface ether1 is down", … in the app's language. */
    static String describe(Alert alert) {
        return Messages.get("alert.kind." + alert.kind().name(), alert.detail());
    }

    private void showCount() {
        bell.setText(unread == 0 ? "" : String.valueOf(unread));
        bell.getStyleClass().remove("alert-new");
        if (unread > 0) {
            bell.getStyleClass().add("alert-new");
        }
    }

    private void openList() {
        unread = 0;
        showCount();
        AlertsWindow.show(workspace, log.all());
    }

    private AlertSettings settings() {
        return workspace.settings().alerts();
    }

    private String branchName(String address) {
        return workspace.settings().router().branchAt(address).map(Branch::name).orElse(address);
    }

    private static void run(Task<?> task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }
}
