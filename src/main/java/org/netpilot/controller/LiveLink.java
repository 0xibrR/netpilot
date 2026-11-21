package org.netpilot.controller;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.netpilot.model.LogEntry;
import org.netpilot.model.RouterResource;
import org.netpilot.model.RouterSettings;
import org.netpilot.service.router.LiveFeed;
import org.netpilot.service.router.RouterClient;

/**
 * Brings the router's live updates to the pages, on the JavaFX thread. A burst of changes (ten
 * customers logging in at once) becomes one call, so pages read the router once, not ten times.
 * Pages register what they want in {@code attach}; nothing changes for them when live updates are
 * off: their usual reading every few seconds goes on.
 */
final class LiveLink implements LiveFeed.Listener {

    private static final System.Logger LOG = System.getLogger(LiveLink.class.getName());

    private final List<Runnable> online = new CopyOnWriteArrayList<>();
    private final List<Runnable> cards = new CopyOnWriteArrayList<>();
    private final List<Runnable> interfaces = new CopyOnWriteArrayList<>();
    private final List<Consumer<LogEntry>> log = new CopyOnWriteArrayList<>();
    private final List<Consumer<RouterResource>> resource = new CopyOnWriteArrayList<>();
    private final ReadOnlyObjectWrapper<LiveFeed.Status> status = new ReadOnlyObjectWrapper<>(
            new LiveFeed.Status(LiveFeed.State.STOPPED, java.util.Optional.empty()));
    private final AtomicBoolean onlinePending = new AtomicBoolean();
    private final AtomicBoolean cardsPending = new AtomicBoolean();
    private final AtomicBoolean interfacesPending = new AtomicBoolean();
    private final AtomicReference<RouterResource> latestResource = new AtomicReference<>();
    private LiveFeed feed;

    /** Starts live updates for this router when they are on in the settings. */
    void start(RouterClient client, RouterSettings settings) {
        stop();
        if (!settings.live() || client.apiLogin().isEmpty()) {
            return;
        }
        boolean tls = client.endpoint().https();
        if (settings.livePort() > 0) {
            begin(client, settings.livePort(), tls);
            return;
        }
        // automatic: the port set on the router (IP > Services), else the usual one
        int run = generation.incrementAndGet();
        status.set(new LiveFeed.Status(LiveFeed.State.CONNECTING, java.util.Optional.empty()));
        Thread lookup = new Thread(() -> {
            int port = settings.livePortFor(client.endpoint());
            try {
                port = client.apiServicePort(tls).orElse(port);
            } catch (org.netpilot.service.router.RouterException e) {
                LOG.log(System.Logger.Level.DEBUG, "Cannot read the API service port", e);
            }
            int chosen = port;
            Platform.runLater(() -> {
                if (run == generation.get()) {
                    begin(client, chosen, tls);
                }
            });
        }, "netpilot-live-port");
        lookup.setDaemon(true);
        lookup.start();
    }

    /** Counts starts and stops, so a slow port lookup never starts a feed after a newer start or a stop. */
    private final java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();

    private void begin(RouterClient client, int port, boolean tls) {
        client.apiLogin().ifPresent(login -> {
            LOG.log(System.Logger.Level.INFO, "Live updates on API port {0}", String.valueOf(port));
            feed = new LiveFeed(login, port, tls, this);
            feed.start();
        });
    }

    void stop() {
        generation.incrementAndGet();
        if (feed != null) {
            feed.close();
            feed = null;
        }
        status.set(new LiveFeed.Status(LiveFeed.State.STOPPED, java.util.Optional.empty()));
    }

    /** Whether updates arrive live right now (else pages read every few seconds). */
    boolean live() {
        return status.get().state() == LiveFeed.State.LIVE;
    }

    ReadOnlyObjectProperty<LiveFeed.Status> statusProperty() {
        return status.getReadOnlyProperty();
    }

    void onOnline(Runnable action) {
        online.add(action);
    }

    void onCards(Runnable action) {
        cards.add(action);
    }

    void onInterfaces(Runnable action) {
        interfaces.add(action);
    }

    void onLog(Consumer<LogEntry> action) {
        log.add(action);
    }

    void onResource(Consumer<RouterResource> action) {
        resource.add(action);
    }

    // ------------------------------------------------------------------ from the feed's thread

    @Override
    public void online() {
        coalesce(onlinePending, online);
    }

    @Override
    public void cards() {
        coalesce(cardsPending, cards);
    }

    @Override
    public void interfaces() {
        coalesce(interfacesPending, interfaces);
    }

    @Override
    public void log(LogEntry entry) {
        Platform.runLater(() -> log.forEach(l -> safely(() -> l.accept(entry))));
    }

    @Override
    public void resource(RouterResource reading) {
        // only the newest reading matters; one waiting call is enough
        if (latestResource.getAndSet(reading) == null) {
            Platform.runLater(() -> {
                RouterResource r = latestResource.getAndSet(null);
                if (r != null) {
                    resource.forEach(l -> safely(() -> l.accept(r)));
                }
            });
        }
    }

    @Override
    public void status(LiveFeed.Status next) {
        Platform.runLater(() -> status.set(next));
    }

    private static void coalesce(AtomicBoolean pending, List<Runnable> actions) {
        if (pending.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                pending.set(false);
                actions.forEach(LiveLink::safely);
            });
        }
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "A live update failed on a page", e);
        }
    }
}
