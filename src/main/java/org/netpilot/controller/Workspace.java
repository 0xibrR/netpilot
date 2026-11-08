package org.netpilot.controller;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javafx.stage.Window;
import org.netpilot.AppContext;
import org.netpilot.model.AppSettings;
import org.netpilot.model.HotspotProfile;
import org.netpilot.model.HotspotUser;

/**
 * What every page shares: services, the router session, the saved settings and a few actions
 * that belong to other pages (printing vouchers, switching page).
 */
final class Workspace {

    private static final System.Logger LOG = System.getLogger(Workspace.class.getName());

    private final AppContext context;
    private final RouterSession router;
    private final Supplier<Window> window;
    private AppSettings settings;
    private Consumer<List<HotspotUser>> printer = users -> {
    };
    private Consumer<PageId> navigator = page -> {
    };
    private Consumer<String> toaster = message -> {
    };
    private Runnable connector = () -> {
    };
    private Runnable languageReloader = () -> {
    };

    Workspace(AppContext context, RouterSession router, Supplier<Window> window, AppSettings settings) {
        this.context = context;
        this.router = router;
        this.window = window;
        this.settings = settings;
    }

    AppContext context() {
        return context;
    }

    RouterSession router() {
        return router;
    }

    Window window() {
        return window.get();
    }

    AppSettings settings() {
        return settings;
    }

    /** Changes and saves the settings. */
    void updateSettings(UnaryOperator<AppSettings> change) {
        settings = change.apply(settings);
        try {
            context.settingsRepository().save(settings);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot save settings", e);
        }
    }

    /** Profile names from the router when connected, otherwise the ones that have a price. */
    List<String> profileNames() {
        Optional<RouterSession.RouterLists> lists = router.lists();
        if (lists.isPresent()) {
            return lists.get().profiles().stream().map(HotspotProfile::name).toList();
        }
        return List.copyOf(settings.voucherTemplate().prices().keySet());
    }

    /** Exports PDF/CSV/RSC for these users (done by the Generate page). */
    void printVouchers(List<HotspotUser> users) {
        printer.accept(users);
    }

    void show(PageId page) {
        navigator.accept(page);
    }

    void setPrinter(Consumer<List<HotspotUser>> printer) {
        this.printer = printer;
    }

    void setNavigator(Consumer<PageId> navigator) {
        this.navigator = navigator;
    }

    /** Shows a short message that fades out by itself. */
    void toast(String message) {
        toaster.accept(message);
    }

    /** Opens the router connection dialog. */
    void connect() {
        connector.run();
    }

    void setToaster(Consumer<String> toaster) {
        this.toaster = toaster;
    }

    void setConnector(Runnable connector) {
        this.connector = connector;
    }

    /** Rebuilds the main window in the language saved in the settings, keeping the connection. */
    void reloadLanguage() {
        languageReloader.run();
    }

    void setLanguageReloader(Runnable reloader) {
        this.languageReloader = reloader;
    }
}
